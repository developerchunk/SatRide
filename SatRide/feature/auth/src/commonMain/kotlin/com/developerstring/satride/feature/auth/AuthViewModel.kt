package com.developerstring.satride.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.developerstring.satride.core.auth.AuthFailure
import com.developerstring.satride.core.auth.AuthRepository
import com.developerstring.satride.core.auth.AuthState
import com.developerstring.satride.core.auth.Countries
import com.developerstring.satride.core.auth.Country
import com.developerstring.satride.core.auth.GoogleTokens
import com.developerstring.satride.core.auth.ProfileRepository
import com.developerstring.satride.core.auth.UserProfile
import com.developerstring.satride.core.auth.userMessage
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

data class SignInUiState(
    val signingIn: Boolean = false,
    val signInAvailable: Boolean = true,
    val error: String? = null,
)

sealed interface SignInEffect {
    /** Signed in and a profile already exists — nothing more to ask. */
    data object Done : SignInEffect

    /** Signed in for the first time; collect a name before going on. */
    data object NeedsProfile : SignInEffect

    data class ShowMessage(val text: String) : SignInEffect
}

/**
 * Signing in.
 *
 * The token is obtained by the screen — see [GoogleSignInLauncher] — and handed here, which is what
 * keeps this whole class testable without an Activity or a device.
 */
class SignInViewModel(private val authRepository: AuthRepository, private val profileRepository: ProfileRepository) :
    ViewModel() {

    private val mutableState = MutableStateFlow(SignInUiState())
    val state: StateFlow<SignInUiState> = mutableState.asStateFlow()

    private val effectChannel = Channel<SignInEffect>(Channel.BUFFERED, BufferOverflow.DROP_OLDEST)
    val effects: Flow<SignInEffect> = effectChannel.receiveAsFlow()

    fun onLauncherAvailability(available: Boolean) {
        mutableState.value = mutableState.value.copy(signInAvailable = available)
    }

    /**
     * @param requestToken the platform's account picker, already presented by the screen.
     */
    fun signIn(requestToken: suspend () -> Result<GoogleTokens>) {
        if (mutableState.value.signingIn) return
        mutableState.value = mutableState.value.copy(signingIn = true, error = null)

        viewModelScope.launch {
            val token = requestToken()
                .getOrElse { error ->
                    finishWithFailure((error as? GoogleSignInException)?.failure ?: AuthFailure.Unexpected(error))
                    return@launch
                }

            authRepository.signInWithGoogle(token)
                .onSuccess {
                    mutableState.value = mutableState.value.copy(signingIn = false)
                    // A returning user should not be asked for a name they already gave. Firestore
                    // answers from its cache when offline, so this does not block on the network.
                    val existing = runCatching { profileRepository.observe().firstOrNullSafe() }.getOrNull()
                    effectChannel.trySend(
                        if (existing == null) SignInEffect.NeedsProfile else SignInEffect.Done,
                    )
                }
                .onFailure { finishWithFailure(AuthFailure.Unexpected(it)) }
        }
    }

    private fun finishWithFailure(failure: AuthFailure) {
        mutableState.value = mutableState.value.copy(signingIn = false, error = failure.userMessage())
        // Cancelling is a decision, not a fault: `userMessage()` returns null for it and nothing
        // is shown.
        failure.userMessage()?.let { effectChannel.trySend(SignInEffect.ShowMessage(it)) }
    }
}

// --- create profile ---------------------------------------------------------------------------

data class CreateProfileUiState(
    val name: String = "",
    val countryQuery: String = "",
    val selectedCountry: Country? = null,
    val countryPickerOpen: Boolean = false,
    val referredBy: String = "",
    val saving: Boolean = false,
    val error: String? = null,
) {
    val countryResults: List<Country> get() = Countries.search(countryQuery)

    /** Only the name is required — the rest is genuinely optional and stays that way. */
    val canSave: Boolean get() = name.isNotBlank() && !saving
}

