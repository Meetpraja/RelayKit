package com.relaykit.network.request

/** HTTP response snapshot returned by the low-level client API. */
public data class TransportResponse(
    /** HTTP status code. */
    public val statusCode: Int,
    /** Response headers. */
    public val headers: Map<String, String>,
    /** Response body as text, read once. */
    public val body: String,
    /** Whether the status is in the successful range. */
    public val isSuccessful: Boolean,
)
