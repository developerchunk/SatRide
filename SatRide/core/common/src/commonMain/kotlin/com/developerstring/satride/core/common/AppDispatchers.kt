package com.developerstring.satride.core.common

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * The dispatchers the app runs on, injected rather than referenced directly.
 *
 * Every layer takes this instead of touching [Dispatchers] so tests can substitute a single
 * deterministic dispatcher. Code that hard-codes an IO dispatcher cannot be run under `runTest`'s
 * virtual clock, and a data layer whose timing cannot be controlled is a data layer whose
 * staleness and retry logic cannot be tested.
 */
interface AppDispatchers {
    /** CPU-bound work: SGP4 propagation, DSP, parsing a large catalog. */
    val default: CoroutineDispatcher

    /** Blocking I/O: network, disk, database. */
    val io: CoroutineDispatcher

    /** The UI thread. */
    val main: CoroutineDispatcher
}

/**
 * A dispatcher for blocking work.
 *
 * `Dispatchers.IO` is not visible from common code — it is declared per platform and is internal
 * in the common source set — so this is the one place the difference has to be spelled out.
 */
internal expect val platformIoDispatcher: CoroutineDispatcher

/** Production dispatchers. */
class DefaultAppDispatchers : AppDispatchers {
    override val default: CoroutineDispatcher = Dispatchers.Default
    override val io: CoroutineDispatcher = platformIoDispatcher
    override val main: CoroutineDispatcher = Dispatchers.Main
}
