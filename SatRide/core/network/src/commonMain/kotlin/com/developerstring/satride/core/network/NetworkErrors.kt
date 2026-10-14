package com.developerstring.satride.core.network

import com.developerstring.satride.core.common.AppError
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.ResponseException
import kotlinx.io.IOException
import kotlinx.serialization.SerializationException

/**
 * Turns whatever Ktor threw into an [AppError].
 *
 * This is the only place in the app that knows Ktor's exception types. Above it, a refresh
 * failure is "offline" or "the server said no" — states a screen can render — rather than a
 * transport detail every caller has to re-interpret.
 */
fun Throwable.asAppError(context: String): AppError = when (this) {
    is AppError -> this

    is ResponseException -> AppError.Server(
        statusCode = response.status.value,
        message = "$context failed: ${response.status}",
        cause = this,
    )

    // A timeout is more useful to the user as "check your connection" than as a server fault:
    // in practice it nearly always means a weak mobile signal, not a broken endpoint.
    is HttpRequestTimeoutException -> AppError.Offline(this)

    is IOException -> AppError.Offline(this)

    is SerializationException -> AppError.Malformed(
        message = "$context returned data SatRide could not read",
        cause = this,
    )

    else -> AppError.Unexpected("$context failed: ${message ?: this::class.simpleName}", this)
}
