package com.example.kmd_reader.data.repository

import com.example.kmd_reader.data.WorkRepository
import com.example.kmd_reader.data.bundle.BundleAssetManifest
import com.example.kmd_reader.data.bundle.BundleAssetRef
import com.example.kmd_reader.data.bundle.BundleFontAsset
import com.example.kmd_reader.data.bundle.BundleManifest
import com.example.kmd_reader.data.bundle.BundleStore
import com.example.kmd_reader.data.bundle.RevisionSourceStore
import com.example.kmd_reader.domain.model.CommentSummary
import com.example.kmd_reader.domain.model.ComplexityLevel
import com.example.kmd_reader.domain.model.EffectIntensity
import com.example.kmd_reader.domain.model.InteractionLevel
import com.example.kmd_reader.domain.model.KmdScriptRef
import com.example.kmd_reader.domain.model.KmdScriptRevision
import com.example.kmd_reader.domain.model.OrientationHint
import com.example.kmd_reader.domain.model.PresentationMode
import com.example.kmd_reader.domain.model.PreviewMode
import com.example.kmd_reader.domain.model.ScriptIssue
import com.example.kmd_reader.domain.model.Work
import com.example.kmd_reader.domain.model.WorkAssetManifest
import com.example.kmd_reader.domain.model.WorkAssetRef
import com.example.kmd_reader.domain.model.WorkAttributes
import com.example.kmd_reader.domain.model.WorkFontAsset
import com.example.kmd_reader.domain.model.WorkLifecycleStatus
import com.example.kmd_reader.domain.model.WorkPresentation
import com.example.kmd_reader.domain.model.WorkSourceType

/**
 * R3-D3 装饰器：把 local_library 书架条目合并进 WorkRepository 的作品链路。
 *
 * - listWorks：远程/mock 作品 + local_library.getShelf() 的本地作品，去重（本地优先）
 * - getWork：先查 local_library，命中真正本地导入 entry（isLocalEntry）返回本地 Work；否则 delegate
 * - getWorkSource：先查 local_library entry（isLocalEntry 守卫），优先读最新本地提交 source
 *   → 否则 kmdSource / BundleStore 读 source；否则 delegate（§2.7 播放优先级）
 * - listIssues：本地作品无 issues，返回空；否则 delegate
 *
 * 关键：仅对真正本地导入的 entry 拦截（isLocalEntry）。远程/Mock 作品首次阅读时会写入一个
 * source=Remote/Mock、kmdSource=null、bundleId=null、onShelf=false 的进度 entry；这类条目不得
 * 覆盖远程 Work/issues，否则读过一次的社区作品再进 Review/Issues 会丢掉远程检查结果。
 *
 * 这样导入的作品出现在 state.works 中，且阅读时能从本地读 source。
 */
