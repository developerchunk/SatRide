package com.developerstring.satride.core.auth

import co.touchlab.kermit.Logger
import com.developerstring.satride.core.common.AppScopeQualifier
import kotlinx.coroutines.CoroutineScope
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Identity wiring.
 *
 * Application-scoped, like the tracking engine and the catalog refresher: the session outlives every
 * screen, and a `viewModelScope`-bound one would be torn down and restored on each navigation.
 *
 * Both bindings fall back to an unavailable implementation if Firebase cannot start. That is not
 * defensive padding — it is the desktop build, which has no `google-services.json`, and an iOS build
 * whose SDK has not been linked in Xcode yet. `Firebase.auth` throws in both cases, and a throw here
 * would crash the app the first time anyone opened the profile tab rather than showing them a
 * signed-out screen with a disabled button, which is the honest answer.
 */
val authModule: Module =
    module {
        single<AuthRepository> {
            runCatching { FirebaseAuthRepository(scope = get<CoroutineScope>(AppScopeQualifier)) }
                .getOrElse {
                    // Deliberately without the throwable. This is an expected condition on desktop
                    // and on iOS before the SDK is linked, and a full stack trace on every launch
                    // reads as a crash to anyone scanning the log.
                    Logger.withTag("Auth").i { "Firebase unavailable (${it.message}); sign-in disabled in this build" }
                    UnavailableAuthRepository()
                }
        }

        single<ProfileRepository> {
            val auth = get<AuthRepository>()
            if (auth is UnavailableAuthRepository) {
                UnavailableProfileRepository()
            } else {
                runCatching { FirestoreProfileRepository(authRepository = auth) }
                    .getOrElse { UnavailableProfileRepository() }
            }
        }
    }
