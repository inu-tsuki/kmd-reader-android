package com.example.kmd_reader.runtime.webview

import com.example.kmd_reader.domain.model.IssueSeverity
import com.example.kmd_reader.domain.model.IssueSource
import com.example.kmd_reader.domain.model.ScriptIssue
import com.example.kmd_reader.domain.model.Work
import com.example.kmd_reader.runtime.ReaderLoadRequest
import com.example.kmd_reader.runtime.ReaderRuntimeAssetManifest
import com.example.kmd_reader.runtime.ReaderRuntimeAssetRef
import com.example.kmd_reader.runtime.ReaderRuntimeCapabilities
import com.example.kmd_reader.runtime.ReaderRuntimeEvent
import com.example.kmd_reader.runtime.ReaderRuntimeFontAsset
import com.example.kmd_reader.runtime.ReaderRuntimeTimelineMarker
import com.example.kmd_reader.runtime.ReaderRuntimeViewport
import com.example.kmd_reader.runtime.ReaderSettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

object RuntimeMessageCodec {
    const val VERSION = 1

    private val json = Json {
        ignoreUnknownKeys = true
    }

    fun encodeLoadScript(
        request: ReaderLoadRequest,
        id: String
    ): String {
        return encodeCommand(
            id = id,
            type = "loadScript",
            payload = buildJsonObject {
                put("work", workPayload(request.work))
                request.source?.let { put("source", JsonPrimitive(it)) }
                request.sourceUrl?.let { put("sourceUrl", JsonPrimitive(it)) }
                request.assetManifest?.let { put("assetManifest", assetManifestPayload(it)) }
                put("settings", settingsPayload(request.settings))
            }
        )
    }

    fun encodePlay(id: String): String =
        encodeCommand(id = id, type = "play")

    fun encodePause(id: String): String =
        encodeCommand(id = id, type = "pause")

    fun encodeSeek(
        progress: Float,
        id: String
    ): String {
        return encodeCommand(
            id = id,
            type = "seek",
            payload = buildJsonObject {
                put("progress", JsonPrimitive(progress.coerceIn(0f, 1f)))
            }
        )
    }

    fun encodeSetInspectionEnabled(
        enabled: Boolean,
        id: String
    ): String {
        return encodeCommand(
            id = id,
            type = "setInspectionEnabled",
            payload = buildJsonObject {
                put("enabled", JsonPrimitive(enabled))
            }
        )
    }

    fun encodeUpdateSettings(
        settings: ReaderSettings,
        id: String
    ): String {
        return encodeCommand(
            id = id,
            type = "updateSettings",
            payload = settingsPayload(settings)
        )
    }

    fun encodeDispose(id: String): String =
        encodeCommand(id = id, type = "dispose")

    fun decodeInbound(rawMessage: String): RuntimeInboundMessage {
        val root = runCatching {
            json.parseToJsonElement(rawMessage).jsonObject
        }.getOrElse { error ->
            return RuntimeInboundMessage.Invalid(
                message = error.message ?: "Runtime message is not valid JSON"
            )
        }

        val version = root.int("version")
            ?: return RuntimeInboundMessage.Invalid("Runtime message is missing protocol version")
        if (version != VERSION) {
            return RuntimeInboundMessage.Invalid("Unsupported runtime protocol version: $version")
        }

        val type = root.string("type")
            ?: return RuntimeInboundMessage.Invalid("Runtime message is missing type")
        val envelopeId = root.string("id")
        val sessionId = root.string("sessionId")
        val payload = root.obj("payload") ?: buildJsonObject {}

        return when (type) {
            "runtimeReady" -> decodeRuntimeReady(payload, sessionId)
            "ready" -> decodeReady(payload, sessionId)
            "progressChanged" -> decodeProgressChanged(payload, sessionId)
            "playbackStateChanged" -> decodePlaybackStateChanged(payload, sessionId)
            "inspectionReported" -> decodeInspectionReported(payload, sessionId)
            "error" -> decodeError(payload, envelopeId, sessionId)
            else -> RuntimeInboundMessage.Unknown(type)
        }
    }

