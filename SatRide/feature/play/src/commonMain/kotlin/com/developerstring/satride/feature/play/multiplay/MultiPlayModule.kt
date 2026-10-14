package com.developerstring.satride.feature.play.multiplay

import co.touchlab.kermit.Logger
import com.developerstring.satride.core.auth.AuthRepository
import com.developerstring.satride.core.auth.UnavailableAuthRepository
import com.developerstring.satride.feature.play.GlobeAssets
import com.developerstring.satride.feature.play.multiplay.net.FirestoreRoomRepository
import com.developerstring.satride.feature.play.multiplay.net.PlayerIdentity
import com.developerstring.satride.feature.play.multiplay.net.RoomRepository
import com.developerstring.satride.feature.play.multiplay.net.UnavailableRoomRepository
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

/**
 * Multi-Play's wiring. Lives beside `playModule` and is aggregated the same way.
 *
 * The room backend follows the auth module's rule: if Firebase could not start, the arena still
 * opens — solo play needs nothing from the network — and every room button says why it is off.
 */
val multiPlayModule: Module = module {
    single { GlobeAssets(textureSource = get(), dispatchers = get()) }

    single<RoomRepository> {
        val auth = get<AuthRepository>()
        if (auth is UnavailableAuthRepository) {
            UnavailableRoomRepository()
        } else {
            runCatching { FirestoreRoomRepository() }
                .getOrElse {
                    Logger.withTag("MultiPlay").i { "Firestore unavailable (${it.message}); rooms disabled" }
                    UnavailableRoomRepository()
                }
        }
    }

    single { PlayerIdentity(auth = get(), profiles = get(), settings = get()) }

    viewModelOf(::MultiPlayViewModel)
}
