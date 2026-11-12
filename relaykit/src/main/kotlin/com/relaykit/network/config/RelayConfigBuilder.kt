package com.relaykit.network.config

import java.net.URI
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import com.relaykit.network.event.RelayEventListener
import com.relaykit.network.logging.LogLevel
import com.relaykit.network.logging.LogSink
import com.relaykit.network.internal.logging.LogRedactor

/** Mutable DSL used only while constructing a [RelayConfig]. */
@RelayConfigDsl
public class RelayConfigBuilder {
    /** Required absolute HTTP or HTTPS base URL. */
    public var baseUrl: String = ""
    private var configuredAuth: Auth? = null
    private val configuredHeaders = linkedMapOf<String, String>()
    private val configuredQuery = linkedMapOf<String, String>()
    private var configuredContentType: String = "application/json"
    private var connectTimeout: Duration = 15.seconds
    private var readTimeout: Duration = 30.seconds
    private var writeTimeout: Duration = 30.seconds
    private var callTimeout: Duration = 60.seconds
    private var redirects: Boolean = true
    private var unknownKeys: Boolean = true
    private var emitNulls: Boolean = false
    private var lenient: Boolean = false
    private var coerceValues: Boolean = false
    private var logLevel: LogLevel = LogLevel.NONE
    private var configuredLogSink: LogSink? = null
    private val configuredRedactions = linkedSetOf<String>()
    private var bodyLogLimit: Int = 8 * 1024
    private var messageKeys: List<String> = listOf("message", "error_description", "error")
    private val fallbackMessageMap = linkedMapOf<String, String>()
    private var eventCallback: RelayEventListener? = null
    private var eventCapacity: Int = 64

    /** Configure API key authentication. */
    public fun apiKey(value: String, header: String = "X-API-Key"): Unit {
        configuredAuth = Auth.ApiKey(value, header)
    }

    /** Configure bearer authentication with a dynamic token provider. */
    public fun bearer(tokenProvider: suspend () -> String?): Unit {
        bearerConfigured = true
        configuredAuth = Auth.Bearer(tokenProvider)
    }

    /** Configure bearer authentication using the client's in-memory token. */
    public fun bearer(): Unit {
        bearerConfigured = true
        configuredAuth = Auth.Bearer { token }
    }

    /** Configure custom authorization. */
    public fun customAuth(header: String, value: String): Unit {
        configuredAuth = Auth.Custom(header, value)
    }

    /** Set authentication strategy. */
    public fun auth(value: Auth?): Unit { configuredAuth = value }

    /** Add or replace a default header. */
    public fun header(name: String, value: String): Unit { configuredHeaders[name] = value }

    /** Add or replace a default query parameter. */
    public fun query(name: String, value: String): Unit { configuredQuery[name] = value }

    /** Set the default content type. */
    public fun contentType(value: String): Unit { configuredContentType = value }

    /** Set timeout values. */
    public fun timeouts(block: TimeoutsBuilder.() -> Unit): Unit {
        TimeoutsBuilder().apply(block).also {
            connectTimeout = it.connect
            readTimeout = it.read
            writeTimeout = it.write
            callTimeout = it.call
        }
    }

    /** Set redirect handling. */
    public fun followRedirects(value: Boolean): Unit { redirects = value }

    /** Set JSON decoder options. */
    public fun serialization(
        ignoreUnknownKeys: Boolean = unknownKeys,
        explicitNulls: Boolean = emitNulls,
        isLenient: Boolean = lenient,
        coerceInputValues: Boolean = coerceValues,
    ): Unit {
        unknownKeys = ignoreUnknownKeys
        emitNulls = explicitNulls
        lenient = isLenient
        coerceValues = coerceInputValues
    }

    /** Set a logging level name for later logging configuration. */
    public fun loggingLevel(value: LogLevel): Unit { logLevel = value }

    /** Configure network logging. */
    public fun logging(level: LogLevel, sink: LogSink? = configuredLogSink, maxBodySize: Int = bodyLogLimit): Unit {
        require(maxBodySize > 0) { "maxBodySize must be positive" }
        logLevel = level
        configuredLogSink = sink
        bodyLogLimit = maxBodySize
    }

    /** Add user-defined sensitive key names while retaining mandatory defaults. */
    public fun redact(vararg keys: String): Unit {
        require(keys.all(String::isNotBlank)) { "Redaction keys must not be blank" }
        configuredRedactions += keys
    }

    /** Configure JSON keys to search for a server error message, in priority order. */
    public fun serverMessageKeys(vararg keys: String): Unit {
        require(keys.isNotEmpty() && keys.all(String::isNotBlank)) { "At least one non-blank server message key is required" }
        messageKeys = keys.toList()
    }

    /** Set a fallback message for an error classification. */
    public fun fallbackMessage(type: com.relaykit.network.error.ErrorType, message: String): Unit {
        require(message.isNotBlank()) { "Fallback message must not be blank" }
        fallbackMessageMap[type.name] = message
    }

    /** Configure the client-wide event listener and shared stream buffer size. */
    public fun events(bufferSize: Int = eventCapacity, listener: RelayEventListener? = eventCallback): Unit {
        require(bufferSize > 0) { "Event buffer size must be positive" }
        eventCapacity = bufferSize
        eventCallback = listener
    }

    internal var token: String? = null
    internal var bearerConfigured: Boolean = false

    internal fun build(): RelayConfig {
        val parsed = runCatching { URI(baseUrl) }.getOrNull()
        require(parsed != null && parsed.isAbsolute && parsed.scheme in setOf("http", "https") && !parsed.host.isNullOrBlank()) {
            "baseUrl must be an absolute HTTP or HTTPS URL with a host"
        }
        require(baseUrl.endsWith('/')) { "baseUrl must end with '/'" }
        require(configuredContentType.isNotBlank()) { "contentType must not be blank" }
        return RelayConfig(
            baseUrl, configuredAuth, configuredHeaders.toMap(), configuredQuery.toMap(), configuredContentType,
            Timeouts(connectTimeout, readTimeout, writeTimeout, callTimeout), redirects,
            unknownKeys, emitNulls, lenient, coerceValues, logLevel, configuredLogSink, configuredRedactions.toSet(), bodyLogLimit,
            messageKeys.toList(), fallbackMessageMap.toMap(), eventCallback, eventCapacity,
        )
    }
}

/** DSL scope for timeout values. */
@RelayConfigDsl
public class TimeoutsBuilder {
    /** Connection timeout. */
    public var connect: Duration = 15.seconds
    /** Read timeout. */
    public var read: Duration = 30.seconds
    /** Write timeout. */
    public var write: Duration = 30.seconds
    /** Overall call timeout. */
    public var call: Duration = 60.seconds
}

/** Prevents configuration blocks from accidentally applying to nested DSL receivers. */
@DslMarker
public annotation class RelayConfigDsl
