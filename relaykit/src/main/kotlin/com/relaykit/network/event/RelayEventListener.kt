package com.relaykit.network.event

/** Callback for request lifecycle events. Listener exceptions are isolated from networking. */
public fun interface RelayEventListener {
    /** Receive one request event. */
    public fun onEvent(event: RelayEvent): Unit
}