    fun quoteForJavascript(value: String): String =
        JsonPrimitive(value).toString()

    private fun encodeCommand(
        id: String,
        type: String,
        payload: JsonObject = buildJsonObject {}
    ): String {
        val envelope = buildJsonObject {
            put("version", JsonPrimitive(VERSION))
            put("id", JsonPrimitive(id))
            put("type", JsonPrimitive(type))
            put("payload", payload)
        }
        return envelope.toString()
    }

    private fun workPayload(work: Work): JsonObject =
        buildJsonObject {
            put("id", JsonPrimitive(work.id))
            put("title", JsonPrimitive(work.title))
            put("authorName", JsonPrimitive(work.authorName))
            put("description", JsonPrimitive(work.description))
            put("category", JsonPrimitive(work.category))
            put("contentUri", JsonPrimitive(work.contentUri))
            put("estimatedDurationSec", JsonPrimitive(work.estimatedDurationSec))
            put(
                "script",
                buildJsonObject {
                    put("activeRevisionId", JsonPrimitive(work.script.activeRevisionId))
                    put("sourceUrl", JsonPrimitive(work.script.activeRevision.sourceUrl))
                    put("mimeType", JsonPrimitive(work.script.activeRevision.mimeType))
                    put("kmdVersion", JsonPrimitive(work.script.activeRevision.kmdVersion))
                    put("runtimeVersion", JsonPrimitive(work.script.activeRevision.runtimeVersion))
                    work.script.activeRevision.contentHash?.let {
                        put("contentHash", JsonPrimitive(it))
                    }
                }
            )
            put(
                "tags",
                JsonArray(work.tags.map { JsonPrimitive(it) })
            )
            put(
                "presentation",
                buildJsonObject {
                    put("mode", JsonPrimitive(work.presentation.mode.name))
                    put("modeLabel", JsonPrimitive(work.presentation.mode.label))
                    put("orientationHint", JsonPrimitive(work.presentation.orientationHint.name))
                    put("orientationHintLabel", JsonPrimitive(work.presentation.orientationHint.label))
                    put("aspectRatio", JsonPrimitive(work.presentation.aspectRatio))
                    put("interactionLevel", JsonPrimitive(work.presentation.interactionLevel.name))
                    put("previewMode", JsonPrimitive(work.presentation.previewMode.name))
                }
            )
            put(
                "attributes",
                buildJsonObject {
                    put("effectIntensity", JsonPrimitive(work.attributes.effectIntensity.name))
                    put("commandCount", JsonPrimitive(work.attributes.commandCount))
                    put("externalAssetCount", JsonPrimitive(work.attributes.externalAssetCount))
                    put("complexityLevel", JsonPrimitive(work.attributes.complexityLevel.name))
                    put("runtimeVersion", JsonPrimitive(work.attributes.runtimeVersion))
                }
            )
        }

    private fun settingsPayload(settings: ReaderSettings): JsonObject =
        buildJsonObject {
            put("reducedMotion", JsonPrimitive(settings.reducedMotion))
            put("fontScale", JsonPrimitive(settings.fontScale))
            settings.assetBaseUrl?.let { put("assetBaseUrl", JsonPrimitive(it)) }
            settings.presentationMode?.let { put("presentationMode", JsonPrimitive(it)) }
            settings.viewport?.let { put("viewport", viewportPayload(it)) }
        }

    private fun viewportPayload(viewport: ReaderRuntimeViewport): JsonObject =
        buildJsonObject {
            put("width", JsonPrimitive(viewport.width))
            put("height", JsonPrimitive(viewport.height))
            viewport.devicePixelRatio?.let { put("devicePixelRatio", JsonPrimitive(it)) }
            viewport.backgroundColor?.let { put("backgroundColor", JsonPrimitive(it)) }
        }

    private fun assetManifestPayload(manifest: ReaderRuntimeAssetManifest): JsonObject =
        buildJsonObject {
            manifest.baseUrl?.let { put("baseUrl", JsonPrimitive(it)) }
            if (manifest.fonts.isNotEmpty()) {
                put("fonts", JsonArray(manifest.fonts.map(::fontAssetPayload)))
            }
            if (manifest.assets.isNotEmpty()) {
                put(
                    "assets",
                    JsonObject(manifest.assets.mapValues { (_, asset) ->
                        assetRefPayload(asset)
                    })
                )
            }
        }

