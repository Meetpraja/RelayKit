package com.relaykit.network.request

import java.io.File

/** Progress for a streamed download. [downloadedBytes] includes any bytes already present when resuming. */
public data class DownloadProgress(
    public val downloadedBytes: Long,
    public val totalBytes: Long?,
) {
    /** Percentage in the inclusive range 0..100, or null when the total length is unknown. */
    public val percent: Int? get() = totalBytes?.takeIf { it > 0L }
        ?.let { ((downloadedBytes.coerceAtMost(it).toDouble() / it) * 100.0).toInt() }
}

/** Metadata about a download attempt. A non-success result never represents a completed destination file. */
public data class DownloadResponse(
    public val statusCode: Int,
    public val headers: Map<String, String>,
    public val destination: File,
    public val partialFile: File,
    public val downloadedBytes: Long,
    public val totalBytes: Long?,
    public val isSuccessful: Boolean,
    public val resumed: Boolean,
    public val message: String? = null,
)
