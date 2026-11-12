package com.relaykit.network.logging

/** Destination for formatted RelayKit log messages. */
public fun interface LogSink {
    /** Receive one already-redacted log message. */
    public fun log(message: String): Unit
}
