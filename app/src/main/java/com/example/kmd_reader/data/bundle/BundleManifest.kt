package com.example.kmd_reader.data.bundle

import kotlinx.serialization.Serializable

/**
 * `.kmdwork` bundle manifest（work.json），对齐 `work-bundle-format.md` §3 骨架。
 * 用 kotlinx.serialization + ignoreUnknownKeys = true（项目既有模式），容忍未声明字段演进。
 *
 * manifest 内 asset URL = bundle 内相对路径（work-bundle-format.md §3 注释）。
 * BundleAssetManifest 形状对齐 ReaderRuntimeAssetManifest（含 fonts），但不直接复用
 * runtime 类型——bundle manifest 是导入/存储层的概念，runtime 类型属于播放层，保持分层。
 */

@Serializable
data class BundleManifest(
    val formatVersion: Int = 1,
    val bundleId: String,
    val entry: String = "scripts/main.kmd",
    val presentation: BundlePresentationSnapshot? = null,
    val assetManifest: BundleAssetManifest? = null,
    val revisions: List<BundleRevisionEntry> = emptyList(),
    val exportRevisionId: String? = null,
    val origin: BundleOrigin? = null,
    val communitySnapshot: BundleCommunitySnapshot? = null
)

@Serializable
data class BundleAssetManifest(
    val baseUrl: String? = null,
    val fonts: List<BundleFontAsset> = emptyList(),
    val assets: Map<String, BundleAssetRef> = emptyMap()
)

@Serializable
data class BundleFontAsset(
    val family: String,
    val url: String,
    val weight: String? = null,
    val style: String? = null
)

@Serializable
data class BundleAssetRef(
    val url: String,
    val type: String? = null
)

@Serializable
data class BundleRevisionEntry(
    val id: String,
    val parentRevisionId: String? = null,
    val contentHash: String,
    val message: String? = null,
    val createdAt: Long = 0,
    val remoteRevisionId: String? = null
)

@Serializable
data class BundleOrigin(
    val workId: String? = null,
    val revisionId: String? = null,
    val sourceUrl: String? = null,
    val exportedAt: Long? = null
)

@Serializable
data class BundlePresentationSnapshot(
    val mode: String? = null,
    val aspectRatio: String? = null,
    val orientationHint: String? = null
)

@Serializable
data class BundleCommunitySnapshot(
    val title: String? = null,
    val authorName: String? = null
)