class LocalAwareWorkRepository(
    private val delegate: WorkRepository,
    private val localLibrary: LocalLibraryRepository,
    private val bundleStore: BundleStore? = null,
    private val revisionSourceStore: RevisionSourceStore? = null
) : WorkRepository {

    override suspend fun listWorks(refresh: Boolean): List<Work> {
        val remoteWorks = delegate.listWorks(refresh)
        // getShelf() 只返回 onShelf=true 条目，但其中仍可能有旧/远程书架 entry
        // （如迁移历史里的 source=Mock、kmdSource=null shelf 条目，见 KmdReaderDatabaseMigrationTest）。
        // 只把真正本地导入的 entry（isLocalEntry）映射成 Work 并覆盖远程，避免把远程/Mock 作品
        // 错误显示成 WorkSourceType.Local 空壳、丢掉原始 metadata。
        // resolveLocalPlayable 内部做 getEntry + isLocalEntry 守卫 + revision/source 解析，
        // 保证 listWorks 投影的 revision id 与 getWorkSource 播放的 source 来自同一解析结果。
        val localWorks = localLibrary.getShelf().mapNotNull { entry ->
            resolveLocalPlayable(entry.workId)?.let { toWork(it, bundleStore) }
        }
        val localIds = localWorks.map { it.id }.toSet()
        return localWorks + remoteWorks.filterNot { it.id in localIds }
    }

    override suspend fun getWork(id: String, refresh: Boolean): Work? {
        // 只对真正本地导入的 entry 拦截。远程/Mock 作品首次阅读时会写入一个
        // source=Remote/Mock、kmdSource=null、bundleId=null、onShelf=false 的进度 entry，
        // 这类纯进度条目不能覆盖远程 Work（见 ViewModel.toLocalLibraryEntry）。
        // resolveLocalPlayable 内部做 getEntry + isLocalEntry 守卫；非 null 时 toWork 消费同一解析结果。
        resolveLocalPlayable(id)?.let { return toWork(it, bundleStore) }
        return delegate.getWork(id, refresh)
    }

    override suspend fun listIssues(workId: String, refresh: Boolean): List<ScriptIssue> {
        // 本地作品的 issues 走本地（R3-C issue draft / R4 范畴），不查远程。
        // 同 getWork：仅对真正本地导入的 entry 拦截，否则远程 issue 结果会被空壳进度 entry 丢掉。
        localLibrary.getEntry(workId)?.takeIf(::isLocalEntry)?.let {
            return emptyList()
        }
        return delegate.listIssues(workId, refresh)
    }

    override suspend fun getWorkSource(workId: String, refresh: Boolean): String? {
        // resolveLocalPlayable 统一解析 entry + revision + source（§2.7 播放优先级）。
        // 与 getWork / listWorks 消费同一个 resolver，保证 source 和 Work.script 身份来自同一解析。
        val playable = resolveLocalPlayable(workId)
        if (playable != null) {
            return playable.source ?: delegate.getWorkSource(workId, refresh)
        }
        return delegate.getWorkSource(workId, refresh)
    }

    /**
     * R3-E 收束：统一解析本地可播放作品（§2.7 播放优先级）。
     *
     * 把 getWork/listWorks 的 revision 投影与 getWorkSource 的 source 选择收敛成一次解析，
     * 返回 [LocalPlayable]——包含 entry、source 可读的 latest revision、按优先级解析出的 source。
     * getWork / listWorks 用 playableRevision 投影 Work.script 身份；getWorkSource 直接返回 source。
     * 从结构上保证 Work.script.activeRevisionId 和实际播放 source 来自同一个解析结果。
     *
     * 解析顺序（与原 getWorkSource 分支一致）：
     * 1. getEntry(workId)?.takeIf(::isLocalEntry) → 否则返回 null（走 delegate）
     * 2. getLatestRevision(workId) → readSource(sourcePath) 可读：playableRevision = revision, source = revisionSource
     * 3. revision 不可读/不存在 → fallback：kmdSource → BundleStore.readEntrySource → null
     * 4. playableRevision 只在 step 2 命中时非 null（source 缺失时不投影，回退 entry 指针）
     */
    private suspend fun resolveLocalPlayable(workId: String): LocalPlayable? {
        val entry = localLibrary.getEntry(workId)?.takeIf(::isLocalEntry) ?: return null

        // 0. 优先：最新本地提交的 source（§2.7，无论 syncState——最新提交即最新可播放版本）。
        // revision 存在但 source 文件丢失（bundle 被删/文件损坏）→ fallback 到下一优先级，
        // 因为 R3-E 是接口预留、生产写入面尚未接入，残缺 revision 不应阻塞已有 source 的播放。
        val latestRevision = localLibrary.getLatestRevision(workId)
        if (latestRevision != null && revisionSourceStore != null) {
            val revisionSource = revisionSourceStore.readSource(latestRevision.sourcePath)
            if (revisionSource != null) {
                return LocalPlayable(
                    entry = entry,
                    playableRevision = latestRevision,
                    source = revisionSource
                )
            }
        }

        // 1. kmdSource 快捷路径（裸 .kmd）
        if (!entry.kmdSource.isNullOrBlank()) {
            return LocalPlayable(entry = entry, playableRevision = null, source = entry.kmdSource)
        }

        // 2. BundleStore 路径（.kmdwork）
        if (entry.bundleId != null && bundleStore != null) {
            val bundleSource = bundleStore.readEntrySource(entry.bundleId)
            return LocalPlayable(entry = entry, playableRevision = null, source = bundleSource)
        }

        // 本地 entry 但无 source（不应发生）→ 返回 entry 让上层 fallback delegate
        return LocalPlayable(entry = entry, playableRevision = null, source = null)
    }

    /**
     * LocalPlayable → Work 的映射。
     * 本地作品缺远程社区字段（description/tags/commentSummary），用合理默认填充。
     *
     * R3-D4：bundleId 作品从 BundleStore.readManifest() 读 assetManifest（含 fonts），
     * baseUrl 改写为 https://kmd-reader-assets.local/<bundleId>/（spike §4.4/§4.6 展开层职责）。
     * 裸 .kmd（kmdSource 非空、bundleId null）无 assets，assetManifest=null。
     *
     * R3-E 版本身份一致：从 [resolveLocalPlayable] 的结果投影 playableRevision 到 Work.script。
     * getWorkSource() 读同一 resolver 返回的 source 播放；toWork() 用同一 playableRevision.id
     * 映射进 Work.script.activeRevisionId，从结构上保证身份与播放内容一致。
     * playableRevision 为 null（无 revision 或 source 缺失 fallback）时回退 entry 指针。
     */
    private fun toWork(
        playable: LocalPlayable,
        bundleStore: BundleStore?
    ): Work {
        val entry = playable.entry
        val mode = entry.presentationMode
        val orientationHint = if (entry.aspectRatio.contains(":")) {
            val parts = entry.aspectRatio.split(":")
            val w = parts.getOrNull(0)?.toIntOrNull() ?: 1
            val h = parts.getOrNull(1)?.toIntOrNull() ?: 1
            when {
                w > h -> OrientationHint.Landscape
                w < h -> OrientationHint.Portrait
                else -> OrientationHint.Adaptive
            }
        } else {
            OrientationHint.Adaptive
        }
        // R3-E：投影 resolver 解析出的 playableRevision（source 可读的 latest revision）。
        // 为 null 时回退 entry 指针（与 getWorkSource 的 fallback 对齐）。
        val revisionId = playable.playableRevision?.id ?: entry.activeRevisionId ?: "local"
        val revisionContentHash = playable.playableRevision?.contentHash ?: entry.contentHash
        val revisionLabel = playable.playableRevision?.message ?: "本地导入"
        val revisionCreatedAt = playable.playableRevision?.createdAt?.toString() ?: ""
        return Work(
            id = entry.workId,
            title = entry.title,
            authorName = entry.authorName,
            description = "",
            tags = emptyList(),
            category = "",
            sourceType = WorkSourceType.Local,
            lifecycleStatus = WorkLifecycleStatus.Published,
            presentation = WorkPresentation(
                mode = mode,
                orientationHint = orientationHint,
                aspectRatio = entry.aspectRatio.ifBlank { "16:9" },
                interactionLevel = InteractionLevel.None,
                previewMode = PreviewMode.Runtime
            ),
            contentUri = entry.contentUri,
            previewUri = null,
            script = KmdScriptRef(
                activeRevisionId = revisionId,
                activeRevision = KmdScriptRevision(
                    id = revisionId,
                    label = revisionLabel,
                    sourceUrl = "",
                    mimeType = "text/kmd",
                    kmdVersion = "1",
                    runtimeVersion = "1",
                    createdAt = revisionCreatedAt,
                    contentHash = revisionContentHash
                )
            ),
            assetManifest = entry.resolveAssetManifest(bundleStore),
            estimatedDurationSec = 0,
            attributes = WorkAttributes(
                effectIntensity = EffectIntensity.Medium,
                commandCount = 0,
                externalAssetCount = 0,
                complexityLevel = ComplexityLevel.Simple,
                runtimeVersion = "1"
            ),
            commentSummary = CommentSummary(
                summary = "",
                highlights = emptyList(),
                concerns = emptyList()
            )
        )
    }
}

