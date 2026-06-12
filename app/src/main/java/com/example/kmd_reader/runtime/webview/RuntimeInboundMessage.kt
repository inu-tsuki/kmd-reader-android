package com.example.kmd_reader.runtime.webview

import com.example.kmd_reader.runtime.ReaderRuntimeEvent

sealed interface RuntimeInboundMessage {
    data class HostReady(
        val event: ReaderRuntimeEvent.TransportReady
    ) : RuntimeInboundMessage

    data class Event(
        val event: ReaderRuntimeEvent
    ) : RuntimeInboundMessage

    data class Unknown(
        val type: String?
    ) : RuntimeInboundMessage

    data class Invalid(
        val message: String
    ) : RuntimeInboundMessage
}
