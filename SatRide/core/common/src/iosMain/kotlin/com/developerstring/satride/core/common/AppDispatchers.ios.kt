package com.developerstring.satride.core.common

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Kotlin/Native has no separate IO pool, so this is the default pool with room to block.
 *
 * The parallelism matters: SQLite and network calls park their thread, and without a dedicated
 * slice they would occupy the same workers the propagator runs on — starving the AR frame loop
 * during a catalog refresh.
 */
internal actual val platformIoDispatcher: CoroutineDispatcher =
    Dispatchers.Default.limitedParallelism(PARALLELISM)

private const val PARALLELISM = 8