/**
 * R3-E 收束：[LocalAwareWorkRepository.resolveLocalPlayable] 的解析结果。
 *
 * 把 entry、source 可读的 latest revision、按 §2.7 优先级解析出的 source 打包成一个结果，
 * 供 getWork / listWorks（投影 revision 身份）和 getWorkSource（返回 source）消费同一解析。
 */
private data class LocalPlayable(
    val entry: LocalLibraryEntry,
    /** source 可读的最新 revision；无 revision 或 source 文件缺失时为 null。 */
    val playableRevision: LocalRevision?,
    /** 按 §2.7 优先级解析出的播放 source（revision → kmdSource → BundleStore）；无 source 时为 null。 */
    val source: String?
)

/**
 * 判断 local_library entry 是否为"真正本地导入"（而非远程/Mock 作品首次阅读时自动建的进度 entry）。
 * 导入路径写 source=Local 且 kmdSource 或 bundleId 至少一个非空（见 ViewModel.importKmdwork/importPlainKmd）；
 * 这里用宽判据 source==Local || kmdSource!=null || bundleId!=null，兜住历史/迁移产生的 source 异常但确实带本地源的条目。
 * 纯进度 entry（source=Remote/Mock、kmdSource=null、bundleId=null）不被拦截，继续走 delegate 读远程 Work/issues。
 */
