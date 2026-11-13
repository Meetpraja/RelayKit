package com.relaykit.network.internal.engine

import com.relaykit.network.config.RelayConfig
import com.relaykit.network.internal.http.HttpTransport
import com.relaykit.network.request.HttpMethod
import com.relaykit.network.request.RelayRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import com.relaykit.network.request.TransportResponse
import com.relaykit.network.request.DownloadProgress
import com.relaykit.network.request.DownloadResponse
import java.io.File
import com.relaykit.network.event.EventHub
import com.relaykit.network.event.RelayEvent
import com.relaykit.network.error.ErrorType
import com.relaykit.network.error.ErrorClassifier
import java.util.UUID
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

internal class ExecutionEngine(
    private val config: RelayConfig,
    private val transport: HttpTransport,
    private val eventHub: EventHub,
    private val retryTiming: RetryTiming = DefaultRetryTiming,
) {
    suspend fun execute(request: RelayRequest): TransportResponse {
        validate(request)
        val id = UUID.randomUUID().toString()
        val start = System.nanoTime()
        eventHub.emit(RelayEvent.Started(id, request.endpoint, request.method, request.tag, request.extras))
        return try {
            var retriesUsed = 0
            var response: TransportResponse
            while (true) {
                currentCoroutineContext().ensureActive()
                try {
                    val attemptRequest = request.copy(requestId = id, retryAttempt = retriesUsed + 1)
                    response = withContext(Dispatchers.IO) { transport.execute(attemptRequest) }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    ErrorClassifier.cancellation(error)?.let { throw it }
                    currentCoroutineContext().ensureActive()
                    if (!canRetry(request) || !config.retryPolicy.retryNetworkFailures || retriesUsed >= config.retryPolicy.maxRetries ||
                        ErrorClassifier.classify(error) !in setOf(ErrorType.NETWORK, ErrorType.TIMEOUT)
                    ) throw error
                    retriesUsed++
                    retryTiming.wait(config.retryPolicy.delayForRetry(retriesUsed, null, retryTiming.randomUnit()))
                    continue
                }

                if (canRetry(request) && retriesUsed < config.retryPolicy.maxRetries &&
                    response.statusCode != 401 && response.statusCode in config.retryPolicy.retryableStatusCodes
                ) {
                    retriesUsed++
                    val serverDelay = parseRetryAfterMillis(response.header("Retry-After"))
                    retryTiming.wait(config.retryPolicy.delayForRetry(retriesUsed, serverDelay, retryTiming.randomUnit()))
                    continue
                }
                break
            }
            val elapsed = (System.nanoTime() - start) / 1_000_000
            if (response.isSuccessful) eventHub.emit(RelayEvent.Succeeded(id, request.endpoint, request.method, response.statusCode, elapsed, request.tag, request.extras))
            else eventHub.emit(RelayEvent.Failed(id, request.endpoint, request.method, response.statusCode, ErrorType.HTTP, "HTTP ${response.statusCode}", elapsed, request.tag, request.extras))
            response
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            ErrorClassifier.cancellation(error)?.let { throw it }
            // OkHttp may report IOException when its Call is cancelled. Prefer the coroutine's
            // cancellation state so cancellation never becomes a network failure event.
            currentCoroutineContext().ensureActive()
            val elapsed = (System.nanoTime() - start) / 1_000_000
            val type = ErrorClassifier.classify(error)
            val message = config.fallbackMessages[type.name] ?: ErrorClassifier.defaultMessage(type)
            eventHub.emit(RelayEvent.Failed(id, request.endpoint, request.method, null, type, message, elapsed, request.tag, request.extras))
            throw error
        }
    }

    suspend fun executeDownload(
        request: RelayRequest,
        destination: File,
        resume: Boolean,
        onProgress: ((DownloadProgress) -> Unit)?,
    ): DownloadResponse {
        validate(request)
        require(request.method == HttpMethod.GET) { "File downloads currently support GET requests only" }
        val id = UUID.randomUUID().toString()
        val start = System.nanoTime()
        eventHub.emit(RelayEvent.Started(id, request.endpoint, request.method, request.tag, request.extras))
        return try {
            var retriesUsed = 0
            var response: DownloadResponse
            while (true) {
                currentCoroutineContext().ensureActive()
                val attempt = request.copy(requestId = id, retryAttempt = retriesUsed + 1)
                try {
                    response = withContext(Dispatchers.IO) {
                        transport.download(attempt, destination, resume, onProgress)
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    ErrorClassifier.cancellation(error)?.let { throw it }
                    currentCoroutineContext().ensureActive()
                    if (!canRetry(request) || !config.retryPolicy.retryNetworkFailures || retriesUsed >= config.retryPolicy.maxRetries ||
                        ErrorClassifier.classify(error) !in setOf(ErrorType.NETWORK, ErrorType.TIMEOUT)
                    ) throw error
                    retriesUsed++
                    retryTiming.wait(config.retryPolicy.delayForRetry(retriesUsed, null, retryTiming.randomUnit()))
                    continue
                }
                if (canRetry(request) && retriesUsed < config.retryPolicy.maxRetries &&
                    response.statusCode != 401 && response.statusCode in config.retryPolicy.retryableStatusCodes
                ) {
                    retriesUsed++
                    retryTiming.wait(config.retryPolicy.delayForRetry(retriesUsed, parseRetryAfterMillis(response.header("Retry-After")), retryTiming.randomUnit()))
                    continue
                }
                break
            }
            val elapsed = (System.nanoTime() - start) / 1_000_000
            if (response.isSuccessful) {
                eventHub.emit(RelayEvent.Succeeded(id, request.endpoint, request.method, response.statusCode, elapsed, request.tag, request.extras))
            } else {
                eventHub.emit(RelayEvent.Failed(id, request.endpoint, request.method, response.statusCode, ErrorType.HTTP, "HTTP ${response.statusCode}", elapsed, request.tag, request.extras))
            }
            response
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            ErrorClassifier.cancellation(error)?.let { throw it }
            currentCoroutineContext().ensureActive()
            val elapsed = (System.nanoTime() - start) / 1_000_000
            val type = ErrorClassifier.classify(error)
            val message = config.fallbackMessages[type.name] ?: ErrorClassifier.defaultMessage(type)
            eventHub.emit(RelayEvent.Failed(id, request.endpoint, request.method, null, type, message, elapsed, request.tag, request.extras))
            throw error
        }
    }

    private fun canRetry(request: RelayRequest): Boolean {
        val policy = config.retryPolicy
        if (!policy.enabled) return false
        if (request.method != HttpMethod.GET && !policy.retryNonIdempotentRequests) return false
        // Provider-backed content URIs can be one-shot or change between reads; do not replay them.
        if (request.files.any { it.uri != null }) return false
        return true
    }

    private fun TransportResponse.header(name: String): String? = headers.entries
        .firstOrNull { it.key.equals(name, ignoreCase = true) }
        ?.value

    private fun DownloadResponse.header(name: String): String? = headers.entries
        .firstOrNull { it.key.equals(name, ignoreCase = true) }
        ?.value

    private fun parseRetryAfterMillis(value: String?): Long? {
        val header = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val maxMillis = config.retryPolicy.maxDelay.inWholeMilliseconds
        val seconds = header.toLongOrNull()
        if (seconds != null) {
            if (seconds <= 0L) return 0L
            return (seconds.toDouble() * 1_000.0).coerceAtMost(maxMillis.toDouble()).toLong()
        }
        if (header.all(Char::isDigit)) return maxMillis
        // Retry-After HTTP dates use GMT. A fresh formatter is created per call for thread safety.
        val formatter = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("GMT")
            isLenient = false
        }
        val position = ParsePosition(0)
        val date = formatter.parse(header, position)?.takeIf { position.index == header.length } ?: return null
        return (date.time.toDouble() - System.currentTimeMillis().toDouble())
            .coerceIn(0.0, maxMillis.toDouble())
            .toLong()
    }

    private fun validate(request: RelayRequest) {
        require(request.endpoint.isNotBlank()) { "endpoint must not be blank" }
        require(request.method != HttpMethod.GET || request.body == null) { "GET requests cannot include a body" }
        val parsed = runCatching { java.net.URI(request.endpoint) }.getOrNull()
        require(parsed == null || !parsed.isAbsolute || parsed.scheme in setOf("http", "https")) {
            "Absolute endpoint URLs must use HTTP or HTTPS"
        }
        require(config.baseUrl.endsWith('/')) { "baseUrl must end with '/'" }
        val bodyModes = listOf(request.body != null, request.form != null, request.multipart != null || request.files.isNotEmpty()).count { it }
        require(bodyModes <= 1) { "body, form, and multipart are mutually exclusive" }
    }
}
