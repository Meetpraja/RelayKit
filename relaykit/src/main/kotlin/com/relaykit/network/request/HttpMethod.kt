package com.relaykit.network.request

/** Supported HTTP request methods. */
public enum class HttpMethod {
    /** Retrieve a resource. */
    GET,
    /** Create a resource. */
    POST,
    /** Replace a resource. */
    PUT,
    /** Partially update a resource. */
    PATCH,
    /** Delete a resource. */
    DELETE,
}
