package com.example.kmd_reader.data.repository

import com.example.kmd_reader.data.WorkRepository
import com.example.kmd_reader.data.bundle.BundleStore
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
import com.example.kmd_reader.domain.model.WorkAttributes
import com.example.kmd_reader.domain.model.WorkLifecycleStatus
import com.example.kmd_reader.domain.model.WorkPresentation
import com.example.kmd_reader.domain.model.WorkSourceType

/**
 * R3-D3 装饰器：把 local_library 书架条目合并进 WorkRepository 的作品链路。
 *
 * - listWorks：远程/mock 作品 + local_library.getShelf() 的本地作品，去重（本地优先）
 * - getWork：先查 local_library，命中真正本地导入 entry（isLocalEntry）返回本地 Work；否则 delegate
 * - getWorkSource：先查 local_library entry（isLocalEntry 守卫），从 kmdSource 或 BundleStore 读 source；否则 delegate
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
    private val bundleStore: BundleStore? = null
) : WorkRepository {

    override suspend fun listWorks(refresh: Boolean): List<Work> {
        val remoteWorks = delegate.listWorks(refresh)
        // getShelf() 只返回 onShelf=true 条目，但其中仍可能有旧/远程书架 entry
        // （如迁移历史里的 source=Mock、kmdSource=null shelf 条目，见 KmdReaderDatabaseMigrationTest）。
        // 只把真正本地导入的 entry（isLocalEntry）映射成 Work 并覆盖远程，避免把远程/Mock 作品
        // 错误显示成 WorkSourceType.Local 空壳、丢掉原始 metadata。
        val localWorks = localLibrary.getShelf().filter(::isLocalEntry).map { it.toWork() }
        val localIds = localWorks.map { it.id }.toSet()
        return localWorks + remoteWorks.filterNot { it.id in localIds }
    }

    override suspend fun getWork(id: String, refresh: Boolean): Work? {
        // 只对真正本地导入的 entry 拦截。远程/Mock 作品首次阅读时会写入一个
        // source=Remote/Mock、kmdSource=null、bundleId=null、onShelf=false 的进度 entry，
        // 这类纯进度条目不能覆盖远程 Work（见 ViewModel.toLocalLibraryEntry）。
        localLibrary.getEntry(id)?.takeIf(::isLocalEntry)?.let { return it.toWork() }
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
        // 先查 local_library；仅对真正本地导入的 entry 走本地 source 快捷路径，
        // 否则 fallback delegate（远程/Mock 作品的纯进度 entry 无本地源可读）。
        val entry = localLibrary.getEntry(workId)?.takeIf(::isLocalEntry)
        if (entry != null) {
            // 1. kmdSource 快捷路径（裸 .kmd）
            if (!entry.kmdSource.isNullOrBlank()) return entry.kmdSource
            // 2. BundleStore 路径（.kmdwork）
            if (entry.bundleId != null && bundleStore != null) {
                return bundleStore.readEntrySource(entry.bundleId)
            }
            // 本地 entry 但无 source（不应发生）→ fallback delegate
        }
        return delegate.getWorkSource(workId, refresh)
    }
}

/**
 * 判断 local_library entry 是否为"真正本地导入"（而非远程/Mock 作品首次阅读时自动建的进度 entry）。
 * 导入路径写 source=Local 且 kmdSource 或 bundleId 至少一个非空（见 ViewModel.importKmdwork/importPlainKmd）；
 * 这里用宽判据 source==Local || kmdSource!=null || bundleId!=null，兜住历史/迁移产生的 source 异常但确实带本地源的条目。
 * 纯进度 entry（source=Remote/Mock、kmdSource=null、bundleId=null）不被拦截，继续走 delegate 读远程 Work/issues。
 */
private fun isLocalEntry(entry: LocalLibraryEntry): Boolean =
    entry.source == WorkSourceType.Local || entry.kmdSource != null || entry.bundleId != null

/**
 * LocalLibraryEntry → Work 的映射。
 * 本地作品缺远程社区字段（description/tags/commentSummary），用合理默认填充。
 */
private fun LocalLibraryEntry.toWork(): Work {
    val mode = presentationMode
    val orientationHint = if (aspectRatio.contains(":")) {
        val parts = aspectRatio.split(":")
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
    return Work(
        id = workId,
        title = title,
        authorName = authorName,
        description = "",
        tags = emptyList(),
        category = "",
        sourceType = WorkSourceType.Local,
        lifecycleStatus = WorkLifecycleStatus.Published,
        presentation = WorkPresentation(
            mode = mode,
            orientationHint = orientationHint,
            aspectRatio = aspectRatio.ifBlank { "16:9" },
            interactionLevel = InteractionLevel.None,
            previewMode = PreviewMode.Runtime
        ),
        contentUri = contentUri,
        previewUri = null,
        script = KmdScriptRef(
            activeRevisionId = activeRevisionId ?: "local",
            activeRevision = KmdScriptRevision(
                id = activeRevisionId ?: "local",
                label = "本地导入",
                sourceUrl = "",
                mimeType = "text/kmd",
                kmdVersion = "1",
                runtimeVersion = "1",
                createdAt = "",
                contentHash = contentHash
            )
        ),
        assetManifest = null,
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