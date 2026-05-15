package com.example.kmd_reader.runtime.webview

import com.example.kmd_reader.domain.model.IssueSeverity
import com.example.kmd_reader.domain.model.IssueSource
import com.example.kmd_reader.domain.model.ScriptIssue
import com.example.kmd_reader.domain.model.Work
import com.example.kmd_reader.runtime.ReaderLoadRequest
import com.example.kmd_reader.runtime.ReaderRuntimeEvent
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
            payload = buildJsonObject {
                put("reducedMotion", JsonPrimitive(settings.reducedMotion))
                put("fontScale", JsonPrimitive(settings.fontScale))
            }
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

        val type = root.string("type")
        val payload = root.obj("payload") ?: buildJsonObject {}

        return when (type) {
            "runtimeReady" -> RuntimeInboundMessage.HostReady
            "ready" -> decodeReady(payload)
            "progressChanged" -> decodeProgressChanged(payload)
            "playbackStateChanged" -> decodePlaybackStateChanged(payload)
            "inspectionReported" -> decodeInspectionReported(payload)
            "error" -> decodeError(payload)
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

    private fun decodeReady(payload: JsonObject): RuntimeInboundMessage {
        val workId = payload.string("workId")
            ?: return RuntimeInboundMessage.Invalid("Runtime ready event is missing workId")
        return RuntimeInboundMessage.Event(
            ReaderRuntimeEvent.Ready(workId = workId)
        )
    }

    private fun decodeProgressChanged(payload: JsonObject): RuntimeInboundMessage {
        val workId = payload.string("workId")
            ?: return RuntimeInboundMessage.Invalid("Runtime progress event is missing workId")
        return RuntimeInboundMessage.Event(
            ReaderRuntimeEvent.ProgressChanged(
                workId = workId,
                progress = payload.float("progress")?.coerceIn(0f, 1f) ?: 0f,
                positionPayload = payload.string("positionPayload").orEmpty()
            )
        )
    }

    private fun decodePlaybackStateChanged(payload: JsonObject): RuntimeInboundMessage {
        val workId = payload.string("workId")
            ?: return RuntimeInboundMessage.Invalid("Runtime playback event is missing workId")
        return RuntimeInboundMessage.Event(
            ReaderRuntimeEvent.PlaybackStateChanged(
                workId = workId,
                isPlaying = payload.boolean("isPlaying") ?: false
            )
        )
    }

    private fun decodeInspectionReported(payload: JsonObject): RuntimeInboundMessage {
        val workId = payload.string("workId")
            ?: return RuntimeInboundMessage.Invalid("Runtime inspection event is missing workId")
        val issues = payload.array("issues")
            ?.mapNotNull { parseIssue(it, fallbackWorkId = workId) }
            .orEmpty()
        return RuntimeInboundMessage.Event(
            ReaderRuntimeEvent.InspectionReported(
                workId = workId,
                issues = issues
            )
        )
    }

    private fun decodeError(payload: JsonObject): RuntimeInboundMessage =
        RuntimeInboundMessage.Event(
            ReaderRuntimeEvent.Failed(
                workId = payload.string("workId"),
                message = payload.string("message") ?: "Reader Runtime error"
            )
        )

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

    private fun JsonObject.boolean(key: String): Boolean? =
        primitive(key)?.content?.toBooleanStrictOrNull()

    private fun JsonObject.obj(key: String): JsonObject? =
        this[key] as? JsonObject

    private fun JsonObject.array(key: String): JsonArray? =
        this[key] as? JsonArray

    private fun JsonObject.primitive(key: String): JsonPrimitive? =
        this[key] as? JsonPrimitive
}
