package com.relaykit.network.config

import com.relaykit.network.event.RelayEventListener
import com.relaykit.network.logging.LogLevel
import com.relaykit.network.logging.LogSink
import okhttp3.Interceptor
import kotlinx.serialization.json.Json

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
    /** Whether unknown JSON properties are ignored by the default Json instance. */
    public val ignoreUnknownKeys: Boolean,
    /** Whether null-valued properties are encoded by the default Json instance. */
    public val explicitNulls: Boolean,
    /** Whether lenient JSON parsing is enabled on the default Json instance. */
    public val isLenient: Boolean,
    /** Whether input values may be coerced by the default Json instance. */
    public val coerceInputValues: Boolean,
    /** Whether default-valued properties are encoded by the default Json instance. */
    public val encodeDefaults: Boolean,
    /** Optional caller-supplied immutable Json instance, used instead of the default configuration. */
    public val json: Json?,
    /** Default log-level name, retained without introducing logging behavior in this phase. */
    public val loggingLevel: LogLevel,
    /** Destination for sanitized logs. */
    public val logSink: LogSink?,
    /** Additional secret field names to redact. */
    public val redactedKeys: Set<String>,
    /** Additional header names to redact case-insensitively. */
    public val redactedHeaders: Set<String>,
    /** Additional query parameter names to redact case-insensitively. */
    public val redactedQueryParameters: Set<String>,
    /** Maximum logged body characters. */
    public val maxLogBodySize: Int,
    /** Whether JSON request and response bodies should be pretty printed in logs. */
    public val prettyPrintJsonBodies: Boolean,
    /** Ordered JSON object keys searched for a server-provided error message. */
    public val serverMessageKeys: List<String>,
    /** Fallback messages indexed by error type name. */
    public val fallbackMessages: Map<String, String>,
    /** Optional process-local event callback. */
    public val eventListener: RelayEventListener?,
    /** Extra SharedFlow capacity. */
    public val eventBufferSize: Int,
    /** Ordered OkHttp application interceptors for this client. */
    public val interceptors: List<Interceptor>,
    /** Optional suspendable callback used to refresh an expired bearer token. */
    public val bearerTokenRefresher: (suspend (String?) -> String?)?,
    /** Immutable transient retry behavior for this client. */
    public val retryPolicy: RetryPolicy,
)
