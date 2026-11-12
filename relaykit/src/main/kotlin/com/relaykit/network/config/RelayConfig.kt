package com.relaykit.network.config

import com.relaykit.network.event.RelayEventListener
import com.relaykit.network.logging.LogLevel
import com.relaykit.network.logging.LogSink

/** Immutable configuration snapshot used to create one independent client. */
public data class RelayConfig internal constructor(
    /** Base URL used by this client. */
    public val baseUrl: String,
    /** Authentication strategy used by this client. */
    public val auth: Auth?,
    /** Default request headers. */
    public val headers: Map<String, String>,
    /** Default query parameters. */
    public val queryParams: Map<String, String>,
    /** Default content type. */
    public val contentType: String,
    /** Timeout settings. */
    public val timeouts: Timeouts,
    /** Whether HTTP redirects are followed. */
    public val followRedirects: Boolean,
    /** Kotlinx Serialization behavior flags for future serialization setup. */
    public val ignoreUnknownKeys: Boolean,
    /** Whether serialization emits explicit nulls. */
    public val explicitNulls: Boolean,
    /** Whether lenient JSON parsing is enabled. */
    public val isLenient: Boolean,
    /** Whether input values may be coerced. */
    public val coerceInputValues: Boolean,
    /** Default log-level name, retained without introducing logging behavior in this phase. */
    public val loggingLevel: LogLevel,
    /** Destination for sanitized logs. */
    public val logSink: LogSink?,
    /** Additional secret field names to redact. */
    public val redactedKeys: Set<String>,
    /** Maximum logged body characters. */
    public val maxLogBodySize: Int,
    /** Ordered JSON object keys searched for a server-provided error message. */
    public val serverMessageKeys: List<String>,
    /** Fallback messages indexed by error type name. */
    public val fallbackMessages: Map<String, String>,
    /** Optional process-local event callback. */
    public val eventListener: RelayEventListener?,
    /** Extra SharedFlow capacity. */
    public val eventBufferSize: Int,
)
