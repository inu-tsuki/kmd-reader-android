package com.example.kmd_reader.runtime.webview

import com.example.kmd_reader.data.mock.MockWorks
import com.example.kmd_reader.runtime.ReaderLoadRequest
import com.example.kmd_reader.runtime.ReaderRuntimeEvent
import com.example.kmd_reader.runtime.ReaderRuntimeViewport
import com.example.kmd_reader.runtime.ReaderSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeMessageCodecTest {
    @Test
    fun encodeLoadScriptIncludesProtocolEnvelopeAndSourcePayload() {
        val work = MockWorks.works.first { it.id == "glass-rail" }

        val command = RuntimeMessageCodec.encodeLoadScript(
            request = ReaderLoadRequest(
                work = work,
                source = "玻璃铁轨\n\n真实 runtime smoke source",
                settings = ReaderSettings(
                    assetBaseUrl = "./",
                    presentationMode = "stage",
                    viewport = ReaderRuntimeViewport(width = 960, height = 540)
                )
            ),
            id = "android-1"
        )

        assertTrue(command.contains("\"version\":1"))
        assertTrue(command.contains("\"id\":\"android-1\""))
        assertTrue(command.contains("\"type\":\"loadScript\""))
        assertTrue(command.contains("\"id\":\"glass-rail\""))
        assertTrue(command.contains("\"title\":\"玻璃铁轨\""))
        assertTrue(command.contains("\"source\":\"玻璃铁轨\\n\\n真实 runtime smoke source\""))
        assertTrue(command.contains("\"assetBaseUrl\":\"./\""))
        assertTrue(command.contains("\"presentationMode\":\"stage\""))
        assertTrue(command.contains("\"viewport\":{\"width\":960,\"height\":540}"))
    }

    @Test
    fun encodeLoadScriptDoesNotInventPreviewSource() {
        val work = MockWorks.works.first { it.id == "glass-rail" }

        val command = RuntimeMessageCodec.encodeLoadScript(
            request = ReaderLoadRequest(work = work),
            id = "android-1"
        )

        assertTrue(command.contains("\"work\":"))
        assertTrue(!command.contains("\"source\":"))
    }

    @Test
    fun decodeRuntimeReadyKeepsCapabilities() {
        val message = RuntimeMessageCodec.decodeInbound(
            """
            {
              "version": 1,
              "sessionId": "runtime-session-1",
              "type": "runtimeReady",
              "payload": {
                "runtime": "kmd-reader-runtime-web",
                "version": 1,
                "capabilities": {
                  "protocolVersion": 1,
                  "supportsSourceText": true,
                  "supportsSourceUrl": true,
                  "supportsAssetManifest": true,
                  "supportsSeekTime": true,
                  "supportsTimelineMarkers": true,
                  "supportsInspection": true,
                  "supportsInteractiveSegments": false
                }
              }
            }
            """.trimIndent()
        )

        assertTrue(message is RuntimeInboundMessage.HostReady)
        val ready = (message as RuntimeInboundMessage.HostReady).event
        assertEquals("kmd-reader-runtime-web", ready.runtime)
        assertEquals("runtime-session-1", ready.sessionId)
        assertEquals(true, ready.capabilities?.supportsSourceText)
        assertEquals(true, ready.capabilities?.supportsInspection)
    }

    @Test
    fun decodeReadyMessageToRuntimeEvent() {
        val message = RuntimeMessageCodec.decodeInbound(
            """
            {
              "version": 1,
              "id": "runtime-1",
              "type": "ready",
              "payload": {
                "workId": "glass-rail",
                "durationMs": 1200,
                "timelineMarkers": [{
                  "id": "p0-m0",
                  "label": "After",
                  "timeMs": 240,
                  "startTime": 240,
                  "duration": 580,
                  "progress": 0.2,
                  "segmentId": "seg-0",
                  "paragraphIndex": 0,
                  "line": 7,
                  "content": "After",
                  "type": "text"
                }]
              }
            }
            """.trimIndent()
        )

        assertTrue(message is RuntimeInboundMessage.Event)
        val event = (message as RuntimeInboundMessage.Event).event
        assertTrue(event is ReaderRuntimeEvent.Ready)
        val ready = event as ReaderRuntimeEvent.Ready
        assertEquals("glass-rail", ready.workId)
        assertEquals(1200L, ready.durationMs)
        val marker = ready.timelineMarkers.single()
        assertEquals("p0-m0", marker.id)
        assertEquals("After", marker.label)
        assertEquals(240L, marker.timeMs)
        assertEquals(580L, marker.durationMs)
        assertEquals(0.2f, marker.progress ?: 0f, 0.001f)
        assertEquals("seg-0", marker.segmentId)
        assertEquals(0, marker.paragraphIndex)
        assertEquals(7, marker.line)
        assertEquals("After", marker.content)
        assertEquals("text", marker.type)
    }

    @Test
    fun decodeProgressMessageKeepsPlaybackLineAndMarker() {
        val message = RuntimeMessageCodec.decodeInbound(
            """
            {
              "version": 1,
              "sessionId": "runtime-session-2",
              "type": "progressChanged",
              "payload": {
                "workId": "glass-rail",
                "progress": 0.36,
                "timeMs": 840,
                "durationMs": 2400,
                "segmentId": "seg-0",
                "paragraphIndex": 1,
                "line": 9,
                "checkpointId": "cp-9",
                "markerId": "p1-m2",
                "positionPayload": "segment:0:line:9"
              }
            }
            """.trimIndent()
        )

        assertTrue(message is RuntimeInboundMessage.Event)
        val event = (message as RuntimeInboundMessage.Event).event
        assertTrue(event is ReaderRuntimeEvent.ProgressChanged)
        val progress = event as ReaderRuntimeEvent.ProgressChanged
        assertEquals("glass-rail", progress.workId)
        assertEquals(0.36f, progress.progress, 0.001f)
        assertEquals(840L, progress.timeMs)
        assertEquals(2400L, progress.durationMs)
        assertEquals("seg-0", progress.segmentId)
        assertEquals(1, progress.paragraphIndex)
        assertEquals(9, progress.line)
        assertEquals("cp-9", progress.checkpointId)
        assertEquals("p1-m2", progress.markerId)
        assertEquals("runtime-session-2", progress.sessionId)
    }

    @Test
    fun decodePlaybackStateFromRealRuntimePayload() {
        val message = RuntimeMessageCodec.decodeInbound(
            """{"version":1,"type":"playbackStateChanged","payload":{"workId":"glass-rail","isPlaying":true,"state":"playing"}}"""
        )

        assertTrue(message is RuntimeInboundMessage.Event)
        val event = (message as RuntimeInboundMessage.Event).event
        assertTrue(event is ReaderRuntimeEvent.PlaybackStateChanged)
        val playback = event as ReaderRuntimeEvent.PlaybackStateChanged
        assertEquals("glass-rail", playback.workId)
        assertTrue(playback.isPlaying)
        assertEquals("playing", playback.state)
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

    @Test
    fun decodeRuntimeErrorKeepsCommandMetadata() {
        val message = RuntimeMessageCodec.decodeInbound(
            """
            {
              "version": 1,
              "id": "android-7",
              "type": "error",
              "payload": {
                "workId": "glass-rail",
                "code": "SCRIPT_SOURCE_MISSING",
                "message": "missing source",
                "recoverable": true
              }
            }
            """.trimIndent()
        )

        assertTrue(message is RuntimeInboundMessage.Event)
        val event = (message as RuntimeInboundMessage.Event).event
        assertTrue(event is ReaderRuntimeEvent.Failed)
        val failed = event as ReaderRuntimeEvent.Failed
        assertEquals("glass-rail", failed.workId)
        assertEquals("SCRIPT_SOURCE_MISSING", failed.code)
        assertEquals("android-7", failed.commandId)
        assertEquals(true, failed.recoverable)
    }

    @Test
    fun rejectUnsupportedProtocolVersion() {
        val message = RuntimeMessageCodec.decodeInbound(
            """{"version":2,"type":"ready","payload":{"workId":"glass-rail"}}"""
        )

        assertTrue(message is RuntimeInboundMessage.Invalid)
    }
}
