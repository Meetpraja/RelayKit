package com.relaykit.network.json

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Safe wrapper for dynamic JSON values. Missing or incompatible lookups return [NULL]. */
public class RelayJson internal constructor(internal val element: JsonElement) {
    /** Whether this value is JSON null. */
    public val isNull: Boolean get() = element is JsonNull
    /** Object keys, or an empty set for non-objects. */
    public val keys: Set<String> get() = (element as? JsonObject)?.keys.orEmpty()
    /** Number of object fields or array items, otherwise zero. */
    public val size: Int get() = when (element) { is JsonObject -> element.size; is JsonArray -> element.size; else -> 0 }

    /** Safely access an object member or array index. */
    public operator fun get(key: String): RelayJson = RelayJson((element as? JsonObject)?.get(key) ?: JsonNull)
    /** Safely access an array index. */
    public operator fun get(index: Int): RelayJson = RelayJson((element as? JsonArray)?.getOrNull(index) ?: JsonNull)
    /** Whether an object has a key. */
    public fun has(key: String): Boolean = (element as? JsonObject)?.containsKey(key) == true
    /** Convert this node to a string, throwing if its type is incompatible. */
    public fun asString(): String = asStringOrNull() ?: throw IllegalStateException("JSON value is not a string")
    /** Convert this node to a string when possible. */
    public fun asStringOrNull(): String? = (element as? JsonPrimitive)?.takeIf { it.isString }?.content
    /** Convert this node to an integer, throwing when incompatible. */
    public fun asInt(): Int = asIntOrNull() ?: throw IllegalStateException("JSON value is not an integer")
    /** Convert this node to an integer when possible. */
    public fun asIntOrNull(): Int? = (element as? JsonPrimitive)?.content?.toIntOrNull()
    /** Convert this node to a long, throwing when incompatible. */
    public fun asLong(): Long = asLongOrNull() ?: throw IllegalStateException("JSON value is not a long")
    /** Convert this node to a long when possible. */
    public fun asLongOrNull(): Long? = (element as? JsonPrimitive)?.content?.toLongOrNull()
    /** Convert this node to a double, throwing when incompatible. */
    public fun asDouble(): Double = asDoubleOrNull() ?: throw IllegalStateException("JSON value is not a number")
    /** Convert this node to a double when possible. */
    public fun asDoubleOrNull(): Double? = (element as? JsonPrimitive)?.content?.toDoubleOrNull()
    /** Convert this node to a boolean, throwing when incompatible. */
    public fun asBoolean(): Boolean = asBooleanOrNull() ?: throw IllegalStateException("JSON value is not a boolean")
    /** Convert this node to a boolean when possible. */
    public fun asBooleanOrNull(): Boolean? = (element as? JsonPrimitive)?.content?.toBooleanStrictOrNull()
    /** Convert array entries to wrappers. */
    public fun toList(): List<RelayJson> = (element as? JsonArray)?.map(::RelayJson).orEmpty()
    /** Convert object values to wrappers. */
    public fun toMap(): Map<String, RelayJson> = (element as? JsonObject)?.mapValues { RelayJson(it.value) }.orEmpty()
    /** Render JSON in compact form. */
    public fun toStringValue(): String = element.toString()
    /** Render JSON in pretty form. */
    public fun toPrettyString(): String = Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), element)

    public companion object {
        /** Null-valued safe node. */
        @JvmField public val NULL: RelayJson = RelayJson(JsonNull)
        /** Parse dynamic JSON text. */
        @JvmStatic public fun parse(text: String, json: Json = Json): RelayJson = RelayJson(json.parseToJsonElement(text))
        /** Create from a JSON element. */
        @JvmStatic public fun from(element: JsonElement): RelayJson = RelayJson(element)
    }
}
