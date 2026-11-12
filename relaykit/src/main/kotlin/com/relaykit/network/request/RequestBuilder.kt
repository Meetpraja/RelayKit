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
    /** Set a request tag for event correlation. */
    public fun tag(value: String): Unit { requestTag = value }
    /** Add request metadata that is never sent to the server. */
    public fun extra(name: String, value: Any?): Unit { requestExtras[name] = value }

    internal fun build(): RelayRequest = RelayRequest(
        endpoint = endpoint,
        method = method,
        body = requestBody,
        form = requestForm,
        multipart = requestMultipart,
        files = requestFiles.toList(),
        query = requestQuery.toMap(),
        headers = requestHeaders.toMap(),
        auth = requestAuth,
        tag = requestTag,
        extras = requestExtras.toMap(),
    )
}

/** Marker for request DSL scopes. */
@DslMarker
public annotation class RelayRequestDsl
