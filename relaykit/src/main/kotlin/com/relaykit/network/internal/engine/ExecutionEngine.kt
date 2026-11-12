package com.relaykit.network.internal.engine

import com.relaykit.network.config.RelayConfig
import com.relaykit.network.internal.http.HttpTransport
import com.relaykit.network.request.HttpMethod
import com.relaykit.network.request.RelayRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.relaykit.network.request.TransportResponse
import com.relaykit.network.event.EventHub
import com.relaykit.network.event.RelayEvent
import com.relaykit.network.error.ErrorType
import java.util.UUID

internal class ExecutionEngine(
    private val config: RelayConfig,
    private val transport: HttpTransport,
    private val eventHub: EventHub,
) {
    suspend fun execute(request: RelayRequest): TransportResponse {
        validate(request)
        val id = UUID.randomUUID().toString()
        val start = System.nanoTime()
        eventHub.emit(RelayEvent.Started(id, request.endpoint, request.method, request.tag, request.extras))
        return try {
            val response = withContext(Dispatchers.IO) { transport.execute(request) }
            val elapsed = (System.nanoTime() - start) / 1_000_000
            if (response.isSuccessful) eventHub.emit(RelayEvent.Succeeded(id, request.endpoint, request.method, response.statusCode, elapsed, request.tag, request.extras))
            else eventHub.emit(RelayEvent.Failed(id, request.endpoint, request.method, response.statusCode, ErrorType.HTTP, "HTTP ${response.statusCode}", elapsed, request.tag, request.extras))
            response
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val elapsed = (System.nanoTime() - start) / 1_000_000
            eventHub.emit(RelayEvent.Failed(id, request.endpoint, request.method, null, ErrorType.NETWORK, error.message ?: "Network request failed", elapsed, request.tag, request.extras))
            throw error
        }
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
