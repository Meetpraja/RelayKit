package com.relaykit.network.internal.parse

import com.relaykit.network.error.ErrorType
import com.relaykit.network.error.RelayError
import com.relaykit.network.json.RelayJson
import com.relaykit.network.request.TransportResponse
import com.relaykit.network.result.RelayResult
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull

internal object ResponseParser {
    fun <T> parse(
        response: TransportResponse,
        raw: String,
        requestId: String,
        duration: Long,
        json: Json,
        serializer: KSerializer<T>,
        serverMessageKeys: List<String> = listOf("message", "error_description", "error"),
        fallbackMessages: Map<String, String> = emptyMap(),
    ): RelayResult<T> {
        val tree = raw.takeIf(String::isNotBlank)?.let { runCatching { json.parseToJsonElement(it) }.getOrNull() } ?: JsonNull
        val wrapped = RelayJson.from(tree)
        val success = response.isSuccessful
        if (!success) {
            val message = extractMessage(wrapped, serverMessageKeys)
                ?: fallbackMessages[ErrorType.HTTP.name]
                ?: "HTTP ${response.statusCode}"
            return RelayResult.Failure(RelayError(ErrorType.HTTP, message, response.statusCode, wrapped), response.statusCode, message, wrapped, raw, requestId, duration)
        }
        if (serializer.descriptor.serialName == "com.relaykit.network.json.RelayJson") {
            @Suppress("UNCHECKED_CAST")
            return RelayResult.Success(wrapped as T, response.statusCode, "HTTP ${response.statusCode}", wrapped, raw, requestId, duration)
        }
        return try {
            @Suppress("UNCHECKED_CAST")
            val data: T = if (serializer.descriptor.serialName == "kotlin.Unit") Unit as T
            else if (raw.isBlank()) throw SerializationException("Empty response body")
            else json.decodeFromString(serializer, raw)
            RelayResult.Success(data, response.statusCode, "HTTP ${response.statusCode}", wrapped, raw, requestId, duration)
        } catch (error: SerializationException) {
            val message = fallbackMessages[ErrorType.PARSING.name] ?: "Unable to parse response body"
            RelayResult.Failure(RelayError(ErrorType.PARSING, message, response.statusCode, wrapped), response.statusCode, message, wrapped, raw, requestId, duration)
        }
    }

    fun dynamic(response: TransportResponse, requestId: String, duration: Long, json: Json, serverMessageKeys: List<String> = listOf("message", "error_description", "error"), fallbackMessages: Map<String, String> = emptyMap()): RelayResult<RelayJson> {
        val raw = response.body
        val tree = raw.takeIf(String::isNotBlank)?.let { runCatching { json.parseToJsonElement(it) }.getOrNull() } ?: JsonNull
        val wrapped = RelayJson.from(tree)
        if (!response.isSuccessful) {
            val message = extractMessage(wrapped, serverMessageKeys) ?: fallbackMessages[ErrorType.HTTP.name] ?: "HTTP ${response.statusCode}"
            return RelayResult.Failure(RelayError(ErrorType.HTTP, message, response.statusCode, wrapped), response.statusCode, message, wrapped, raw, requestId, duration)
        }
        return RelayResult.Success(wrapped, response.statusCode, "HTTP ${response.statusCode}", wrapped, raw, requestId, duration)
    }

    private fun extractMessage(json: RelayJson, keys: List<String>): String? = keys.firstNotNullOfOrNull { key ->
        json[key].asStringOrNull()?.takeIf(String::isNotBlank)
    }
}
