package com.developerstring.satride.feature.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.developerstring.satride.core.auth.AuthRepository
import com.developerstring.satride.core.auth.AuthState
import com.developerstring.satride.core.auth.Countries
import com.developerstring.satride.core.auth.Country
import com.developerstring.satride.core.auth.ProfileRepository
import com.developerstring.satride.core.auth.SatRideUser
import com.developerstring.satride.core.auth.UserProfile
import com.developerstring.satride.core.common.asCompassPoint
import com.developerstring.satride.core.common.format
import com.developerstring.satride.core.model.ObserverPosition
import com.developerstring.satride.core.sensors.LocationProvider
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ProfileUiState(
    val authState: AuthState = AuthState.Unknown,
    val profile: UserProfile? = null,
    val busy: Boolean = false,
    val confirmingDelete: Boolean = false,
    val observer: ObserverPosition = ObserverPosition.Unknown,
    val showCoordinates: Boolean = false,
) {
    val user: SatRideUser? get() = authState.userOrNull

    val isSignedIn: Boolean get() = authState is AuthState.SignedIn

    /**
     * True only while the session is genuinely unknown.
     *
     * Drives a shimmer rather than the signed-out prompt: showing "sign in" to somebody who *is*
     * signed in, for the frame before Firebase answers, reads as the app having lost their account.
     */
    val loading: Boolean get() = authState == AuthState.Unknown

    val country: Country? get() = Countries.byCode(profile?.countryCode)

    /** The name to show: what the user chose, falling back to what Google told us. */
    val displayName: String?
        get() = profile?.displayName ?: user?.googleDisplayName

    /**
     * Where the app believes the user is, in the form a person can check.
     *
     * Degrees to four decimals — about eleven metres, finer than any fix the app asks for and
     * coarse enough not to imply precision that is not there. Hemispheres as letters rather than
     * signs, because "18.8480° N" is checkable against a map and "-18.848" invites a sign error.
     */
    val coordinates: String?
        get() = observer.geoPos?.let { position ->
            val latHemisphere = if (position.latDeg >= 0) "N" else "S"
            val lonHemisphere = if (position.lonDeg >= 0) "E" else "W"
            buildString {
                append(kotlin.math.abs(position.latDeg).format(4))
                append("° ")
                append(latHemisphere)
                append("  ")
                append(kotlin.math.abs(position.lonDeg).format(4))
                append("° ")
                append(lonHemisphere)
            }
        }

    /** How the position was obtained, and how good it is — the part that explains a wrong answer. */
    val locationSource: String
        get() = when (val current = observer) {
            ObserverPosition.Unknown -> "No location yet"

            is ObserverPosition.Manual -> "Entered by hand"

            is ObserverPosition.Automatic ->
                current.accuracyM
                    ?.let { "From this device · ±${it.format(0)} m" }
                    ?: "From this device"
        }
}

sealed interface ProfileEvent {
    data object CoordinatesToggled : ProfileEvent
    data object SignInRequested : ProfileEvent
    data object SignOutRequested : ProfileEvent
    data object EditRequested : ProfileEvent
    data object DeleteRequested : ProfileEvent
    data object DeleteConfirmed : ProfileEvent
    data object DeleteDismissed : ProfileEvent
}

sealed interface ProfileEffect {
    data object OpenSignIn : ProfileEffect
    data object OpenEdit : ProfileEffect
    data class ShowMessage(val text: String) : ProfileEffect
}

/**
 * The profile tab.
 *
 * Signed out is an ordinary state here, not an error — the tab is useful either way, and for a
 * signed-out user it is simply an offer.
 */
class ProfileViewModel(
    private val authRepository: AuthRepository,
    private val profileRepository: ProfileRepository,
    private val locationProvider: LocationProvider,
) : ViewModel() {

    private val busy = MutableStateFlow(false)
    private val confirmingDelete = MutableStateFlow(false)
    private val showCoordinates = MutableStateFlow(false)

    private val effectChannel = Channel<ProfileEffect>(Channel.BUFFERED, BufferOverflow.DROP_OLDEST)
    val effects: Flow<ProfileEffect> = effectChannel.receiveAsFlow()

    val state: StateFlow<ProfileUiState> = combine(
        authRepository.state,
        profileRepository.observe(),
        combine(busy, confirmingDelete, showCoordinates) { busy, confirming, coordinates ->
            Triple(busy, confirming, coordinates)
        },
        locationProvider.position,
    ) { auth, profile, (busy, confirming, coordinates), observer ->
        ProfileUiState(
            authState = auth,
            profile = profile,
            busy = busy,
            confirmingDelete = confirming,
            observer = observer,
            showCoordinates = coordinates,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS), ProfileUiState())

    fun onEvent(event: ProfileEvent) {
        when (event) {
            ProfileEvent.CoordinatesToggled -> {
                showCoordinates.value = !showCoordinates.value
                // Asking for a fresh fix on open is the point: this panel exists so a user can
                // check the app is using where they actually are, and showing them a cached answer
                // would defeat it.
                if (showCoordinates.value) viewModelScope.launch { locationProvider.requestFix() }
            }

            ProfileEvent.SignInRequested -> effectChannel.trySend(ProfileEffect.OpenSignIn)

            ProfileEvent.EditRequested -> effectChannel.trySend(ProfileEffect.OpenEdit)

            ProfileEvent.SignOutRequested -> viewModelScope.launch {
                busy.value = true
                authRepository.signOut()
                busy.value = false
            }

            ProfileEvent.DeleteRequested -> confirmingDelete.value = true

            ProfileEvent.DeleteDismissed -> confirmingDelete.value = false

            ProfileEvent.DeleteConfirmed -> deleteAccount()
        }
    }

    /**
     * Deletes the profile document first, then the auth user.
     *
     * That order is not arbitrary: the Firestore rules only permit a delete from the document's
     * owner, and once the auth user is gone there is no owner left. Reversing it strands the
     * document permanently, readable by nobody and deletable by nobody.
     */
    private fun deleteAccount() {
        viewModelScope.launch {
            busy.value = true
            confirmingDelete.value = false

            profileRepository.delete()
                .onFailure { effectChannel.trySend(ProfileEffect.ShowMessage("Could not delete your profile data.")) }

            authRepository.deleteAccount()
                .onSuccess { effectChannel.trySend(ProfileEffect.ShowMessage("Your account has been deleted.")) }
                .onFailure {
                    // Firebase requires a recent sign-in before it will delete an account.
                    effectChannel.trySend(
                        ProfileEffect.ShowMessage("Sign in again, then delete — Google needs a recent sign-in."),
                    )
                }

            busy.value = false
        }
    }

    private companion object {
        const val SUBSCRIPTION_TIMEOUT_MS = 5_000L
    }
}