    private fun fontAssetPayload(font: ReaderRuntimeFontAsset): JsonObject =
        buildJsonObject {
            put("family", JsonPrimitive(font.family))
            put("url", JsonPrimitive(font.url))
            font.weight?.let { put("weight", JsonPrimitive(it)) }
            font.style?.let { put("style", JsonPrimitive(it)) }
        }

    private fun assetRefPayload(asset: ReaderRuntimeAssetRef): JsonObject =
        buildJsonObject {
            put("url", JsonPrimitive(asset.url))
            asset.type?.let { put("type", JsonPrimitive(it)) }
        }

    private fun decodeRuntimeReady(
        payload: JsonObject,
        sessionId: String?
    ): RuntimeInboundMessage.HostReady {
        return RuntimeInboundMessage.HostReady(
            ReaderRuntimeEvent.TransportReady(
                runtime = payload.string("runtime") ?: "kmd-reader-runtime",
                version = payload.int("version") ?: VERSION,
                capabilities = payload.obj("capabilities")?.let(::decodeCapabilities),
                sessionId = sessionId
            )
        )
    }

    private fun decodeCapabilities(payload: JsonObject): ReaderRuntimeCapabilities =
        ReaderRuntimeCapabilities(
            protocolVersion = payload.int("protocolVersion") ?: VERSION,
            supportsSourceText = payload.boolean("supportsSourceText") ?: false,
            supportsSourceUrl = payload.boolean("supportsSourceUrl") ?: false,
            supportsAssetManifest = payload.boolean("supportsAssetManifest") ?: false,
            supportsSeekTime = payload.boolean("supportsSeekTime") ?: false,
            supportsTimelineMarkers = payload.boolean("supportsTimelineMarkers") ?: false,
            supportsInspection = payload.boolean("supportsInspection") ?: false,
            supportsInteractiveSegments = payload.boolean("supportsInteractiveSegments") ?: false
        )

    private fun decodeReady(
        payload: JsonObject,
        sessionId: String?
    ): RuntimeInboundMessage {
        val workId = payload.string("workId")
            ?: return RuntimeInboundMessage.Invalid("Runtime ready event is missing workId")
        return RuntimeInboundMessage.Event(
            ReaderRuntimeEvent.Ready(
                workId = workId,
                durationMs = payload.long("durationMs"),
                timelineMarkers = payload.array("timelineMarkers")
                    ?.mapNotNull(::parseTimelineMarker)
                    .orEmpty(),
                sessionId = sessionId
            )
        )
    }

    private fun decodeProgressChanged(
        payload: JsonObject,
        sessionId: String?
    ): RuntimeInboundMessage {
        val workId = payload.string("workId")
            ?: return RuntimeInboundMessage.Invalid("Runtime progress event is missing workId")
        return RuntimeInboundMessage.Event(
            ReaderRuntimeEvent.ProgressChanged(
                workId = workId,
                progress = payload.float("progress")?.coerceIn(0f, 1f) ?: 0f,
                positionPayload = payload.string("positionPayload").orEmpty(),
                timeMs = payload.long("timeMs"),
                durationMs = payload.long("durationMs"),
                segmentId = payload.string("segmentId"),
                paragraphIndex = payload.int("paragraphIndex"),
                line = payload.int("line"),
                checkpointId = payload.string("checkpointId"),
                markerId = payload.string("markerId"),
                sessionId = sessionId
            )
        )
    }

    private fun decodePlaybackStateChanged(
        payload: JsonObject,
        sessionId: String?
    ): RuntimeInboundMessage {
        val workId = payload.string("workId")
            ?: return RuntimeInboundMessage.Invalid("Runtime playback event is missing workId")
        return RuntimeInboundMessage.Event(
            ReaderRuntimeEvent.PlaybackStateChanged(
                workId = workId,
                isPlaying = payload.boolean("isPlaying") ?: false,
                state = payload.string("state"),
                sessionId = sessionId
            )
        )
    }

