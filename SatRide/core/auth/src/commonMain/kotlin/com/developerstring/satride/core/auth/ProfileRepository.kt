package com.developerstring.satride.core.auth

import co.touchlab.kermit.Logger
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.firestore.FieldValue
import dev.gitlive.firebase.firestore.FirebaseFirestore
import dev.gitlive.firebase.firestore.firestore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * The user's own profile document, at `users/{uid}`.
 *
 * The path *is* the ownership check — see `docs/plan/08-firestore-rules.md`. There is deliberately
 * no way to read another user's document from here, because the rules would refuse it anyway and an
 * API that can only fail is worse than one that does not exist.
 */
interface ProfileRepository {
    /** Emits null when signed out, or when a signed-in user has not created a profile yet. */
    fun observe(): Flow<UserProfile?>

    suspend fun save(profile: UserProfile): Result<Unit>

    suspend fun delete(): Result<Unit>
}

@OptIn(ExperimentalCoroutinesApi::class)
class FirestoreProfileRepository(
    private val authRepository: AuthRepository,
    private val firestore: FirebaseFirestore = Firebase.firestore,
) : ProfileRepository {

    private val logger = Logger.withTag("Profile")

    override fun observe(): Flow<UserProfile?> = authRepository.state
        .flatMapLatest { auth ->
            when (auth) {
                // Not "no profile" — nothing is known yet, and emitting null here would make the
                // profile screen show its "create a profile" prompt for a frame on every launch.
                AuthState.Unknown -> flowOf(null)

                AuthState.SignedOut -> flowOf(null)

                is AuthState.SignedIn -> firestore.collection(COLLECTION)
                    .document(auth.user.uid)
                    .snapshots
                    .map { snapshot -> if (snapshot.exists) snapshot.data<UserProfile>() else null }
            }
        }
        .catch { error ->
            // A permission error here means the rules and the client disagree, which is a
            // programming mistake rather than a user-facing one. Surfacing null keeps the screen
            // usable while the log says what actually happened.
            // Also covers a build with no FirebaseApp at all, where the Firestore handle resolves
            // and only fails on collection — desktop, and iOS before the SDK is linked.
            logger.w(error) { "reading the profile failed" }
            emit(null)
        }

    override suspend fun save(profile: UserProfile): Result<Unit> {
        val uid = authRepository.state.value.userOrNull?.uid
            ?: return Result.failure(IllegalStateException("Not signed in."))
        if (!profile.isValid) {
            return Result.failure(IllegalArgumentException("A name is required."))
        }

        return runCatching {
            val document = firestore.collection(COLLECTION).document(uid)
            val existing = document.get()

            // `createdAt` is written once and never again — the rules enforce it, and a client that
            // could rewrite it could backdate its own account, which matters the moment referrals
            // are worth anything. Both timestamps are the *server's*, not the phone's.
            val fields = buildMap {
                put("displayName", profile.displayName)
                put("countryCode", profile.countryCode)
                put("referredBy", profile.referredBy)
                if (!existing.exists) put("createdAt", FieldValue.serverTimestamp)
                put("updatedAt", FieldValue.serverTimestamp)
            }

            // merge = true so an edit that omits a field does not delete it.
            document.set(fields, merge = true)
        }.onFailure { logger.w(it) { "saving the profile failed" } }
    }

    override suspend fun delete(): Result<Unit> {
        val uid = authRepository.state.value.userOrNull?.uid
            ?: return Result.failure(IllegalStateException("Not signed in."))
        return runCatching { firestore.collection(COLLECTION).document(uid).delete() }
            .onFailure { logger.w(it) { "deleting the profile failed" } }
    }

    private companion object {
        const val COLLECTION = "users"
    }
}
