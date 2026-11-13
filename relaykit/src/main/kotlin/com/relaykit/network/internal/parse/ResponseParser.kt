package com.relaykit.network.internal.parse

import com.relaykit.network.error.ErrorType
import com.relaykit.network.error.RelayError
import com.relaykit.network.json.RelayJson
import com.relaykit.network.request.TransportResponse
import com.relaykit.network.result.RelayResult
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.coroutines.CancellationException

internal object ResponseParser {
    private const val MAX_RETAINED_FAILURE_BODY_CHARS = 16 * 1024

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
        val httpFailure = !response.isSuccessful
        val retainedBody = if (httpFailure) raw.take(MAX_RETAINED_FAILURE_BODY_CHARS) else raw
        val parsed = parseJson(retainedBody, json)
        val wrapped = RelayJson.from(parsed.element ?: JsonNull)
        if (httpFailure) {
            val message = extractMessage(wrapped, serverMessageKeys)
                ?: fallbackMessages[ErrorType.HTTP.name]
                ?: "HTTP ${response.statusCode}"
            return RelayResult.Failure(
                RelayError(ErrorType.HTTP, message, response.statusCode, wrapped),
                response.statusCode, message, wrapped, retainedBody, requestId, duration,
            )
        }
        if (serializer.descriptor.serialName == "com.relaykit.network.json.RelayJson") {
            @Suppress("UNCHECKED_CAST")
            return RelayResult.Success(wrapped as T, response.statusCode, "HTTP ${response.statusCode}", wrapped, raw, requestId, duration)
        }
        return try {
            @Suppress("UNCHECKED_CAST")
            val data: T = if (serializer.descriptor.serialName == "kotlin.Unit") Unit as T
            else if (raw.isBlank()) throw SerializationException("Empty response body")
            else json.decodeFromJsonElement(serializer, parsed.element ?: throw parsed.error ?: SerializationException("Invalid JSON response"))
            RelayResult.Success(data, response.statusCode, "HTTP ${response.statusCode}", wrapped, raw, requestId, duration)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val parsingCause = if (error is SerializationException) error
                else SerializationException("Custom serializer failed to decode the response", error)
            val message = fallbackMessages[ErrorType.PARSING.name] ?: "Unable to parse response body"
            RelayResult.Failure(
                RelayError(ErrorType.PARSING, message, response.statusCode, wrapped).withCause(parsingCause),
                response.statusCode, message, wrapped, raw.take(MAX_RETAINED_FAILURE_BODY_CHARS), requestId, duration,
            )
        }
    }

    fun dynamic(
        response: TransportResponse,
        requestId: String,
        duration: Long,
        json: Json,
        serverMessageKeys: List<String> = listOf("message", "error_description", "error"),
        fallbackMessages: Map<String, String> = emptyMap(),
    ): RelayResult<RelayJson> {
        val httpFailure = !response.isSuccessful
        val retainedBody = if (httpFailure) response.body.take(MAX_RETAINED_FAILURE_BODY_CHARS) else response.body
        val parsed = parseJson(retainedBody, json)
        val wrapped = RelayJson.from(parsed.element ?: JsonNull)
        if (httpFailure) {
            val message = extractMessage(wrapped, serverMessageKeys)
                ?: fallbackMessages[ErrorType.HTTP.name]
                ?: "HTTP ${response.statusCode}"
            return RelayResult.Failure(
                RelayError(ErrorType.HTTP, message, response.statusCode, wrapped),
                response.statusCode, message, wrapped, retainedBody, requestId, duration,
            )
        }
        val parseError = parsed.error
        if (parseError != null) {
            val message = fallbackMessages[ErrorType.PARSING.name] ?: "Unable to parse response body"
            return RelayResult.Failure(
                RelayError(ErrorType.PARSING, message, response.statusCode, RelayJson.NULL).withCause(parseError),
                response.statusCode, message, RelayJson.NULL, response.body.take(MAX_RETAINED_FAILURE_BODY_CHARS), requestId, duration,
            )
        }
        return RelayResult.Success(wrapped, response.statusCode, "HTTP ${response.statusCode}", wrapped, response.body, requestId, duration)
    }

    private data class JsonParseResult(val element: JsonElement?, val error: SerializationException?)

    private fun parseJson(raw: String, json: Json): JsonParseResult = try {
        val text = raw.trim()
        val isValidRootToken = text.startsWith('{') || text.startsWith('[') || text.startsWith('"') ||
            text == "true" || text == "false" || text == "null" ||
            text.firstOrNull() == '-' || text.firstOrNull()?.isDigit() == true
        if (!isValidRootToken) throw SerializationException("Response body is not valid JSON")
        JsonParseResult(json.parseToJsonElement(raw), null)
    } catch (error: SerializationException) {
        JsonParseResult(null, error)
    }

    private fun extractMessage(json: RelayJson, keys: List<String>): String? = keys.firstNotNullOfOrNull { key ->
        json[key].asStringOrNull()?.takeIf(String::isNotBlank)
    }
}
