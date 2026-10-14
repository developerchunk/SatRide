package com.developerstring.satride.feature.play.multiplay.net

import co.touchlab.kermit.Logger
import com.developerstring.satride.core.auth.AuthRepository
import com.developerstring.satride.core.auth.ProfileRepository
import com.developerstring.satride.core.datastore.SettingsRepository
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.auth.auth
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** Who this phone is in a room. */
data class PlayerIdentityInfo(
    val uid: String,
    val name: String,
    /** True when the uid is an anonymous Firebase session rather than an account. */
    val isGuest: Boolean,
)

/**
 * Resolves a uid and a display name for the arena.
 *
 * Signed-in players race under their profile name. Everyone else gets an *anonymous* Firebase
 * session — a real uid the security rules can hold to account, with no sign-up in the way of
 * playing. The app's own `AuthRepository` deliberately does not treat that session as "signed
 * in" (see its `isAnonymous` check), so the profile tab keeps offering sign-in and nothing else
 * in the app changes because someone played a race.
 */
class PlayerIdentity(
    private val auth: AuthRepository,
    private val profiles: ProfileRepository,
    private val settings: SettingsRepository,
) {
    private val logger = Logger.withTag("PlayerIdentity")

    suspend fun resolve(): Result<PlayerIdentityInfo> = runCatching {
        // Wait briefly for the session to be restored; Unknown on a cold start is normal.
        withTimeoutOrNull(2_500L) { auth.state.first { it.isResolved } }
        val account = auth.state.value.userOrNull
        val guestName = guestName()
        if (account != null) {
            val profileName = withTimeoutOrNull(1_500L) { profiles.observe().first() }?.displayName
            return@runCatching PlayerIdentityInfo(
                uid = account.uid,
                name = (profileName ?: account.googleDisplayName ?: guestName).take(MAX_NAME),
                isGuest = false,
            )
        }
        val firebaseAuth = Firebase.auth
        val user = firebaseAuth.currentUser ?: firebaseAuth.signInAnonymously().user
            ?: error("Anonymous sign-in returned no user.")
        PlayerIdentityInfo(uid = user.uid, name = guestName, isGuest = true)
    }.onFailure { logger.w { "identity unavailable: ${it.message}" } }

    /** The name a guest races under. Persisted so it is the same next time. */
    suspend fun guestName(): String {
        val existing = settings.current().multiplayGuestName
        if (!existing.isNullOrBlank()) return existing
        val generated = "Pilot " + (1000 + kotlin.random.Random.nextInt(9000))
        settings.setMultiplayGuestName(generated)
        return generated
    }

    suspend fun setGuestName(name: String) {
        settings.setMultiplayGuestName(name.trim().take(MAX_NAME))
    }

    companion object {
        const val MAX_NAME: Int = 16
    }
}
