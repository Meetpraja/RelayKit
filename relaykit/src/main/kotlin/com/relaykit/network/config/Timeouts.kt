package com.relaykit.network.config

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Immutable timeout settings for a client. */
public data class Timeouts(
    /** Connection establishment timeout. */
    public val connect: Duration = 15.seconds,
    /** Response read timeout. */
    public val read: Duration = 30.seconds,
    /** Request write timeout. */
    public val write: Duration = 30.seconds,
    /** Overall call timeout. */
    public val call: Duration = 60.seconds,
)
