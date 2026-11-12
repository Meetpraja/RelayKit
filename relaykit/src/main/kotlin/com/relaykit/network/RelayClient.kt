package com.relaykit.network

import android.content.Context
import com.relaykit.network.config.RelayConfig
import com.relaykit.network.config.RelayConfigBuilder
import com.relaykit.network.internal.engine.ExecutionEngine
import com.relaykit.network.internal.http.OkHttpTransport
import com.relaykit.network.request.HttpMethod
import com.relaykit.network.request.RelayRequest
import com.relaykit.network.request.RequestBuilder
import com.relaykit.network.request.TransportResponse
import com.relaykit.network.request.RelayFile
import com.relaykit.network.internal.parse.ResponseParser
import com.relaykit.network.result.RelayResult
import com.relaykit.network.json.RelayJson
import kotlinx.serialization.serializer
import kotlin.reflect.typeOf
import kotlinx.serialization.json.Json
import com.relaykit.network.event.EventHub
import com.relaykit.network.event.RelayEvent
import kotlinx.coroutines.flow.SharedFlow

/** An independently configured RelayKit backend client. */
public class RelayClient internal constructor(
    context: Context,
    /** Immutable configuration for this client. */
    public val config: RelayConfig,
) {
    internal val applicationContext: Context = context.applicationContext
    @Volatile private var currentToken: String? = null
    internal val json: Json by lazy {
        Json {
            ignoreUnknownKeys = config.ignoreUnknownKeys
            explicitNulls = config.explicitNulls
            isLenient = config.isLenient
            coerceInputValues = config.coerceInputValues
        }
    }
    internal val eventHub: EventHub = EventHub(config.eventBufferSize, config.eventListener)
    internal val executionEngine: ExecutionEngine by lazy { ExecutionEngine(config, OkHttpTransport(this, json), eventHub) }

    /** Hot stream of events produced by this client. */
    public val events: SharedFlow<RelayEvent> get() = eventHub.events

    /** Replace the in-memory bearer token used by [com.relaykit.network.config.RelayConfigBuilder.bearer]. */
    public fun setToken(token: String?): Unit { currentToken = token }

    internal fun token(): String? = currentToken

    /** Execute a request and return its status, headers, and raw body snapshot. */
    public suspend fun request(
        endpoint: String,
        method: HttpMethod,
        body: Any? = null,
        query: Map<String, Any?> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
        auth: com.relaykit.network.config.Auth? = null,
        form: Map<String, String>? = null,
        multipart: Map<String, String>? = null,
        files: List<RelayFile> = emptyList(),
    ): TransportResponse = executionEngine.execute(
        RelayRequest(endpoint, method, body, form, multipart, files, query, headers, auth),
    )

    /** Execute a request and deserialize the response as [T]. */
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    public suspend inline fun <reified T : Any> call(
        endpoint: String,
        method: HttpMethod,
        body: Any? = null,
        query: Map<String, Any?> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
        auth: com.relaykit.network.config.Auth? = null,
        form: Map<String, String>? = null,
        multipart: Map<String, String>? = null,
        files: List<RelayFile> = emptyList(),
    ): RelayResult<T> {
        if (T::class == RelayJson::class) {
            @Suppress("UNCHECKED_CAST")
            return callDynamic(endpoint, method, body, query, headers, auth, form, multipart, files) as RelayResult<T>
        }
        return callTyped(endpoint, method, body, query, headers, auth, form, multipart, files, kotlinx.serialization.serializer<T>())
    }

    @PublishedApi
    internal suspend fun callDynamic(
        endpoint: String,
        method: HttpMethod,
        body: Any?,
        query: Map<String, Any?>,
        headers: Map<String, String>,
        auth: com.relaykit.network.config.Auth?,
        form: Map<String, String>?,
        multipart: Map<String, String>?,
        files: List<RelayFile>,
    ): RelayResult<RelayJson> {
        val started = System.nanoTime()
        val id = java.util.UUID.randomUUID().toString()
        return try {
            val response = request(endpoint, method, body, query, headers, auth, form, multipart, files)
            ResponseParser.dynamic(response, id, (System.nanoTime() - started) / 1_000_000, json, config.serverMessageKeys, config.fallbackMessages)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            RelayResult.Failure(
                com.relaykit.network.error.RelayError(classify(error), fallback(error)),
                null, fallback(error), RelayJson.NULL, null, id, (System.nanoTime() - started) / 1_000_000,
            )
        }
    }

    @PublishedApi
    internal suspend fun <T> callTyped(
        endpoint: String,
        method: HttpMethod,
        body: Any?,
        query: Map<String, Any?>,
        headers: Map<String, String>,
        auth: com.relaykit.network.config.Auth?,
        form: Map<String, String>?,
        multipart: Map<String, String>?,
        files: List<RelayFile>,
        serializer: kotlinx.serialization.KSerializer<T>,
    ): RelayResult<T> {
        val start = System.nanoTime()
        val requestId = java.util.UUID.randomUUID().toString()
        return try {
            val response = request(endpoint, method, body, query, headers, auth, form, multipart, files)
            ResponseParser.parse(response, response.body, requestId, (System.nanoTime() - start) / 1_000_000, json, serializer, config.serverMessageKeys, config.fallbackMessages)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val elapsed = (System.nanoTime() - start) / 1_000_000
            RelayResult.Failure(
                com.relaykit.network.error.RelayError(classify(error), fallback(error)),
                null, fallback(error), RelayJson.NULL, null, requestId, elapsed,
            )
        }
    }

    /** Execute a request configured with the request DSL. */
    public suspend fun request(
        endpoint: String,
        method: HttpMethod,
        block: RequestBuilder.() -> Unit,
    ): TransportResponse = executionEngine.execute(RequestBuilder(endpoint, method).apply(block).build())

    public companion object {
        /** Create a client independent from RelayKit's default client. */
        @JvmStatic
        public fun create(context: Context, block: RelayConfigBuilder.() -> Unit): RelayClient {
            val builder = RelayConfigBuilder().apply(block)
            val resolved = builder.build()
            return RelayClient(context.applicationContext, resolved)
        }
    }

    private fun classify(error: Exception): com.relaykit.network.error.ErrorType = when (error) {
        is java.net.SocketTimeoutException -> com.relaykit.network.error.ErrorType.TIMEOUT
        is java.io.IOException -> com.relaykit.network.error.ErrorType.NETWORK
        else -> com.relaykit.network.error.ErrorType.UNKNOWN
    }

    private fun fallback(error: Exception): String = config.fallbackMessages[classify(error).name]
        ?: when (classify(error)) {
            com.relaykit.network.error.ErrorType.TIMEOUT -> "The request timed out"
            com.relaykit.network.error.ErrorType.NETWORK -> "A network error occurred"
            else -> "The request failed"
        }
}
