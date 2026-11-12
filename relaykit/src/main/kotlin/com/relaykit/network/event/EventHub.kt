package com.relaykit.network.event

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/** Bounded event publisher that isolates callback failures. */
public class EventHub(
    bufferSize: Int = 64,
    private val listener: RelayEventListener? = null,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutableEvents = MutableSharedFlow<RelayEvent>(replay = 8, extraBufferCapacity = bufferSize.coerceAtLeast(1), onBufferOverflow = BufferOverflow.DROP_OLDEST)
    /** Hot event stream. */
    public val events: kotlinx.coroutines.flow.SharedFlow<RelayEvent> = mutableEvents.asSharedFlow()

    /** Publish an event without allowing listener failures to escape. */
    public fun emit(event: RelayEvent): Unit {
        mutableEvents.tryEmit(event)
        listener?.let { callback -> scope.launch { runCatching { callback.onEvent(event) } } }
    }
}