sealed interface CreateProfileEvent {
    data class NameChanged(val value: String) : CreateProfileEvent
    data class CountryQueryChanged(val value: String) : CreateProfileEvent
    data class CountrySelected(val country: Country?) : CreateProfileEvent
    data object CountryPickerOpened : CreateProfileEvent
    data object CountryPickerDismissed : CreateProfileEvent
    data class ReferredByChanged(val value: String) : CreateProfileEvent
    data object Save : CreateProfileEvent
}

sealed interface CreateProfileEffect {
    data object Saved : CreateProfileEffect
    data class ShowMessage(val text: String) : CreateProfileEffect
}

class CreateProfileViewModel(
    private val authRepository: AuthRepository,
    private val profileRepository: ProfileRepository,
) : ViewModel() {

    private val mutableState = MutableStateFlow(CreateProfileUiState())
    val state: StateFlow<CreateProfileUiState> = mutableState.asStateFlow()

    private val effectChannel = Channel<CreateProfileEffect>(Channel.BUFFERED, BufferOverflow.DROP_OLDEST)
    val effects: Flow<CreateProfileEffect> = effectChannel.receiveAsFlow()

    init {
        // Seeded from the Google account, because a form that is already filled in correctly is
        // faster to agree with than an empty one is to complete. It stays editable — this is the
        // name the user chooses for SatRide, not the one on their email.
        (authRepository.state.value as? AuthState.SignedIn)?.user?.googleDisplayName?.let { name ->
            mutableState.value = mutableState.value.copy(name = name)
        }
    }

    fun onEvent(event: CreateProfileEvent) {
        val current = mutableState.value
        when (event) {
            is CreateProfileEvent.NameChanged ->
                mutableState.value = current.copy(name = event.value.take(UserProfile.MAX_NAME_LENGTH), error = null)

            is CreateProfileEvent.CountryQueryChanged ->
                mutableState.value = current.copy(countryQuery = event.value)

            is CreateProfileEvent.CountrySelected ->
                mutableState.value = current.copy(
                    selectedCountry = event.country,
                    countryPickerOpen = false,
                    countryQuery = "",
                )

            CreateProfileEvent.CountryPickerOpened ->
                mutableState.value = current.copy(countryPickerOpen = true, countryQuery = "")

            CreateProfileEvent.CountryPickerDismissed ->
                mutableState.value = current.copy(countryPickerOpen = false, countryQuery = "")

            is CreateProfileEvent.ReferredByChanged ->
                mutableState.value =
                    current.copy(referredBy = event.value.take(UserProfile.MAX_REFERRAL_LENGTH))

            CreateProfileEvent.Save -> save()
        }
    }

    private fun save() {
        val current = mutableState.value
        if (!current.canSave) return
        mutableState.value = current.copy(saving = true, error = null)

        viewModelScope.launch {
            val profile = UserProfile.of(
                displayName = current.name,
                countryCode = current.selectedCountry?.code,
                referredBy = current.referredBy,
            )

            profileRepository.save(profile)
                .onSuccess {
                    mutableState.value = mutableState.value.copy(saving = false)
                    effectChannel.trySend(CreateProfileEffect.Saved)
                }
                .onFailure { error ->
                    mutableState.value = mutableState.value.copy(
                        saving = false,
                        error = error.message ?: "Could not save your profile.",
                    )
                    effectChannel.trySend(
                        CreateProfileEffect.ShowMessage(error.message ?: "Could not save your profile."),
                    )
                }
        }
    }
}

/** The first value, or null if it does not arrive promptly. */
private suspend fun <T> Flow<T>.firstOrNullSafe(): T? = withTimeoutOrNull(PROFILE_LOOKUP_TIMEOUT_MS) { firstOrNull() }

/**
 * How long to wait for Firestore before assuming there is no profile.
 *
 * Bounded because this sits between the user tapping "sign in" and the app moving on. If the answer
 * has not arrived in two seconds the worst outcome is that a returning user is shown the profile
 * form with their existing name already in it — mildly redundant, and far better than a spinner
 * that never resolves on a bad connection.
 */
private const val PROFILE_LOOKUP_TIMEOUT_MS = 2_000L
