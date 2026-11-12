package com.relaykit.network.event

import com.relaykit.network.error.ErrorType
import com.relaykit.network.request.HttpMethod

/** Lifecycle event emitted for a request. */
public sealed interface RelayEvent {
    /** Unique request identifier. */
    public val requestId: String
    /** Endpoint passed by the caller. */
    public val endpoint: String
    /** HTTP method. */
    public val method: HttpMethod
    /** Optional developer-supplied tag. */
    public val tag: String?
    /** Additional caller metadata. */
    public val extras: Map<String, Any?>

    /** Emitted immediately before network execution. */
    public data class Started(override val requestId: String, override val endpoint: String, override val method: HttpMethod, override val tag: String? = null, override val extras: Map<String, Any?> = emptyMap()) : RelayEvent
    /** Emitted when a response completes successfully. */
    public data class Succeeded(override val requestId: String, override val endpoint: String, override val method: HttpMethod, public val statusCode: Int, public val durationMs: Long, override val tag: String? = null, override val extras: Map<String, Any?> = emptyMap()) : RelayEvent
    /** Emitted when execution returns a failure or response error. */
    public data class Failed(override val requestId: String, override val endpoint: String, override val method: HttpMethod, public val statusCode: Int?, public val errorType: ErrorType, public val message: String, public val durationMs: Long, override val tag: String? = null, override val extras: Map<String, Any?> = emptyMap()) : RelayEvent
}
