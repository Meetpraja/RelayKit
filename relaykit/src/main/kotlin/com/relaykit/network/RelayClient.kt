package com.relaykit.network

import android.content.Context
import com.relaykit.network.config.RelayConfig
import com.relaykit.network.config.RelayConfigBuilder
import com.relaykit.network.internal.engine.ExecutionEngine
import com.relaykit.network.internal.engine.DefaultRetryTiming
import com.relaykit.network.internal.engine.RetryTiming
import com.relaykit.network.internal.http.OkHttpTransport
import com.relaykit.network.request.HttpMethod
import com.relaykit.network.request.RelayRequest
import com.relaykit.network.request.RequestBuilder
import com.relaykit.network.request.TransportResponse
import com.relaykit.network.request.DownloadProgress
import com.relaykit.network.request.DownloadResponse
import com.relaykit.network.request.RelayFile
import com.relaykit.network.request.snapshotRequestValue
import com.relaykit.network.internal.parse.ResponseParser
import com.relaykit.network.result.RelayResult
import com.relaykit.network.json.RelayJson
import kotlinx.serialization.serializer
import kotlin.reflect.typeOf
import kotlinx.serialization.json.Json
import com.relaykit.network.event.EventHub
import com.relaykit.network.event.RelayEvent
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import com.relaykit.network.error.ErrorClassifier