    private fun decodeInspectionReported(
        payload: JsonObject,
        sessionId: String?
    ): RuntimeInboundMessage {
        val workId = payload.string("workId")
            ?: return RuntimeInboundMessage.Invalid("Runtime inspection event is missing workId")
        val issues = payload.array("issues")
            ?.mapNotNull { parseIssue(it, fallbackWorkId = workId) }
            .orEmpty()
        return RuntimeInboundMessage.Event(
            ReaderRuntimeEvent.InspectionReported(
                workId = workId,
                issues = issues,
                sessionId = sessionId
            )
        )
    }

    private fun decodeError(
        payload: JsonObject,
        envelopeId: String?,
        sessionId: String?
    ): RuntimeInboundMessage =
        RuntimeInboundMessage.Event(
            ReaderRuntimeEvent.Failed(
                workId = payload.string("workId"),
                message = payload.string("message") ?: "Reader Runtime error",
                code = payload.string("code"),
                commandId = payload.string("commandId") ?: envelopeId,
                recoverable = payload.boolean("recoverable"),
                sessionId = sessionId
            )
        )

    private fun parseTimelineMarker(element: JsonElement): ReaderRuntimeTimelineMarker? {
        val marker = element as? JsonObject ?: return null
        val id = marker.string("id") ?: return null
        return ReaderRuntimeTimelineMarker(
            id = id,
            label = marker.string("label"),
            timeMs = marker.long("timeMs"),
            startTimeMs = marker.long("startTime"),
            durationMs = marker.long("duration"),
            progress = marker.float("progress")?.coerceIn(0f, 1f),
            segmentId = marker.string("segmentId"),
            paragraphIndex = marker.int("paragraphIndex"),
            line = marker.int("line"),
            content = marker.string("content"),
            type = marker.string("type")
        )
    }

    private fun parseIssue(
        element: JsonElement,
        fallbackWorkId: String
    ): ScriptIssue? {
        val issue = element as? JsonObject ?: return null
        val id = issue.string("id") ?: return null
        return ScriptIssue(
            id = id,
            workId = issue.string("workId") ?: fallbackWorkId,
            severity = enumValueOrDefault(
                raw = issue.string("severity"),
                defaultValue = IssueSeverity.Info
            ),
            source = enumValueOrDefault(
                raw = issue.string("source"),
                defaultValue = IssueSource.Runtime
            ),
            location = issue.string("location").orEmpty(),
            message = issue.string("message").orEmpty(),
            suggestion = issue.string("suggestion").orEmpty()
        )
    }

    private inline fun <reified T : Enum<T>> enumValueOrDefault(
        raw: String?,
        defaultValue: T
    ): T {
        return raw?.let { value ->
            runCatching { enumValueOf<T>(value) }.getOrNull()
        } ?: defaultValue
    }

    private fun JsonObject.string(key: String): String? =
        primitive(key)?.content

    private fun JsonObject.float(key: String): Float? =
        primitive(key)?.content?.toFloatOrNull()

    private fun JsonObject.long(key: String): Long? =
        primitive(key)?.content?.let { value ->
            value.toLongOrNull()
                ?: value.toDoubleOrNull()
                    ?.takeIf { !it.isNaN() && !it.isInfinite() }
                    ?.toLong()
        }

    private fun JsonObject.int(key: String): Int? =
        primitive(key)?.content?.let { value ->
            value.toIntOrNull()
                ?: value.toDoubleOrNull()
                    ?.takeIf { !it.isNaN() && !it.isInfinite() }
                    ?.toInt()
        }

    private fun JsonObject.boolean(key: String): Boolean? =
        primitive(key)?.content?.toBooleanStrictOrNull()

    private fun JsonObject.obj(key: String): JsonObject? =
        this[key] as? JsonObject

    private fun JsonObject.array(key: String): JsonArray? =
        this[key] as? JsonArray

    private fun JsonObject.primitive(key: String): JsonPrimitive? =
        this[key] as? JsonPrimitive
}
