package com.example.kmd_reader.ui.screen.work

import com.example.kmd_reader.ui.screen.mine.ShelfItem
import com.example.kmd_reader.ui.format.formatRelativeReadTime

data class WorkDetailReadingState(
    val buttonLabel: String,
    val summary: String?
)

/**
 * Resolves the detail-page promise from the same persisted revision compatibility rule used by
 * restoreSeekOnReady: only two non-null, different revision ids prevent restoration.
 */
fun resolveWorkDetailReadingState(
    item: ShelfItem?,
    currentRevisionId: String?,
    now: Long
): WorkDetailReadingState {
    item ?: return WorkDetailReadingState(buttonLabel = "开始阅读", summary = null)

    val progress = item.readingProgress.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
    val relativeTime = item.lastReadAt?.let { formatRelativeReadTime(now, it) }

    if (progress >= 1f) {
        val summary = listOfNotNull("已读完", relativeTime?.let { "上次阅读 $it" })
            .joinToString(" · ")
        return WorkDetailReadingState(buttonLabel = "开始阅读", summary = summary)
    }

    val revisionsDiffer = item.activeRevisionId != null && currentRevisionId != null &&
        item.activeRevisionId != currentRevisionId
    if (progress > 0f && revisionsDiffer) {
        return WorkDetailReadingState(
            buttonLabel = "开始阅读",
            summary = "作品已更新，将从头开始"
        )
    }

    if (progress > 0f) {
        val percent = (progress * 100).toInt()
        val summary = listOfNotNull("已读 $percent%", relativeTime?.let { "上次阅读 $it" })
            .joinToString(" · ")
        return WorkDetailReadingState(buttonLabel = "继续阅读", summary = summary)
    }

    return WorkDetailReadingState(
        buttonLabel = "开始阅读",
        summary = relativeTime?.let { "上次阅读 $it" }
    )
}