/** An independently configured RelayKit backend client. */
public class RelayClient internal constructor(
    context: Context,
    /** Immutable configuration for this client. */
    public val config: RelayConfig,
    internal val retryTiming: RetryTiming = DefaultRetryTiming,
) {
    internal val applicationContext: Context = context.applicationContext
    @Volatile private var currentToken: String? = null
    @Volatile private var hasManagedToken: Boolean = false
    internal val json: Json by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        config.json ?: Json {
            ignoreUnknownKeys = config.ignoreUnknownKeys
            explicitNulls = config.explicitNulls
            isLenient = config.isLenient
            coerceInputValues = config.coerceInputValues
            encodeDefaults = config.encodeDefaults
        }
    }
    internal val eventHub: EventHub = EventHub(config.eventBufferSize, config.eventListener)
    internal val executionEngine: ExecutionEngine by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { ExecutionEngine(config, OkHttpTransport(this, json), eventHub, retryTiming) }

    /** Hot stream of events produced by this client. */
    public val events: SharedFlow<RelayEvent> get() = eventHub.events

    /** Replace the in-memory bearer token used by [com.relaykit.network.config.RelayConfigBuilder.bearer]. */
    public fun setToken(token: String?): Unit { currentToken = token; hasManagedToken = true }

    internal fun token(): String? = currentToken

    internal fun hasManagedToken(): Boolean = hasManagedToken

    internal fun resolveBearer(providerToken: String?): String? = if (hasManagedToken) currentToken else providerToken ?: currentToken

    internal fun updateRefreshedToken(token: String): Unit { currentToken = token; hasManagedToken = true }

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
    ): TransportResponse = executeRequest(endpoint, method, body, query, headers, auth, form, multipart, files, null)

    /** Execute a raw request with optional aggregate multipart upload progress. */
    public suspend fun request(
        endpoint: String,
        method: HttpMethod,
        onUploadProgress: (bytesWritten: Long, totalBytes: Long?) -> Unit,
        body: Any? = null,
        query: Map<String, Any?> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
        auth: com.relaykit.network.config.Auth? = null,
        form: Map<String, String>? = null,
        multipart: Map<String, String>? = null,
        files: List<RelayFile> = emptyList(),
    ): TransportResponse = executeRequest(endpoint, method, body, query, headers, auth, form, multipart, files, onUploadProgress)

    @PublishedApi
    internal suspend fun executeRequest(
        endpoint: String,
        method: HttpMethod,
        body: Any?,
        query: Map<String, Any?>,
        headers: Map<String, String>,
        auth: com.relaykit.network.config.Auth?,
        form: Map<String, String>?,
        multipart: Map<String, String>?,
        files: List<RelayFile>,
        onUploadProgress: ((bytesWritten: Long, totalBytes: Long?) -> Unit)?,
    ): TransportResponse = executionEngine.execute(
        RelayRequest(endpoint, method, snapshotRequestValue(body), form?.toMap(), multipart?.toMap(), files.toList(), query.mapValues { snapshotRequestValue(it.value) }, headers.toMap(), auth, uploadProgress = onUploadProgress),
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
            return callDynamic(endpoint, method, body, query, headers, auth, form, multipart, files, null) as RelayResult<T>
        }
        return callTyped(endpoint, method, body, query, headers, auth, form, multipart, files, kotlinx.serialization.serializer<T>(), null)
    }

    /** Execute and deserialize a request with optional aggregate multipart upload progress. */
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    public suspend inline fun <reified T : Any> call(
        endpoint: String,
        method: HttpMethod,
        noinline onUploadProgress: (bytesWritten: Long, totalBytes: Long?) -> Unit,
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
            return callDynamic(endpoint, method, body, query, headers, auth, form, multipart, files, onUploadProgress) as RelayResult<T>
        }
        return callTyped(endpoint, method, body, query, headers, auth, form, multipart, files, kotlinx.serialization.serializer<T>(), onUploadProgress)
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
        onUploadProgress: ((bytesWritten: Long, totalBytes: Long?) -> Unit)?,
    ): RelayResult<RelayJson> {
        val started = System.nanoTime()
        val id = java.util.UUID.randomUUID().toString()
        return try {
            val response = executeRequest(endpoint, method, body, query, headers, auth, form, multipart, files, onUploadProgress)
            val result = ResponseParser.dynamic(response, id, (System.nanoTime() - started) / 1_000_000, json, config.serverMessageKeys, config.fallbackMessages)
            currentCoroutineContext().ensureActive()
            result
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            ErrorClassifier.cancellation(error)?.let { throw it }
            currentCoroutineContext().ensureActive()
            RelayResult.Failure(
                com.relaykit.network.error.RelayError(classify(error), fallback(error)).withCause(ErrorClassifier.originalCause(error)),
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
        onUploadProgress: ((bytesWritten: Long, totalBytes: Long?) -> Unit)? = null,
    ): RelayResult<T> {
        val start = System.nanoTime()
        val requestId = java.util.UUID.randomUUID().toString()
        return try {
            val response = executeRequest(endpoint, method, body, query, headers, auth, form, multipart, files, onUploadProgress)
            val result = ResponseParser.parse(response, response.body, requestId, (System.nanoTime() - start) / 1_000_000, json, serializer, config.serverMessageKeys, config.fallbackMessages)
            currentCoroutineContext().ensureActive()
            result
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            ErrorClassifier.cancellation(error)?.let { throw it }
            currentCoroutineContext().ensureActive()
            val elapsed = (System.nanoTime() - start) / 1_000_000
            RelayResult.Failure(
                com.relaykit.network.error.RelayError(classify(error), fallback(error)).withCause(ErrorClassifier.originalCause(error)),
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

    /** Stream a GET response to [destination] without buffering its contents in memory. */
    public suspend fun download(
        endpoint: String,
        destination: java.io.File,
        method: HttpMethod = HttpMethod.GET,
        query: Map<String, Any?> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
        auth: com.relaykit.network.config.Auth? = null,
        resume: Boolean = false,
        onProgress: ((DownloadProgress) -> Unit)? = null,
    ): DownloadResponse {
        require(method == HttpMethod.GET) { "File downloads currently support GET requests only" }
        val request = RelayRequest(
            endpoint = endpoint,
            method = method,
            body = null,
            query = query.mapValues { snapshotRequestValue(it.value) },
            headers = headers.toMap(),
            auth = auth,
        )
        return executionEngine.executeDownload(request, destination, resume, onProgress)
    }

    public companion object {
        /** Create a client independent from RelayKit's default client. */
        @JvmStatic
        public fun create(context: Context, block: RelayConfigBuilder.() -> Unit): RelayClient {
            val builder = RelayConfigBuilder().apply(block)
            val resolved = builder.build()
            return RelayClient(context.applicationContext, resolved)
        }
    }

    private fun classify(error: Exception): com.relaykit.network.error.ErrorType = ErrorClassifier.classify(error)

    private fun fallback(error: Exception): String = config.fallbackMessages[classify(error).name]
        ?: ErrorClassifier.defaultMessage(classify(error))
}
