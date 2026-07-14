package com.example.kmd_reader.ui.screen.mine

import com.example.kmd_reader.data.repository.LocalLibraryEntry
import com.example.kmd_reader.ui.format.formatRelativeReadTime

/**
 * R3-F：书架/历史卡片的纯 UI 模型。由 [LocalLibraryEntry] 直接组装，不依赖 domain [Work]——
 * entry 自带 title/authorName/presentationMode，卡片不需要 description/tags。
 *
 * @param hasLocalSource 有本地源可离线播放（kmdSource!=null || bundleId!=null），与 isLocalEntry 判据一致。
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

/** Page-local bookshelf mode. It belongs to Compose saveable state, never persisted preferences. */
enum class BookshelfView { Library, History }

enum class BookshelfEmptyState {
    None,
    Library,
    History
}

/** Render-ready model; MineDesk never has to infer persistence or source facts. */
data class BookshelfCardModel(
    val workId: String,
    val title: String,
    val authorName: String,
    val modeLabel: String,
    val sourceLabel: String,
    val progress: Float?,
    val timeLabel: String?,
    val canContinue: Boolean
)

data class BookshelfUiModel(
    val view: BookshelfView,
    val continueReading: List<BookshelfCardModel>,
    val localShelf: List<BookshelfCardModel>,
    val networkShelf: List<BookshelfCardModel>,
    val history: List<BookshelfCardModel>,
    val emptyState: BookshelfEmptyState
)

/**
 * Builds the complete R3-K1 bookshelf presentation from the persisted snapshot.
 * Inputs are never mutated: repository ordering is preserved inside normal shelf/history groups.
 */
fun ShelfState.toBookshelfUiModel(
    view: BookshelfView,
    nowMillis: Long
): BookshelfUiModel {
    val localShelf = shelf.filter { it.hasLocalSource }.map { it.toCardModel(nowMillis) }
    val networkShelf = shelf.filterNot { it.hasLocalSource }.map { it.toCardModel(nowMillis) }
    val historyCards = history.map { it.toCardModel(nowMillis) }
    val continueReading = if (view == BookshelfView.Library) {
        (shelf + history)
            .asSequence()
            .filter { item ->
                item.readingProgress.isFinite() && item.readingProgress > 0f &&
                    item.readingProgress < 1f && item.lastReadAt != null
            }
            .sortedByDescending { it.lastReadAt }
            .distinctBy { it.workId }
            .take(3)
            .map { it.toCardModel(nowMillis) }
            .toList()
    } else {
        emptyList()
    }
    val emptyState = when (view) {
        BookshelfView.Library -> if (
            localShelf.isEmpty() && networkShelf.isEmpty() && continueReading.isEmpty()
        ) {
            BookshelfEmptyState.Library
        } else {
            BookshelfEmptyState.None
        }
        BookshelfView.History -> if (historyCards.isEmpty()) {
            BookshelfEmptyState.History
        } else {
            BookshelfEmptyState.None
        }
    }

    return BookshelfUiModel(
        view = view,
        continueReading = continueReading,
        localShelf = localShelf,
        networkShelf = networkShelf,
        history = historyCards,
        emptyState = emptyState
    )
}

private fun ShelfItem.toCardModel(nowMillis: Long): BookshelfCardModel {
    val validProgress = readingProgress.takeIf { it.isFinite() && it > 0f && it < 1f }
    val timeLabel = lastReadAt?.let { "上次阅读 ${formatRelativeReadTime(nowMillis, it)}" }
        ?: importedAt?.let { "导入于 ${formatRelativeReadTime(nowMillis, it)}" }
    return BookshelfCardModel(
        workId = workId,
        title = title,
        authorName = authorName,
        modeLabel = modeLabel,
        sourceLabel = if (hasLocalSource) "本地可读" else "需联网",
        progress = validProgress,
        timeLabel = timeLabel,
        canContinue = validProgress != null
    )
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
