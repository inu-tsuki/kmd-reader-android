package com.example.kmd_reader.runtime

import com.example.kmd_reader.domain.model.Work

data class ReaderLoadRequest(
    val work: Work,
    val source: String? = null,
    val sourceUrl: String? = null,
    val assetManifest: ReaderRuntimeAssetManifest? = null,
    val settings: ReaderSettings = ReaderSettings()
)

data class ReaderSettings(
    val reducedMotion: Boolean = false,
    val fontScale: Float = 1f,
    val assetBaseUrl: String? = "./",
    val presentationMode: String? = null,
    val viewport: ReaderRuntimeViewport? = null
)

data class ReaderRuntimeViewport(
    val width: Int,
    val height: Int,
    val devicePixelRatio: Float? = null,
    val backgroundColor: String? = null
)

data class ReaderRuntimeTimelineMarker(
    val id: String,
    val label: String? = null,
    val timeMs: Long? = null,
    val startTimeMs: Long? = null,
    val durationMs: Long? = null,
    val progress: Float? = null,
    val segmentId: String? = null,
    val paragraphIndex: Int? = null,
    val line: Int? = null,
    val content: String? = null,
    val type: String? = null
)

data class ReaderRuntimeCapabilities(
    val protocolVersion: Int = 1,
    val supportsSourceText: Boolean = false,
    val supportsSourceUrl: Boolean = false,
    val supportsAssetManifest: Boolean = false,
    val supportsSeekTime: Boolean = false,
    val supportsTimelineMarkers: Boolean = false,
    val supportsInspection: Boolean = false,
    val supportsInteractiveSegments: Boolean = false
)

data class ReaderRuntimeAssetManifest(
    val baseUrl: String? = null,
    val fonts: List<ReaderRuntimeFontAsset> = emptyList(),
    val assets: Map<String, ReaderRuntimeAssetRef> = emptyMap()
)

data class ReaderRuntimeFontAsset(
    val family: String,
    val url: String,
    val weight: String? = null,
    val style: String? = null
)

data class ReaderRuntimeAssetRef(
    val url: String,
    val type: String? = null
)
