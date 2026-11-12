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
)
