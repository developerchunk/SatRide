package com.developerstring.satride.feature.auth

import com.developerstring.satride.core.auth.AuthFailure
import com.developerstring.satride.core.auth.AuthRepository
import com.developerstring.satride.core.auth.AuthState
import com.developerstring.satride.core.auth.Countries
import com.developerstring.satride.core.auth.GoogleTokens
import com.developerstring.satride.core.auth.ProfileRepository
import com.developerstring.satride.core.auth.SatRideUser
import com.developerstring.satride.core.auth.UserProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SignInViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun aFirstTimeUserIsAskedForAProfile() = runTest(dispatcher) {
        val model = SignInViewModel(FakeAuth(), FakeProfiles(profile = null))

        model.signIn { Result.success(GoogleTokens(idToken = "token")) }

        assertEquals(SignInEffect.NeedsProfile, model.effects.first())
    }

    @Test
    fun aReturningUserIsNotAskedAgain() = runTest(dispatcher) {
        val model = SignInViewModel(FakeAuth(), FakeProfiles(profile = UserProfile("Aditya")))

        model.signIn { Result.success(GoogleTokens(idToken = "token")) }

        assertEquals(SignInEffect.Done, model.effects.first())
    }

    @Test
    fun cancellingSaysNothingAtAll() = runTest(dispatcher) {
        val model = SignInViewModel(FakeAuth(), FakeProfiles(null))

        // Dismissing the account picker is a decision, not a fault. An error message here would
        // tell the user they did something wrong when they simply changed their mind.
        model.signIn { Result.failure(GoogleSignInException(AuthFailure.Cancelled)) }
        testScheduler.advanceUntilIdle()

        assertNull(model.state.value.error)
        assertFalse(model.state.value.signingIn)
    }

    @Test
    fun aMissingAccountIsExplained() = runTest(dispatcher) {
        val model = SignInViewModel(FakeAuth(), FakeProfiles(null))

        model.signIn { Result.failure(GoogleSignInException(AuthFailure.NoAccountAvailable)) }
        testScheduler.advanceUntilIdle()

        assertTrue(model.state.value.error!!.contains("No Google account"))
    }

    @Test
    fun aFailedExchangeIsReportedAndDoesNotLeaveTheButtonSpinning() = runTest(dispatcher) {
        val model = SignInViewModel(FakeAuth(exchangeFails = true), FakeProfiles(null))

        model.signIn { Result.success(GoogleTokens(idToken = "token")) }
        testScheduler.advanceUntilIdle()

        assertFalse(model.state.value.signingIn)
        assertTrue(model.state.value.error != null)
    }

    @Test
    fun tappingTwiceDoesNotStartTwoSignIns() = runTest(dispatcher) {
        var calls = 0
        val model = SignInViewModel(FakeAuth(), FakeProfiles(null))

        model.signIn {
            calls++
            Result.success(GoogleTokens(idToken = "token"))
        }
        model.signIn {
            calls++
            Result.success(GoogleTokens(idToken = "token"))
        }
        testScheduler.advanceUntilIdle()

        assertEquals(1, calls)
    }
}

class CreateProfileViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun theNameIsPreFilledFromTheGoogleAccount() = runTest(dispatcher) {
        val model = CreateProfileViewModel(FakeAuth(displayName = "Aditya Shinde"), FakeProfiles(null))
        assertEquals("Aditya Shinde", model.state.value.name)
    }

    @Test
    fun onlyTheNameIsRequired() = runTest(dispatcher) {
        val profiles = FakeProfiles(null)
        val model = CreateProfileViewModel(FakeAuth(displayName = null), profiles)

        assertFalse(model.state.value.canSave)
        model.onEvent(CreateProfileEvent.NameChanged("Aditya"))
        assertTrue(model.state.value.canSave)

        model.onEvent(CreateProfileEvent.Save)
        testScheduler.advanceUntilIdle()

        // Saved with no country and no referral, both of which stay null rather than "".
        val saved = profiles.saved!!
        assertEquals("Aditya", saved.displayName)
        assertNull(saved.countryCode)
        assertNull(saved.referredBy)
    }

    @Test
    fun whitespaceIsNotAName() = runTest(dispatcher) {
        val model = CreateProfileViewModel(FakeAuth(displayName = null), FakeProfiles(null))
        model.onEvent(CreateProfileEvent.NameChanged("   "))
        assertFalse(model.state.value.canSave)
    }

    @Test
    fun aSelectedCountryIsSavedAsItsCode() = runTest(dispatcher) {
        val profiles = FakeProfiles(null)
        val model = CreateProfileViewModel(FakeAuth(displayName = null), profiles)

        model.onEvent(CreateProfileEvent.NameChanged("Aditya"))
        model.onEvent(CreateProfileEvent.CountrySelected(Countries.byCode("IN")))
        model.onEvent(CreateProfileEvent.Save)
        testScheduler.advanceUntilIdle()

        assertEquals("IN", profiles.saved?.countryCode)
    }

    @Test
    fun choosingACountryClosesThePickerAndClearsTheQuery() = runTest(dispatcher) {
        val model = CreateProfileViewModel(FakeAuth(displayName = null), FakeProfiles(null))

        model.onEvent(CreateProfileEvent.CountryPickerOpened)
        model.onEvent(CreateProfileEvent.CountryQueryChanged("ind"))
        assertTrue(model.state.value.countryResults.isNotEmpty())

        model.onEvent(CreateProfileEvent.CountrySelected(Countries.byCode("IN")))

        assertFalse(model.state.value.countryPickerOpen)
        assertEquals("", model.state.value.countryQuery)
    }

    @Test
    fun aCountryCanBeClearedBackToUnanswered() = runTest(dispatcher) {
        val model = CreateProfileViewModel(FakeAuth(displayName = null), FakeProfiles(null))
        model.onEvent(CreateProfileEvent.CountrySelected(Countries.byCode("IN")))
        model.onEvent(CreateProfileEvent.CountrySelected(null))
        assertNull(model.state.value.selectedCountry)
    }

    @Test
    fun overlongInputIsCappedAtWhatTheRulesAccept() = runTest(dispatcher) {
        val model = CreateProfileViewModel(FakeAuth(displayName = null), FakeProfiles(null))

        model.onEvent(CreateProfileEvent.NameChanged("x".repeat(500)))
        model.onEvent(CreateProfileEvent.ReferredByChanged("y".repeat(500)))

        assertEquals(UserProfile.MAX_NAME_LENGTH, model.state.value.name.length)
        assertEquals(UserProfile.MAX_REFERRAL_LENGTH, model.state.value.referredBy.length)
    }

    @Test
    fun aFailedSaveSurfacesAndClearsTheSavingFlag() = runTest(dispatcher) {
        val model = CreateProfileViewModel(FakeAuth(displayName = null), FakeProfiles(null, saveFails = true))

        model.onEvent(CreateProfileEvent.NameChanged("Aditya"))
        model.onEvent(CreateProfileEvent.Save)
        testScheduler.advanceUntilIdle()

        assertFalse(model.state.value.saving)
        assertTrue(model.state.value.error != null)
    }
}

// --- fakes --------------------------------------------------------------------------------------

private class FakeAuth(displayName: String? = "Aditya", private val exchangeFails: Boolean = false) : AuthRepository {
    override val state: StateFlow<AuthState> = MutableStateFlow(
        AuthState.SignedIn(SatRideUser(uid = "uid-1", email = "a@example.com", googleDisplayName = displayName)),
    )

    override suspend fun signInWithGoogle(tokens: GoogleTokens): Result<SatRideUser> = if (exchangeFails) {
        Result.failure(IllegalStateException("nope"))
    } else {
        Result.success(SatRideUser("uid-1"))
    }

    // The email path is the reviewers' door, not the sign-in screen's; no test here exercises it.
    override suspend fun signInWithEmail(email: String, password: String): Result<SatRideUser> =
        Result.failure(IllegalStateException("not under test"))

    override suspend fun signOut() = Unit
    override suspend fun deleteAccount(): Result<Unit> = Result.success(Unit)
}

private class FakeProfiles(profile: UserProfile?, private val saveFails: Boolean = false) : ProfileRepository {
    private val flow = MutableStateFlow(profile)
    var saved: UserProfile? = null

    override fun observe(): Flow<UserProfile?> = flow

    override suspend fun save(profile: UserProfile): Result<Unit> {
        if (saveFails) return Result.failure(IllegalStateException("offline"))
        saved = profile
        flow.value = profile
        return Result.success(Unit)
    }

    override suspend fun delete(): Result<Unit> {
        flow.value = null
        return Result.success(Unit)
    }
}
