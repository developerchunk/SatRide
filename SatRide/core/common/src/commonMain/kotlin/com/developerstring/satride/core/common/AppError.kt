package com.developerstring.satride.core.common

/**
 * A failure the user might need to hear about, phrased in terms of what went wrong rather than
 * which exception was thrown.
 *
 * The data layer converts exceptions into these at its boundary so features never catch
 * `IOException` or `SerializationException` — and so every failure has an obvious UI treatment.
 * The mapping from transport exceptions lives in `core:network`, which is the only module that
 * knows what Ktor throws.
 */
sealed class AppError(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** No usable network. Recoverable by the user, and the app still works from cache. */
    class Offline(cause: Throwable? = null) : AppError("No network connection", cause)

    /** The server was reachable but unhappy, or the request timed out. */
    class Server(val statusCode: Int?, message: String, cause: Throwable? = null) : AppError(message, cause)

    /** The response arrived but was not what the contract promised. */
    class Malformed(message: String, cause: Throwable? = null) : AppError(message, cause)

    /** Local persistence failed — disk full, corrupt database. */
    class Storage(message: String, cause: Throwable? = null) : AppError(message, cause)

    /** The input the user supplied could not be used. Always worth showing verbatim. */
    class InvalidInput(message: String, cause: Throwable? = null) : AppError(message, cause)

    /** Anything we did not anticipate. Kept distinct so it can be reported louder in logs. */
    class Unexpected(message: String, cause: Throwable? = null) : AppError(message, cause)
}
