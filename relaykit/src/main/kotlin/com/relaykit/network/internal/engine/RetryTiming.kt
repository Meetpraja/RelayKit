package com.relaykit.network.internal.engine

import kotlin.random.Random
import kotlin.time.Duration
import kotlinx.coroutines.delay

/** Coroutine-friendly delay and randomness boundary, injectable for deterministic tests. */
internal interface RetryTiming {
    suspend fun wait(duration: Duration)
    fun randomUnit(): Double
}

internal object DefaultRetryTiming : RetryTiming {
    override suspend fun wait(duration: Duration): Unit = delay(duration.inWholeMilliseconds)
    override fun randomUnit(): Double = Random.nextDouble()
}
