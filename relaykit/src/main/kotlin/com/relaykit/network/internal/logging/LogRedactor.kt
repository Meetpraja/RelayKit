package com.relaykit.network.internal.logging

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Shared case-insensitive redaction rules for headers, URLs, and JSON payloads. */
internal object LogRedactor {
    private val headerDefaults = normalizedSet(
        "Authorization", "Proxy-Authorization", "Cookie", "Set-Cookie", "X-API-Key", "API-Key",
    )
    private val queryDefaults = normalizedSet(
        "token", "access_token", "refresh_token", "api_key", "apikey", "x-api-key",
        "password", "secret", "client_secret",
    )
    private val bodyDefaults = normalizedSet(
        "password", "token", "accessToken", "refreshToken", "access_token", "refresh_token",
        "clientSecret", "client_secret", "secret", "cardNumber", "card_number", "cardSecurityCode",
        "card_security_code", "cardVerificationValue", "card_verification_value", "cvv", "cvv2",
        "cvc", "cvc2", "securityCode", "security_code", "pin",
    )

    fun sensitiveHeader(name: String, shared: Set<String>, custom: Set<String>): Boolean =
        matches(name, headerDefaults, shared, custom)

    fun sensitiveQueryParameter(name: String, shared: Set<String>, custom: Set<String>): Boolean =
        matches(name, queryDefaults, shared, custom)

    fun redactJson(value: JsonElement, custom: Set<String>): JsonElement = redactJsonNormalized(value, normalizedSet(custom))

    private fun redactJsonNormalized(value: JsonElement, normalizedCustom: Set<String>): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.mapValues { (key, child) ->
            if (normalize(key) in bodyDefaults || normalize(key) in normalizedCustom) JsonPrimitive("******") else redactJsonNormalized(child, normalizedCustom)
        })
        is JsonArray -> JsonArray(value.map { redactJsonNormalized(it, normalizedCustom) })
        else -> value
    }

    private fun matches(name: String, defaults: Set<String>, shared: Set<String>, custom: Set<String>): Boolean {
        val normalized = normalize(name)
        return normalized in defaults || normalized in normalizedSet(shared) || normalized in normalizedSet(custom)
    }

    private fun normalizedSet(values: Set<String>): Set<String> = values.mapTo(HashSet(), ::normalize)
    private fun normalizedSet(vararg values: String): Set<String> = values.mapTo(HashSet(), ::normalize)
    private fun normalize(value: String): String = value.lowercase().filter(Char::isLetterOrDigit)
}
