package com.relaykit.network.request

import com.relaykit.network.config.Auth

/** DSL builder for requests. */
@RelayRequestDsl
public class RequestBuilder internal constructor(
    private val endpoint: String,
    private val method: HttpMethod,
) {
    internal var requestBody: Any? = null
    internal val requestQuery = linkedMapOf<String, Any?>()
    internal val requestHeaders = linkedMapOf<String, String>()
    internal var requestAuth: Auth? = null
    internal var requestForm: Map<String, String>? = null
    internal var requestMultipart: Map<String, String>? = null
    internal val requestFiles = mutableListOf<RelayFile>()
    internal var requestUploadProgress: ((bytesWritten: Long, totalBytes: Long?) -> Unit)? = null
    internal var requestTag: String? = null
    internal val requestExtras = linkedMapOf<String, Any?>()

    /** Set the JSON request body. */
    public fun body(value: Any?): Unit { requestBody = value }
    /** Add or replace a query parameter. */
    public fun query(name: String, value: Any?): Unit { requestQuery[name] = value }
    /** Add or replace a request header. */
    public fun header(name: String, value: String): Unit { requestHeaders[name] = value }
    /** Override client authentication for this request. */
    public fun auth(value: Auth?): Unit { requestAuth = value }
    /** Set URL-encoded form fields. */
    public fun form(fields: Map<String, String>): Unit { requestForm = fields }
    /** Add multipart text fields. */
    public fun multipart(fields: Map<String, String>): Unit { requestMultipart = fields }
    /** Add a multipart file. */
    public fun file(value: RelayFile): Unit { requestFiles += value }
    /** Observe aggregate request body bytes from the OkHttp background thread. Callback failures are ignored. */
    public fun onUploadProgress(callback: ((bytesWritten: Long, totalBytes: Long?) -> Unit)?): Unit { requestUploadProgress = callback }
    /** Set a request tag for event correlation. */
    public fun tag(value: String): Unit { requestTag = value }
    /** Add request metadata that is never sent to the server. */
    public fun extra(name: String, value: Any?): Unit { requestExtras[name] = value }

    internal fun build(): RelayRequest = RelayRequest(
        endpoint = endpoint,
        method = method,
        body = snapshotRequestValue(requestBody),
        form = requestForm?.toMap(),
        multipart = requestMultipart?.toMap(),
        files = requestFiles.toList(),
        uploadProgress = requestUploadProgress,
        query = requestQuery.mapValues { snapshotRequestValue(it.value) },
        headers = requestHeaders.toMap(),
        auth = requestAuth,
        tag = requestTag,
        extras = requestExtras.mapValues { snapshotRequestValue(it.value) },
    )
}

/**
 * Copy JSON-shaped containers so later caller mutations cannot change an in-flight request.
 * The caller must not mutate a collection concurrently with this traversal; unsynchronized
 * concurrent mutation cannot be snapshotted consistently and may throw during iteration.
 */
internal fun snapshotRequestValue(value: Any?): Any? = when (value) {
    is kotlinx.serialization.json.JsonElement -> value
    is Map<*, *> -> value.entries.associate { (key, item) ->
        require(key is String) { "JSON object keys must be strings" }
        key to snapshotRequestValue(item)
    }
    is List<*> -> value.map(::snapshotRequestValue)
    is Set<*> -> value.map(::snapshotRequestValue)
    is Array<*> -> value.map(::snapshotRequestValue)
    is BooleanArray -> value.copyOf()
    is ByteArray -> value.copyOf()
    is ShortArray -> value.copyOf()
    is IntArray -> value.copyOf()
    is LongArray -> value.copyOf()
    is FloatArray -> value.copyOf()
    is DoubleArray -> value.copyOf()
    is CharArray -> value.copyOf()
    else -> value
}

/** Marker for request DSL scopes. */
@DslMarker
public annotation class RelayRequestDsl
