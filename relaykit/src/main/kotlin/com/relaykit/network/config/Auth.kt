package com.relaykit.network.config

/** Authentication strategy applied by a RelayKit client. */
public sealed interface Auth {
    /** Sends no authentication credentials. */
    public data object None : Auth

    /** Adds an API key to a request header or query parameter. */
    public data class ApiKey(
        public val value: String,
        public val name: String = "X-API-Key",
        public val placement: ApiKeyPlacement = ApiKeyPlacement.HEADER,
    ) : Auth

    /** Adds a bearer token to the Authorization header. The provider is read for every request and may be invoked concurrently. */
    public data class Bearer(
        public val tokenProvider: suspend () -> String?,
    ) : Auth

    /** Adds a custom authorization header. */
    public data class Custom(
        public val header: String,
        public val value: String,
    ) : Auth
}

/** Where an API key is sent. */
public enum class ApiKeyPlacement {
    /** Send the key as an HTTP header. */
    HEADER,
    /** Send the key as a URL query parameter. */
    QUERY,
}
