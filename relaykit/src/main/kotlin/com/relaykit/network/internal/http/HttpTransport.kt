package com.relaykit.network.internal.http

import com.relaykit.network.request.RelayRequest
import com.relaykit.network.request.TransportResponse
import com.relaykit.network.request.DownloadProgress
import com.relaykit.network.request.DownloadResponse
import java.io.File

internal interface HttpTransport {
    suspend fun execute(request: RelayRequest): TransportResponse

    suspend fun download(
        request: RelayRequest,
        destination: File,
        resume: Boolean,
        onProgress: ((DownloadProgress) -> Unit)?,
    ): DownloadResponse = throw UnsupportedOperationException("Streaming downloads are not supported by this transport")
}
