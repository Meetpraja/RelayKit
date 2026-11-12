package com.relaykit.network

import android.content.Context
import com.relaykit.network.config.RelayConfigBuilder
import java.util.concurrent.atomic.AtomicReference
import com.relaykit.network.event.RelayEvent
import kotlinx.coroutines.flow.SharedFlow

/** Process-wide entry point for configuring RelayKit's default client. */
public object RelayKit {
    private val defaultClient = AtomicReference<RelayClient?>(null)

    /** Initialize or replace the default client using the application's context. */
    @JvmStatic
    public fun init(context: Context, block: RelayConfigBuilder.() -> Unit): RelayClient {
        val next = RelayClient.create(context.applicationContext, block)
        defaultClient.set(next)
        return next
    }

    /** Return the current default client, or throw a clear exception if not initialized. */
    @JvmStatic
    public fun client(): RelayClient = defaultClient.get()
        ?: throw IllegalStateException("RelayKit has not been initialized. Call RelayKit.init(context) first.")

    /** Replace the default client's in-memory bearer token. */
    @JvmStatic
    public fun setToken(token: String?): Unit = client().setToken(token)

    /** Hot stream of lifecycle events from the default client. */
    public val events: SharedFlow<RelayEvent> get() = client().events

    /** Clear the default client. Primarily useful for isolated tests. */
    @JvmStatic
    public fun resetForTesting(): Unit { defaultClient.set(null) }
}
