package com.example.kmd_reader.runtime.webview

import com.example.kmd_reader.data.mock.MockWorks
import com.example.kmd_reader.runtime.ReaderLoadRequest
import com.example.kmd_reader.runtime.ReaderRuntimeEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeMessageCodecTest {
    @Test
    fun encodeLoadScriptIncludesWorkPayload() {
        val work = MockWorks.works.first { it.id == "glass-rail" }

        val command = RuntimeMessageCodec.encodeLoadScript(
            request = ReaderLoadRequest(work = work),
            id = "android-1"
        )

        assertTrue(command.contains("\"type\":\"loadScript\""))
        assertTrue(command.contains("\"id\":\"glass-rail\""))
        assertTrue(command.contains("\"title\":\"玻璃铁轨\""))
    }

    @Test
    fun decodeReadyMessageToRuntimeEvent() {
        val message = RuntimeMessageCodec.decodeInbound(
            """{"version":1,"type":"ready","payload":{"workId":"glass-rail"}}"""
        )

        assertTrue(message is RuntimeInboundMessage.Event)
        val event = (message as RuntimeInboundMessage.Event).event
        assertTrue(event is ReaderRuntimeEvent.Ready)
        assertEquals("glass-rail", (event as ReaderRuntimeEvent.Ready).workId)
    }

    @Test
    fun decodeInspectionIssues() {
        val message = RuntimeMessageCodec.decodeInbound(
            """
            {
              "version": 1,
              "type": "inspectionReported",
              "payload": {
                "workId": "glass-rail",
                "issues": [{
                  "id": "webview-d0-glass-rail",
                  "workId": "glass-rail",
                  "severity": "Info",
                  "source": "Runtime",
                  "location": "webview:d0-shell",
                  "message": "ok",
                  "suggestion": "next"
                }]
              }
            }
            """.trimIndent()
        )

        assertTrue(message is RuntimeInboundMessage.Event)
        val event = (message as RuntimeInboundMessage.Event).event
        assertTrue(event is ReaderRuntimeEvent.InspectionReported)
        val inspection = event as ReaderRuntimeEvent.InspectionReported
        assertEquals("glass-rail", inspection.workId)
        assertEquals("webview-d0-glass-rail", inspection.issues.single().id)
    }
}
