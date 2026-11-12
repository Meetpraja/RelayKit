package com.relaykit.network.internal.http

import com.relaykit.network.request.RelayRequest
import com.relaykit.network.request.TransportResponse

internal interface HttpTransport {
    suspend fun execute(request: RelayRequest): TransportResponse
}
