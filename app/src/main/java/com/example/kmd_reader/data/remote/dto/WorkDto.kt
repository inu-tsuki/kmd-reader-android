package com.example.kmd_reader.data.remote.dto

import kotlinx.serialization.Serializable

@Serializable
data class WorkDto(
    val id: String,
    val title: String,
    val authorName: String,
    val description: String,
    val tags: List<String> = emptyList(),
    val presentationMode: String,
    val orientationHint: String,
    val aspectRatio: String,
    val lifecycleStatus: String,
    val interactionLevel: String = "read_only",
    val previewMode: String = "cover",
    val estimatedDurationSec: Int,
    val coverUrl: String = "",
    val script: KmdScriptDto = KmdScriptDto(activeRevisionId = "rev-1"),
    val assetManifest: RuntimeAssetManifestDto? = null,
    val stats: WorkStatsDto? = null,
    val commentSummary: CommentSummaryDto? = null
)

@Serializable
data class KmdScriptDto(
    val activeRevisionId: String,
    val sourceUrl: String? = null,
    val mimeType: String? = null,
    val kmdVersion: String? = null,
    val runtimeVersion: String? = null,
    val contentHash: String? = null,
    val revisions: List<KmdScriptRevisionDto> = emptyList()
)

@Serializable
data class KmdScriptRevisionDto(
    val id: String,
    val label: String = "",
    val sourceUrl: String,
    val mimeType: String = "text/x-kmd",
    val kmdVersion: String = "",
    val runtimeVersion: String = "",
    val createdAt: String = "",
    val contentHash: String? = null
)

@Serializable
data class RuntimeAssetManifestDto(
    val baseUrl: String? = null,
    val assets: Map<String, RuntimeAssetRefDto> = emptyMap()
)

@Serializable
data class RuntimeAssetRefDto(
    val url: String,
    val type: String? = null
)

@Serializable
data class WorkStatsDto(
    val scenes: Int = 0,
    val lines: Int = 0,
    val effects: Int = 0
)

@Serializable
data class CommentSummaryDto(
    val count: Int = 0,
    val preview: List<String> = emptyList()
)
