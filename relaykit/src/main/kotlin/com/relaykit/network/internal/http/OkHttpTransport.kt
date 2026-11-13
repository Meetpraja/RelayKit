package com.relaykit.network.internal.http

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.relaykit.network.RelayClient
import com.relaykit.network.config.Auth
import com.relaykit.network.config.ApiKeyPlacement
import com.relaykit.network.config.RelayConfig
import com.relaykit.network.request.HttpMethod
import com.relaykit.network.request.RelayRequest
import com.relaykit.network.request.TransportResponse
import com.relaykit.network.request.DownloadProgress
import com.relaykit.network.request.DownloadResponse
import com.relaykit.network.request.RelayFile
import android.net.Uri
import android.provider.OpenableColumns
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okio.BufferedSink
import okio.ForwardingSink
import okio.buffer
import okio.source
import java.io.FileNotFoundException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonNull
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import com.relaykit.network.error.RelayInterceptorException
import com.relaykit.network.error.ErrorClassifier
import okhttp3.Interceptor
import kotlinx.serialization.serializer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import retrofit2.Retrofit
import com.relaykit.network.logging.LogLevel
import com.relaykit.network.internal.logging.LogRedactor

internal class OkHttpTransport(
    private val owner: RelayClient,
    private val json: Json,
) : HttpTransport {
    private class RefreshGuard(val client: RelayClient) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<RefreshGuard>
    }
    private val service: RetrofitService by lazy { createService(owner.config, json) }
    private val prettyLogJson: Json by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { Json { prettyPrint = true } }
    private val refreshMutex = Mutex()
    @Volatile private var failedRefreshToken: String? = null
    @Volatile private var refreshGeneration: Long = 0
    @Volatile private var lastRefreshFromToken: String? = null
    @Volatile private var lastRefreshToken: String? = null

    override suspend fun execute(request: RelayRequest): TransportResponse {
        val refreshGuard = kotlin.coroutines.coroutineContext[RefreshGuard]
        check(refreshGuard?.client !== owner) {
            "A bearer refresh callback cannot make requests through the same RelayClient"
        }
        val loggingEnabled = refreshGuard == null
        val config = owner.config
        val url = buildUrl(resolveUrl(config.baseUrl, request.endpoint), config.queryParams + request.query)
        val headers = config.headers + request.headers
        val auth = request.auth ?: config.auth
        val finalHeaders = headers.toMutableMap()
        var finalUrl = url
        var bearerToken: String? = null
        val hasExplicitAuthorization = headers.keys.any { it.equals("Authorization", ignoreCase = true) }
        when (auth) {
            null, Auth.None -> Unit
            is Auth.ApiKey -> if (auth.placement == ApiKeyPlacement.HEADER) finalHeaders[auth.name] = auth.value else finalUrl = buildUrl(finalUrl, mapOf(auth.name to auth.value))
            is Auth.Bearer -> {
                bearerToken = if (owner.hasManagedToken()) owner.token() else owner.resolveBearer(auth.tokenProvider())
                if (!hasExplicitAuthorization) bearerToken?.let { setHeader(finalHeaders, "Authorization", "Bearer $it") }
            }
            is Auth.Custom -> finalHeaders[auth.header] = auth.value
        }
        val jsonRequestText = request.body?.let(::encodeBody)
        val requestBody = when {
            request.files.isNotEmpty() || request.multipart != null -> multipartBody(request)
            request.form != null -> okhttp3.FormBody.Builder().apply { request.form.forEach { (k, v) -> add(k, v) } }.build()
            jsonRequestText != null -> jsonRequestText.toRequestBody(config.contentType.toMediaType())
            else -> null
        }
        val requestRefreshGeneration = refreshGeneration
        val multipartRequestBody = requestBody as? MultipartBody
        var finalAttemptStartedAt = 0L
        var finalAuthRefreshAttempt = false
        var authenticationResponseLogged = false
        suspend fun perform(
            headersForCall: Map<String, String>,
            authenticationRefreshAttempt: Boolean = false,
        ): retrofit2.Response<okhttp3.ResponseBody> {
            val attemptStartedAt = System.nanoTime()
            finalAttemptStartedAt = attemptStartedAt
            finalAuthRefreshAttempt = authenticationRefreshAttempt
            logRequestAttempt(config, request, finalUrl, headersForCall, jsonRequestText, authenticationRefreshAttempt, loggingEnabled)
            val response = if (multipartRequestBody != null) when (request.method) {
                HttpMethod.POST -> service.multipartPostBody(finalUrl, headersForCall, progressBody(multipartRequestBody, request.uploadProgress))
                HttpMethod.PUT -> service.multipartPutBody(finalUrl, headersForCall, progressBody(multipartRequestBody, request.uploadProgress))
                HttpMethod.PATCH -> service.multipartPatchBody(finalUrl, headersForCall, progressBody(multipartRequestBody, request.uploadProgress))
                else -> throw IllegalArgumentException("Multipart requests support POST, PUT, and PATCH")
            }
            else when (request.method) {
            HttpMethod.GET -> service.get(finalUrl, headersForCall)
            HttpMethod.POST -> service.post(finalUrl, headersForCall, requestBody)
            HttpMethod.PUT -> service.put(finalUrl, headersForCall, requestBody)
            HttpMethod.PATCH -> service.patch(finalUrl, headersForCall, requestBody)
            HttpMethod.DELETE -> service.delete(finalUrl, headersForCall)
        }
            return response.also {
                if (it.code() == 401 && bearerToken != null && auth is Auth.Bearer &&
                    !hasExplicitAuthorization && config.bearerTokenRefresher != null && !authenticationRefreshAttempt
                ) {
                    logResponseAttempt(config, request, it.raw(), null, attemptStartedAt, false, "authentication refresh response body omitted", loggingEnabled)
                    authenticationResponseLogged = true
                }
            }
        }

        var result = perform(finalHeaders)
        if (result.code() == 401 && bearerToken != null && auth is Auth.Bearer && !hasExplicitAuthorization &&
            config.bearerTokenRefresher != null && request.files.none { it.uri != null }
        ) {
            val replacement = refreshMutex.withLock {
                // Another 401 may have refreshed while this request waited for the single flight.
                if (owner.token() != null && owner.token() != bearerToken) owner.token()
                else if (refreshGeneration != requestRefreshGeneration && lastRefreshFromToken == bearerToken) lastRefreshToken
                else if (failedRefreshToken == bearerToken) null
                else {
                    try {
                        val refreshed = withContext(RefreshGuard(owner)) {
                            config.bearerTokenRefresher.invoke(bearerToken)
                        }
                        if (refreshed.isNullOrBlank()) {
                            failedRefreshToken = bearerToken
                            lastRefreshFromToken = bearerToken
                            lastRefreshToken = null
                            refreshGeneration++
                            null
                        } else refreshed.also {
                            failedRefreshToken = null
                            lastRefreshFromToken = bearerToken
                            lastRefreshToken = it
                            refreshGeneration++
                            owner.updateRefreshedToken(it)
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        failedRefreshToken = bearerToken
                        lastRefreshFromToken = bearerToken
                        lastRefreshToken = null
                        refreshGeneration++
                        null
                    }
                }
            }
            if (replacement != null) {
                result.errorBody()?.close()
                val retryHeaders = finalHeaders.toMutableMap().also { setHeader(it, "Authorization", "Bearer $replacement") }
                result = perform(retryHeaders, authenticationRefreshAttempt = true)
            }
        }
        val raw = result.raw()
        val responseBody = result.body() ?: result.errorBody()
        val text = responseBody?.use { body ->
            currentCoroutineContext().ensureActive()
            val charset = body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
            body.source().readString(charset)
        }.orEmpty()
        currentCoroutineContext().ensureActive()
        if (!authenticationResponseLogged || finalAuthRefreshAttempt) {
            logResponseAttempt(config, request, raw, text, finalAttemptStartedAt, finalAuthRefreshAttempt, null, loggingEnabled)
        }
        return TransportResponse(
            statusCode = raw.code,
            headers = raw.headers.toMultimap().mapValues { it.value.joinToString(",") },
            body = text,
            isSuccessful = raw.isSuccessful,
        )
    }

    @OptIn(InternalCoroutinesApi::class)
    override suspend fun download(
        request: RelayRequest,
        destination: File,
        resume: Boolean,
        onProgress: ((DownloadProgress) -> Unit)?,
    ): DownloadResponse {
        val context = kotlin.coroutines.coroutineContext
        val refreshGuard = context[RefreshGuard]
        check(refreshGuard?.client !== owner) { "A bearer refresh callback cannot make requests through the same RelayClient" }
        val loggingEnabled = refreshGuard == null
        val config = owner.config
        val url = buildUrl(resolveUrl(config.baseUrl, request.endpoint), config.queryParams + request.query)
        val headers = config.headers + request.headers
        require(headers.keys.none { it.equals("Range", ignoreCase = true) }) {
            "Use resume=true instead of supplying a Range header directly"
        }
        val auth = request.auth ?: config.auth
        val finalHeaders = headers.toMutableMap()
        var finalUrl = url
        var bearerToken: String? = null
        val hasExplicitAuthorization = headers.keys.any { it.equals("Authorization", ignoreCase = true) }
        when (auth) {
            null, Auth.None -> Unit
            is Auth.ApiKey -> if (auth.placement == ApiKeyPlacement.HEADER) finalHeaders[auth.name] = auth.value else finalUrl = buildUrl(finalUrl, mapOf(auth.name to auth.value))
            is Auth.Bearer -> {
                bearerToken = if (owner.hasManagedToken()) owner.token() else owner.resolveBearer(auth.tokenProvider())
                if (!hasExplicitAuthorization) bearerToken?.let { setHeader(finalHeaders, "Authorization", "Bearer $it") }
            }
            is Auth.Custom -> finalHeaders[auth.header] = auth.value
        }

        val parent = destination.absoluteFile.parentFile ?: File(".").absoluteFile
        if (!parent.exists() && !parent.mkdirs()) throw IOException("Unable to create download directory")
        if (destination.exists()) throw IOException("Download destination already exists")
        val target = File(parent, destination.name)
        val partial = File(parent, destination.name + ".part")
        val lock = File(parent, destination.name + ".download.lock")
        if (!lock.createNewFile()) throw IOException("A download to this destination is already in progress")
        var preservePartial = resume
        val attemptStarted = System.nanoTime()
        var response: retrofit2.Response<okhttp3.ResponseBody>? = null
        var activeDownloadCall: retrofit2.Call<okhttp3.ResponseBody>? = null
        var responseLogged = false
        val tokenGenerationAtStart = refreshGeneration
        try {
            if (!resume && partial.exists() && !partial.delete()) throw IOException("Unable to clear previous partial download")
            val initialOffset = if (resume) partial.takeIf(File::exists)?.length() ?: 0L else 0L
            val requestHeaders = finalHeaders.toMutableMap()
            if (resume && initialOffset > 0L) setHeader(requestHeaders, "Range", "bytes=$initialOffset-")
            logRequestAttempt(config, request, finalUrl, requestHeaders, null, false, loggingEnabled)
            val initialResponse = executeDownloadCall(finalUrl, requestHeaders)
            response = initialResponse.first
            activeDownloadCall = initialResponse.second

            if (response.code() == 401 && bearerToken != null && auth is Auth.Bearer && !hasExplicitAuthorization &&
                config.bearerTokenRefresher != null
            ) {
                logResponseAttempt(config, request, response.raw(), null, attemptStarted, false, "download body omitted", loggingEnabled)
                responseLogged = true
                response.errorBody()?.close()
                val replacement = refreshMutex.withLock {
                    if (owner.token() != null && owner.token() != bearerToken) owner.token()
                    else if (refreshGeneration != tokenGenerationAtStart && lastRefreshFromToken == bearerToken) lastRefreshToken
                    else if (failedRefreshToken == bearerToken) null
                    else try {
                        val refreshed = withContext(RefreshGuard(owner)) { config.bearerTokenRefresher.invoke(bearerToken) }
                        if (refreshed.isNullOrBlank()) {
                            failedRefreshToken = bearerToken
                            lastRefreshFromToken = bearerToken
                            lastRefreshToken = null
                            refreshGeneration++
                            null
                        } else refreshed.also {
                            failedRefreshToken = null
                            lastRefreshFromToken = bearerToken
                            lastRefreshToken = it
                            refreshGeneration++
                            owner.updateRefreshedToken(it)
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        failedRefreshToken = bearerToken
                        lastRefreshFromToken = bearerToken
                        lastRefreshToken = null
                        refreshGeneration++
                        null
                    }
                }
                if (replacement != null) {
                    val refreshedHeaders = requestHeaders.toMutableMap().also { setHeader(it, "Authorization", "Bearer $replacement") }
                    logRequestAttempt(config, request, finalUrl, refreshedHeaders, null, true, loggingEnabled)
                    val refreshedResponse = executeDownloadCall(finalUrl, refreshedHeaders)
                    response = refreshedResponse.first
                    activeDownloadCall = refreshedResponse.second
                }
            }

            val raw = response.raw()
            val responseHeaders = raw.headers.toMultimap().mapValues { it.value.joinToString(",") }
            val body = response.body()
            var expectedResponseBytes: Long? = null
            val rangeOffset = if (response.code() == 206) {
                val range = raw.header("Content-Range")?.let(::parseContentRange)
                val rangeTotal = range?.third
                if (range == null || range.first != initialOffset || range.second < range.first ||
                    (rangeTotal != null && range.second != rangeTotal - 1L)
                ) {
                    logDownloadResponse(config, request, raw, attemptStarted, initialOffset, null, loggingEnabled)
                    responseLogged = true
                    body?.close()
                    return DownloadResponse(response.code(), responseHeaders, target, partial, partial.takeIf(File::exists)?.length() ?: 0L,
                        range?.third, false, resume && initialOffset > 0L, "Invalid Content-Range")
                }
                expectedResponseBytes = range.second - range.first + 1L
                range.first to range.third
            } else if (response.code() == 200) {
                if (initialOffset > 0L && partial.exists() && !partial.delete()) throw IOException("Unable to restart ignored-range download")
                expectedResponseBytes = body?.contentLength()?.takeIf { it >= 0L }
                0L to body?.contentLength()?.takeIf { it >= 0L }
            } else if (response.code() == 416) {
                val total = raw.header("Content-Range")?.let(::parseUnsatisfiedRange)
                logDownloadResponse(config, request, raw, attemptStarted, initialOffset, total, loggingEnabled)
                responseLogged = true
                body?.close()
                return DownloadResponse(response.code(), responseHeaders, target, partial, partial.takeIf(File::exists)?.length() ?: 0L,
                    total, false, resume && initialOffset > 0L, "Range not satisfiable")
            } else {
                if (!response.isSuccessful) {
                    logDownloadResponse(config, request, raw, attemptStarted, initialOffset, body?.contentLength()?.takeIf { it >= 0L }, loggingEnabled)
                    responseLogged = true
                    response.errorBody()?.close()
                    if (!resume && partial.exists()) partial.delete()
                    return DownloadResponse(response.code(), responseHeaders, target, partial, partial.takeIf(File::exists)?.length() ?: 0L,
                        null, false, false, "HTTP ${response.code()}")
                }
                expectedResponseBytes = body?.contentLength()?.takeIf { it >= 0L }
                0L to body?.contentLength()?.takeIf { it >= 0L }
            }
            if (!response.isSuccessful) {
                logDownloadResponse(config, request, raw, attemptStarted, initialOffset, rangeOffset.second, loggingEnabled)
                responseLogged = true
                body?.close()
                return DownloadResponse(response.code(), responseHeaders, target, partial, partial.takeIf(File::exists)?.length() ?: 0L,
                    rangeOffset.second, false, resume && initialOffset > 0L, "HTTP ${response.code()}")
            }

            val baseOffset = if (response.code() == 206) rangeOffset.first else 0L
            val totalBytes = rangeOffset.second
            val reporter = DownloadProgressReporter(onProgress)
            try {
                reporter.report(DownloadProgress(baseOffset, totalBytes))
                val call = activeDownloadCall
                val cancellationHandle = context[Job]?.invokeOnCompletion(onCancelling = true) { cause ->
                    if (cause is CancellationException) call?.cancel()
                }
                try {
                    FileOutputStream(partial, response.code() == 206 && baseOffset > 0L).use { output ->
                        var copiedBytes = 0L
                        if (body != null) body.byteStream().use { input ->
                            val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
                            var downloaded = baseOffset
                            var lastReported = baseOffset
                            while (true) {
                                context.ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                output.write(buffer, 0, count)
                                downloaded += count
                                copiedBytes += count
                                if (downloaded - lastReported >= DOWNLOAD_PROGRESS_INTERVAL) {
                                    reporter.report(DownloadProgress(downloaded, totalBytes))
                                    lastReported = downloaded
                                }
                            }
                        }
                        if (expectedResponseBytes != null && copiedBytes != expectedResponseBytes) {
                            throw IOException("Downloaded response length did not match its declared range")
                        }
                        output.fd.sync()
                        context.ensureActive()
                        val written = partial.length()
                        reporter.report(DownloadProgress(written, totalBytes))
                    }
                } catch (cancelled: CancellationException) {
                    call?.cancel()
                    throw cancelled
                } finally {
                    cancellationHandle?.dispose()
                }
            } finally {
                reporter.close()
            }
            val finalLength = partial.takeIf(File::exists)?.length() ?: 0L
            if (target.exists()) throw IOException("Download destination already exists")
            if (!partial.renameTo(target)) throw IOException("Unable to finalize downloaded file")
            preservePartial = false
            val result = DownloadResponse(response.code(), responseHeaders, target, partial, finalLength, totalBytes ?: finalLength,
                true, response.code() == 206 && baseOffset > 0L)
            logDownloadResponse(config, request, raw, attemptStarted, finalLength, result.totalBytes, loggingEnabled)
            responseLogged = true
            return result
        } catch (cancelled: CancellationException) {
            preservePartial = resume
            activeDownloadCall?.cancel()
            throw cancelled
        } finally {
            response?.body()?.close()
            response?.errorBody()?.close()
            if (!preservePartial && partial.exists()) partial.delete()
            lock.delete()
            if (!responseLogged && response != null) {
                logDownloadResponse(config, request, response.raw(), attemptStarted, partial.takeIf(File::exists)?.length() ?: 0L, null, loggingEnabled)
            }
        }
    }

    private fun parseContentRange(value: String): Triple<Long, Long, Long?>? {
        val match = Regex("^bytes (\\d+)-(\\d+)/(\\d+|\\*)$", RegexOption.IGNORE_CASE).matchEntire(value.trim()) ?: return null
        val start = match.groupValues[1].toLongOrNull() ?: return null
        val end = match.groupValues[2].toLongOrNull() ?: return null
        val total = match.groupValues[3].takeUnless { it == "*" }?.toLongOrNull()
        if (total != null && total <= end) return null
        return Triple(start, end, total)
    }

    private fun parseUnsatisfiedRange(value: String): Long? =
        Regex("^bytes \\*/(\\d+)$", RegexOption.IGNORE_CASE).matchEntire(value.trim())
            ?.groupValues?.get(1)?.toLongOrNull()

    @OptIn(InternalCoroutinesApi::class)
    private suspend fun executeDownloadCall(
        url: String,
        headers: Map<String, String>,
    ): Pair<retrofit2.Response<okhttp3.ResponseBody>, retrofit2.Call<okhttp3.ResponseBody>> =
        suspendCancellableCoroutine { continuation ->
            val call = service.downloadCall(url, headers)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : retrofit2.Callback<okhttp3.ResponseBody> {
                override fun onResponse(
                    call: retrofit2.Call<okhttp3.ResponseBody>,
                    response: retrofit2.Response<okhttp3.ResponseBody>,
                ) {
                    val token = continuation.tryResume(response to call)
                    if (token != null) continuation.completeResume(token) else {
                        response.body()?.close()
                        response.errorBody()?.close()
                    }
                }

                override fun onFailure(call: retrofit2.Call<okhttp3.ResponseBody>, error: Throwable) {
                    val token = continuation.tryResumeWithException(error)
                    if (token != null) continuation.completeResume(token)
                }
            })
        }

    private fun logDownloadResponse(
        config: RelayConfig,
        request: RelayRequest,
        response: Response,
        startedAtNanos: Long,
        downloaded: Long,
        total: Long?,
        loggingEnabled: Boolean,
    ) = logSafely(config, loggingEnabled) {
        val elapsed = ((System.nanoTime() - startedAtNanos) / 1_000_000).coerceAtLeast(0)
        val url = redactUrl(response.request.url.toString(), config)
        val line = StringBuilder("DOWNLOAD id=${request.requestId ?: "-"} attempt=${request.retryAttempt} ${response.request.method} $url status=${response.code} duration_ms=$elapsed bytes=$downloaded total=${total ?: "unknown"}")
        if (config.loggingLevel >= LogLevel.HEADERS) {
            line.append("\nHeaders: ${redactHeaders(response.headers.toMultimap().mapValues { it.value.joinToString(",") }, config)}")
        }
        line.toString()
    }

    private class DownloadProgressReporter(private val callback: ((DownloadProgress) -> Unit)?) : AutoCloseable {
        private val executor = callback?.let {
            java.util.concurrent.ThreadPoolExecutor(
                1, 1, 0L, java.util.concurrent.TimeUnit.MILLISECONDS,
                java.util.concurrent.ArrayBlockingQueue(1),
                { task -> Thread(task, "RelayKit-download-progress").apply { isDaemon = true } },
                java.util.concurrent.ThreadPoolExecutor.DiscardOldestPolicy(),
            )
        }

        fun report(progress: DownloadProgress) {
            val target = callback ?: return
            try {
                executor?.execute { try { target(progress) } catch (_: Exception) { /* Observers cannot fail downloads. */ } }
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                // The reporter may already be closed during cancellation.
            }
        }

        override fun close() { executor?.shutdown() }
    }

    private fun setHeader(headers: MutableMap<String, String>, name: String, value: String) {
        headers.keys.filter { it.equals(name, ignoreCase = true) }.forEach(headers::remove)
        headers[name] = value
    }

    private fun createService(config: RelayConfig, json: Json): RetrofitService {
        val clientBuilder = OkHttpClient.Builder()
            .connectTimeout(config.timeouts.connect.inWholeMilliseconds, java.util.concurrent.TimeUnit.MILLISECONDS)
            .readTimeout(config.timeouts.read.inWholeMilliseconds, java.util.concurrent.TimeUnit.MILLISECONDS)
            .writeTimeout(config.timeouts.write.inWholeMilliseconds, java.util.concurrent.TimeUnit.MILLISECONDS)
            .callTimeout(config.timeouts.call.inWholeMilliseconds, java.util.concurrent.TimeUnit.MILLISECONDS)
            .followRedirects(config.followRedirects)
            .followSslRedirects(config.followRedirects)
            // RelayKit owns transient retries at the logical-request layer; avoid invisible retries here.
            .retryOnConnectionFailure(false)
        config.interceptors.forEach { interceptor ->
            clientBuilder.addInterceptor(Interceptor { chain ->
                try {
                    interceptor.intercept(chain)
                } catch (error: java.io.IOException) {
                    throw error
                } catch (error: CancellationException) {
                    throw RelayInterceptorException(error)
                } catch (error: RuntimeException) {
                    throw RelayInterceptorException(error)
                }
            })
        }
        val client = clientBuilder.build()
        return Retrofit.Builder()
            .baseUrl(config.baseUrl)
            .client(client)
            .addConverterFactory(json.asConverterFactory(config.contentType.toMediaType()))
            .build()
            .create(RetrofitService::class.java)
    }

    private fun resolveUrl(base: String, endpoint: String): String =
        if (endpoint.startsWith("http://") || endpoint.startsWith("https://")) endpoint else base + endpoint.removePrefix("/")

    private fun multipartBody(request: RelayRequest): MultipartBody = MultipartBody.Builder().setType(MultipartBody.FORM).apply {
        request.multipart.orEmpty().forEach { (name, value) -> addFormDataPart(name, value) }
        request.files.forEach { addFormDataPart(it.name, it.fileName, fileBody(it)) }
    }.build()

    private fun progressBody(body: RequestBody, callback: ((Long, Long?) -> Unit)?): RequestBody {
        if (callback == null) return body
        return object : RequestBody() {
            override fun contentType() = body.contentType()
            override fun contentLength(): Long = body.contentLength()
            override fun writeTo(sink: BufferedSink) {
                val callbackExecutor = java.util.concurrent.ThreadPoolExecutor(
                    1, 1, 0L, java.util.concurrent.TimeUnit.MILLISECONDS,
                    java.util.concurrent.ArrayBlockingQueue(1),
                    { task -> Thread(task, "RelayKit-upload-progress").apply { isDaemon = true } },
                    java.util.concurrent.ThreadPoolExecutor.DiscardOldestPolicy(),
                )
                var reported = 0L
                var lastReported = 0L
                val total = body.contentLength().takeIf { it >= 0L }
                fun report(bytes: Long, force: Boolean = false) {
                    if (!force && bytes - lastReported < PROGRESS_INTERVAL_BYTES) return
                    lastReported = bytes
                    try {
                        callbackExecutor.execute {
                            try { callback(bytes, total) } catch (_: Exception) { /* Progress is observational only. */ }
                        }
                    } catch (_: java.util.concurrent.RejectedExecutionException) {
                        // Cancellation may have already shut down the callback worker.
                    }
                }
                val countingSink = object : ForwardingSink(sink) {
                    override fun write(source: okio.Buffer, byteCount: Long) {
                        super.write(source, byteCount)
                        reported += byteCount
                        report(reported)
                    }
                }.buffer()
                try {
                    body.writeTo(countingSink)
                    countingSink.flush()
                    report(reported, force = true)
                    callbackExecutor.shutdown()
                } catch (error: Throwable) {
                    callbackExecutor.shutdownNow()
                    throw error
                }
            }
        }
    }

    private fun fileBody(file: RelayFile): RequestBody {
        val mediaType = file.mimeType.toMediaType()
        file.file?.let { return it.asRequestBody(mediaType) }
        val uri = file.uri ?: throw FileNotFoundException("File URI is missing")
        val resolver = owner.applicationContext.contentResolver
        return object : RequestBody() {
            override fun contentType() = mediaType
            override fun writeTo(sink: BufferedSink) {
                val stream = resolver.openInputStream(uri) ?: throw FileNotFoundException("Unable to open content URI")
                stream.source().use { source -> sink.writeAll(source) }
            }
        }
    }

    private fun buildUrl(rawUrl: String, params: Map<String, Any?>): String {
        val parsed = rawUrl.toHttpUrlOrNull() ?: throw IllegalArgumentException("Invalid request URL")
        val builder = parsed.newBuilder()
        params.forEach { (key, value) -> when (value) {
            null -> Unit
            is Iterable<*> -> value.forEach { item -> if (item != null) builder.addQueryParameter(key, item.toString()) }
            is Array<*> -> value.forEach { item -> if (item != null) builder.addQueryParameter(key, item.toString()) }
            else -> builder.setQueryParameter(key, value.toString())
        } }
        return builder.build().toString()
    }

    private fun redactUrl(value: String, config: RelayConfig): String {
        val parsed = value.toHttpUrlOrNull() ?: return "<URL omitted: invalid URL>"
        val builder = parsed.newBuilder()
        if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) {
            builder.username("******").password(if (parsed.password.isEmpty()) "" else "******")
        }
        if (parsed.encodedQuery != null) {
            builder.query(null)
            for (index in 0 until parsed.querySize) {
                val name = parsed.queryParameterName(index)
                val valueAtIndex = parsed.queryParameterValue(index)
                val safeValue = if (LogRedactor.sensitiveQueryParameter(name, config.redactedKeys, config.redactedQueryParameters)) "******" else valueAtIndex
                builder.addQueryParameter(name, safeValue)
            }
        }
        return builder.build().toString()
    }

    private fun redactHeaders(headers: Map<String, String>, config: RelayConfig): Map<String, String> = headers.mapValues { (key, value) ->
        if (LogRedactor.sensitiveHeader(key, config.redactedKeys, config.redactedHeaders)) "******" else value
    }

    private fun redactBody(body: String?, config: RelayConfig): String {
        if (body == null) return "<empty>"
        if (body.length > config.maxLogBodySize) return "<body omitted: exceeds ${config.maxLogBodySize} character limit>"
        val parsed = try {
            json.parseToJsonElement(body)
        } catch (_: Exception) {
            return "<body omitted: invalid JSON>"
        }
        if (parsed !is JsonObject && parsed !is JsonArray) return "<body omitted: JSON has no redactable fields>"
        val sanitized = try {
            val redacted = LogRedactor.redactJson(parsed, config.redactedKeys)
            if (config.prettyPrintJsonBodies) prettyLogJson.encodeToString(JsonElement.serializer(), redacted)
            else json.encodeToString(JsonElement.serializer(), redacted)
        } catch (_: Exception) {
            return "<body omitted: JSON formatting or redaction failed>"
        }
        return if (sanitized.length > config.maxLogBodySize) "<body omitted: exceeds ${config.maxLogBodySize} character limit after redaction>" else sanitized
    }

    private fun logRequestAttempt(
        config: RelayConfig,
        request: RelayRequest,
        url: String,
        headers: Map<String, String>,
        jsonBody: String?,
        authenticationRefreshAttempt: Boolean,
        loggingEnabled: Boolean,
    ) = logSafely(config, loggingEnabled) {
        val line = StringBuilder("REQUEST id=${request.requestId ?: "-"} attempt=${request.retryAttempt} auth_refresh=$authenticationRefreshAttempt ${request.method} ${redactUrl(url, config)}")
        if (config.loggingLevel >= LogLevel.HEADERS) line.append("\nHeaders: ${redactHeaders(headers, config)}")
        if (config.loggingLevel == LogLevel.BODY) {
            val body = when {
                request.files.isNotEmpty() || request.multipart != null -> multipartLogMetadata(request)
                request.form != null -> "<body omitted: form format>"
                else -> redactBody(jsonBody, config)
            }
            line.append("\nBody: ").append(body)
        }
        line.toString()
    }

    private fun multipartLogMetadata(request: RelayRequest): String = buildString {
        append("<multipart: fields=")
        append(request.multipart.orEmpty().keys.joinToString(prefix = "[", postfix = "]"))
        append(", files=[")
        append(request.files.joinToString { file ->
            "name=${file.name}, filename=${file.fileName}, mime=${file.mimeType}, length=${file.file?.takeIf { it.isFile }?.length()?.toString() ?: "unknown"}"
        })
        append("]>")
    }

    private fun logResponseAttempt(
        config: RelayConfig,
        request: RelayRequest,
        response: Response,
        body: String?,
        startedAtNanos: Long,
        authenticationRefreshAttempt: Boolean,
        bodyOmission: String?,
        loggingEnabled: Boolean,
    ) = logSafely(config, loggingEnabled) {
        val elapsed = ((System.nanoTime() - startedAtNanos) / 1_000_000).coerceAtLeast(0)
        val url = redactUrl(response.request.url.toString(), config)
        val line = StringBuilder("RESPONSE id=${request.requestId ?: "-"} attempt=${request.retryAttempt} auth_refresh=$authenticationRefreshAttempt ${response.request.method} $url status=${response.code} duration_ms=$elapsed")
        if (config.loggingLevel >= LogLevel.HEADERS) {
            line.append("\nHeaders: ${redactHeaders(response.headers.toMultimap().mapValues { it.value.joinToString(",") }, config)}")
        }
        if (config.loggingLevel == LogLevel.BODY) {
            line.append("\nBody: ").append(bodyOmission ?: redactBody(body, config))
        }
        line.toString()
    }

    private inline fun logSafely(config: RelayConfig, enabled: Boolean, message: () -> String) {
        if (!enabled || config.loggingLevel == LogLevel.NONE) return
        val sink = config.logSink ?: return
        try {
            sink.log(message())
        } catch (_: Exception) {
            // Logging is best effort and must never change request behavior.
        }
    }

    private companion object {
        const val PROGRESS_INTERVAL_BYTES = 64L * 1024L
        const val DOWNLOAD_BUFFER_SIZE = 32 * 1024
        const val DOWNLOAD_PROGRESS_INTERVAL = 64L * 1024L
    }

    @Suppress("UNCHECKED_CAST")
    private fun encodeBody(value: Any): String = try {
        json.encodeToString(json.serializersModule.serializer(value::class.java), value)
    } catch (_: IllegalArgumentException) {
        json.encodeToString(JsonElement.serializer(), toJsonElement(value))
    }

    private fun toJsonElement(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is JsonElement -> value
        is String -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Map<*, *> -> JsonObject(value.entries.associate { (key, item) ->
            require(key is String) { "JSON object keys must be strings" }
            key to toJsonElement(item)
        })
        is Iterable<*> -> JsonArray(value.map(::toJsonElement))
        is Array<*> -> JsonArray(value.map(::toJsonElement))
        else -> throw IllegalArgumentException("Request body must be a JSON primitive, JsonElement, map, list, or array")
    }
}
