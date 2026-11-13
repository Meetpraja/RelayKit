package com.relaykit.network.config

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Immutable retry rules for transient HTTP and network failures. */
public data class RetryPolicy(
    /** Whether automatic transient retries are enabled. Disabled by default for compatibility. */
    public val enabled: Boolean = false,
    /** Retries after the initial attempt; this is not the total attempt count. */
    public val maxRetries: Int = 2,
    /** Delay before the first retry. */
    public val initialDelay: Duration = 250.milliseconds,
    /** Exponential backoff multiplier applied after each retry. */
    public val backoffMultiplier: Double = 2.0,
    /** Hard cap for computed delays and server Retry-After values. */
    public val maxDelay: Duration = 2.seconds,
    /** HTTP status codes eligible for retry. HTTP 401 is never eligible. */
    public val retryableStatusCodes: Set<Int> = DEFAULT_RETRYABLE_STATUS_CODES,
    /** Whether network IO failures and timeouts are eligible for retry. */
    public val retryNetworkFailures: Boolean = true,
    /** Symmetric random jitter fraction in [0, 1], where 0.2 means ±20 percent. */
    public val jitterFactor: Double = 0.2,
    /** Explicitly permit retrying PUT, POST, PATCH, and DELETE requests. */
    public val retryNonIdempotentRequests: Boolean = false,
) {
    init {
        require(maxRetries >= 0) { "maxRetries must be non-negative" }
        require(initialDelay >= Duration.ZERO && initialDelay.isFinite()) { "initialDelay must be finite and non-negative" }
        require(maxDelay >= Duration.ZERO && maxDelay.isFinite()) { "maxDelay must be finite and non-negative" }
        require(backoffMultiplier.isFinite() && backoffMultiplier >= 1.0) { "backoffMultiplier must be finite and at least 1" }
        require(jitterFactor.isFinite() && jitterFactor in 0.0..1.0) { "jitterFactor must be between 0 and 1" }
        require(retryableStatusCodes.all { it in 100..599 }) { "Retryable HTTP status codes must be between 100 and 599" }
        require(401 !in retryableStatusCodes) { "HTTP 401 is handled by bearer refresh and cannot be a transient retry status" }
    }

    internal fun delayForRetry(retryNumber: Int, retryAfterMillis: Long?, randomValue: Double): Duration {
        val capMillis = maxDelay.inWholeMilliseconds
        if (retryAfterMillis != null) return retryAfterMillis.coerceAtLeast(0).coerceAtMost(capMillis).milliseconds
        if (capMillis == 0L) return Duration.ZERO
        var delayMillis = initialDelay.inWholeMilliseconds.toDouble()
        val multiplier = backoffMultiplier
        repeat((retryNumber - 1).coerceAtLeast(0)) {
            delayMillis = if (delayMillis >= capMillis.toDouble() / multiplier) capMillis.toDouble()
            else delayMillis * multiplier
        }
        delayMillis = delayMillis.coerceAtMost(capMillis.toDouble())
        if (jitterFactor > 0.0) {
            val boundedRandom = randomValue.coerceIn(0.0, 1.0)
            delayMillis *= 1.0 + ((boundedRandom * 2.0) - 1.0) * jitterFactor
        }
        return delayMillis.coerceIn(0.0, capMillis.toDouble()).toLong().milliseconds
    }

    public companion object {
        /** Transient response codes commonly safe to retry when the request itself is replayable. */
        public val DEFAULT_RETRYABLE_STATUS_CODES: Set<Int> = setOf(408, 429, 500, 502, 503, 504)
    }
}

/** Mutable DSL scope used to construct an immutable [RetryPolicy]. */
@RelayConfigDsl
public class RetryPolicyBuilder {
    public var enabled: Boolean = false
    public var maxRetries: Int = 2
    public var initialDelay: Duration = 250.milliseconds
    public var backoffMultiplier: Double = 2.0
    public var maxDelay: Duration = 2.seconds
    public var retryableStatusCodes: Set<Int> = RetryPolicy.DEFAULT_RETRYABLE_STATUS_CODES
    public var retryNetworkFailures: Boolean = true
    public var jitterFactor: Double = 0.2
    public var retryNonIdempotentRequests: Boolean = false

    /** Replace the set of HTTP response codes eligible for retry. 401 is reserved for auth refresh. */
    public fun retryOnStatusCodes(vararg codes: Int): Unit { retryableStatusCodes = codes.toSet() }

    internal fun build(): RetryPolicy = RetryPolicy(
        enabled = enabled,
        maxRetries = maxRetries,
        initialDelay = initialDelay,
        backoffMultiplier = backoffMultiplier,
        maxDelay = maxDelay,
        retryableStatusCodes = java.util.Collections.unmodifiableSet(LinkedHashSet(retryableStatusCodes)),
        retryNetworkFailures = retryNetworkFailures,
        jitterFactor = jitterFactor,
        retryNonIdempotentRequests = retryNonIdempotentRequests,
    )
}
