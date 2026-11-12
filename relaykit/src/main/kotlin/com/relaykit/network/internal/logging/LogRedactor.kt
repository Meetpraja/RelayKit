package com.relaykit.network.internal.logging

import kotlinx.serialization.json.*

internal object LogRedactor {
    private val defaults = setOf("authorization", "x-api-key", "api-key", "password", "token", "access_token", "refresh_token", "secret")

    fun sensitive(key: String, custom: Set<String>): Boolean = key.lowercase() in defaults || key.lowercase() in custom.map(String::lowercase)

    fun redactJson(value: JsonElement, custom: Set<String>): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.mapValues { (key, child) -> if (sensitive(key, custom)) JsonPrimitive("******") else redactJson(child, custom) })
        is JsonArray -> JsonArray(value.map { redactJson(it, custom) })
        else -> value
    }
}
