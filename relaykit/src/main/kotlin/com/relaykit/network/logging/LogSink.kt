package com.relaykit.network.logging

/**
 * Destination for formatted, redacted RelayKit log messages. A sink may be called concurrently
 * from requests on different coroutines, so implementations must be thread safe.
 */
public fun interface LogSink {
    /** Receive one already-redacted log message. */
    public fun log(message: String): Unit
}
