package com.relaykit.network.error

import com.relaykit.network.json.RelayJson

/** Structured failure details. */
public data class RelayError(
    /** Failure category. */
    public val type: ErrorType,
    /** Human-readable description. */
    public val message: String,
    /** Optional HTTP response status. */
    public val statusCode: Int? = null,
    /** Parsed response details. */
    public val json: RelayJson = RelayJson.NULL,
) {
    /** Original exception for network, timeout, serialization, or internal failures, when available. */
    public val cause: Throwable? get() = originalCause

    @Volatile
    private var originalCause: Throwable? = null

    /** Attach the source exception without changing the existing data-class constructor or copy API. */
    internal fun withCause(value: Throwable): RelayError {
        originalCause = value
        return this
    }
}
