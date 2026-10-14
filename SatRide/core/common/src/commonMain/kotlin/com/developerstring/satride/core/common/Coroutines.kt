package com.developerstring.satride.core.common

import kotlinx.coroutines.CancellationException

/**
 * [runCatching] for suspending code.
 *
 * The standard-library version catches [CancellationException] along with everything else,
 * which turns a cancelled coroutine into a `Result.failure` and lets the caller carry on
 * working inside a scope that has already been torn down. In the data layer that shows up as a
 * refresh that keeps writing to the database after the screen is gone. This rethrows
 * cancellation and catches the rest.
 */
inline fun <T> runCatchingCancellable(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (cancellation: CancellationException) {
    throw cancellation
} catch (throwable: Throwable) {
    Result.failure(throwable)
}
