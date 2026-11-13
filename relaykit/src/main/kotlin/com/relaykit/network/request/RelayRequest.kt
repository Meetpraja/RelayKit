package com.relaykit.network.request

import com.relaykit.network.config.Auth

/** Immutable input to the internal HTTP execution engine. */
internal data class RelayRequest(
    /** Relative endpoint or absolute URL. */
    public val endpoint: String,
    /** HTTP method. */
    public val method: HttpMethod,
    /** Optional JSON-serializable body. */
    public val body: Any?,
    /** URL-encoded form fields, reserved for the request-body phase. */
    public val form: Map<String, String>? = null,
    /** Multipart text fields, reserved for the request-body phase. */
    public val multipart: Map<String, String>? = null,
    /** File attachments for multipart requests. */
    public val files: List<RelayFile> = emptyList(),
    /** Request query parameters. */
    public val query: Map<String, Any?>,
    /** Request-specific headers. */
    public val headers: Map<String, String>,
    /** Optional authentication override. */
    public val auth: Auth?,
    /** Optional caller tag. */
    public val tag: String? = null,
    /** Arbitrary request metadata, never transmitted. */
    public val extras: Map<String, Any?> = emptyMap(),
    /** Correlation identifier assigned by the execution engine for sanitized diagnostic logs. */
    public val requestId: String? = null,
    /** Transient transport attempt, starting at one and excluding internal auth refresh calls. */
    public val retryAttempt: Int = 1,
    /** Optional aggregate upload callback; invoked on OkHttp's background thread. */
    public val uploadProgress: ((bytesWritten: Long, totalBytes: Long?) -> Unit)? = null,
)
