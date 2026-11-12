package com.relaykit.network.result

import com.relaykit.network.error.ErrorType
import com.relaykit.network.error.RelayError
import com.relaykit.network.json.RelayJson

/** Structured result of a RelayKit request. */
public sealed interface RelayResult<out T> {
    /** HTTP status, when a response was received. */
    public val statusCode: Int?
    /** Human-readable outcome. */
    public val message: String?
    /** Parsed dynamic JSON response. */
    public val json: RelayJson
    /** Response body text. */
    public val rawBody: String?
    /** Unique identifier for this call. */
    public val requestId: String
    /** Elapsed wall time in milliseconds. */
    public val durationMs: Long
    /** Whether this is a success. */
    public val isSuccess: Boolean get() = this is Success
    /** Whether this is a failure. */
    public val isFailure: Boolean get() = this is Failure
    /** Failure type, if failed. */
    public val errorType: ErrorType? get() = (this as? Failure)?.error?.type
    /** Error, if failed. */
    public val errorOrNull: RelayError? get() = (this as? Failure)?.error
    /** Data, if successful. */
    public val dataOrNull: T? get() = (this as? Success<T>)?.data

    /** Successful HTTP response. */
    public data class Success<T>(
        public val data: T,
        override val statusCode: Int?,
        override val message: String?,
        override val json: RelayJson,
        override val rawBody: String?,
        override val requestId: String,
        override val durationMs: Long,
    ) : RelayResult<T>

    /** Failed HTTP, network, or parsing response. */
    public data class Failure(
        public val error: RelayError,
        override val statusCode: Int?,
        override val message: String?,
        override val json: RelayJson,
        override val rawBody: String?,
        override val requestId: String,
        override val durationMs: Long,
    ) : RelayResult<Nothing>
}

/** Return successful data or throw the contained failure. */
public fun <T> RelayResult<T>.getOrThrow(): T = when (this) {
    is RelayResult.Success -> data
    is RelayResult.Failure -> throw IllegalStateException(error.message)
}

/** Return successful data or a supplied fallback. */
public fun <T> RelayResult<T>.getOrDefault(default: @UnsafeVariance T): T = dataOrNull ?: default

/** Fold success or failure to one value. */
public inline fun <T, R> RelayResult<T>.fold(onSuccess: (T) -> R, onFailure: (RelayError) -> R): R = when (this) {
    is RelayResult.Success -> onSuccess(data)
    is RelayResult.Failure -> onFailure(error)
}

/** Transform success data while preserving failure. */
public inline fun <T, R> RelayResult<T>.map(transform: (T) -> R): RelayResult<R> = when (this) {
    is RelayResult.Success -> RelayResult.Success(transform(data), statusCode, message, json, rawBody, requestId, durationMs)
    is RelayResult.Failure -> RelayResult.Failure(error, statusCode, message, json, rawBody, requestId, durationMs)
}