private fun isLocalEntry(entry: LocalLibraryEntry): Boolean =
    entry.source == WorkSourceType.Local || entry.kmdSource != null || entry.bundleId != null

// R3-D4：bundle asset host（与 ReaderRuntimeHost 的 BundleAssetHost 对齐）。
// baseUrl 改写为这个 host + bundleId，runtime 解析相对 asset 路径时基准就是这个虚拟 host，
// 请求被 shouldInterceptRequest 接住（spike §4.4）。
private const val BundleAssetHost = "https://kmd-reader-assets.local"

/**
 * R3-D4：解析本地导入作品的 assetManifest。
 * - bundleId 作品：从 BundleStore 读 BundleManifest，映射 baseUrl（改写为虚拟 host）+ fonts + assets。
 *   assets url 保持 bundle 内相对路径（runtime 用 baseUrl 解析，spike §4.4）。
 * - 裸 .kmd（kmdSource 非空、bundleId null）：无 assets，返回 null。
 * - bundleId 存在但读 manifest 失败（bundle 已删/损坏）：返回 null，让上层走 source 文本播放。
 */
private fun LocalLibraryEntry.resolveAssetManifest(bundleStore: BundleStore?): WorkAssetManifest? {
    val bid = bundleId ?: return null
    val store = bundleStore ?: return null
    val manifest = store.readManifest(bid)?.assetManifest ?: return null
    if (manifest.baseUrl.isNullOrBlank() && manifest.fonts.isEmpty() && manifest.assets.isEmpty()) {
        return null
    }
    return manifest.toDomain(bundleId = bid)
}

private fun BundleAssetManifest.toDomain(bundleId: String): WorkAssetManifest = WorkAssetManifest(
    // baseUrl 改写为虚拟 host + bundleId（spike §4.4/§4.6）；runtime 据此解析相对 asset 路径
    baseUrl = "$BundleAssetHost/$bundleId/",
    fonts = fonts.map { it.toDomain() },
    assets = assets.mapValues { (_, asset) -> asset.toDomain() }
)

private fun BundleFontAsset.toDomain(): WorkFontAsset = WorkFontAsset(
    family = family,
    url = url,
    weight = weight,
    style = style
)

private fun BundleAssetRef.toDomain(): WorkAssetRef = WorkAssetRef(
    url = url,
    type = type
)