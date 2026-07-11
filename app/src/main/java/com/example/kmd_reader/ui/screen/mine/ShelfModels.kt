package com.example.kmd_reader.ui.screen.mine

import com.example.kmd_reader.data.repository.LocalLibraryEntry

/**
 * R3-F：书架/历史卡片的纯 UI 模型。由 [LocalLibraryEntry] 直接组装，不依赖 domain [Work]——
 * entry 自带 title/authorName/presentationMode，卡片不需要 description/tags。
 *
 * @param hasLocalSource 有本地源可离线播放（kmdSource!=null || bundleId!=null），与 isLocalEntry 判据一致。
 *   用于决定卡片按钮显示「继续阅读」还是「打开详情」。
 */
data class ShelfItem(
    val workId: String,
    val title: String,
    val authorName: String,
    val modeLabel: String,
    val readingProgress: Float,
    val lastReadAt: Long?,
    val importedAt: Long?,
    /** The revision that was active when the persisted reading state was last written. */
    val activeRevisionId: String?,
    val onShelf: Boolean,
    val hasLocalSource: Boolean
)

/**
 * R3-F：书架桌面状态。shelf = onShelf=true（按 lastReadAt/importedAt DESC），
 * history = onShelf=false 但 lastReadAt!=null（按 lastReadAt DESC）。
 */
data class ShelfState(
    val shelf: List<ShelfItem> = emptyList(),
    val history: List<ShelfItem> = emptyList()
) {
    /**
     * Shelf and history are mutually exclusive presentation groups. Detail state must search
     * both because an on-shelf work with reading progress remains in [shelf], not [history].
     */
    fun findByWorkId(workId: String?): ShelfItem? = workId?.let { id ->
        shelf.firstOrNull { it.workId == id } ?: history.firstOrNull { it.workId == id }
    }
}

/**
 * 从 [LocalLibraryEntry] 组装 [ShelfItem]。hasLocalSource 判据与 LocalAwareWorkRepository.isLocalEntry 一致。
 */
fun LocalLibraryEntry.toShelfItem(): ShelfItem = ShelfItem(
    workId = workId,
    title = title,
    authorName = authorName,
    modeLabel = presentationMode.label,
    readingProgress = readingProgress,
    lastReadAt = lastReadAt,
    importedAt = importedAt,
    activeRevisionId = activeRevisionId,
    onShelf = onShelf,
    hasLocalSource = kmdSource != null || bundleId != null
)
