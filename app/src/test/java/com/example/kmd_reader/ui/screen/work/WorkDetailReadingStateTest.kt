package com.example.kmd_reader.ui.screen.work

import com.example.kmd_reader.ui.screen.mine.ShelfItem
import com.example.kmd_reader.ui.screen.mine.ShelfState
import com.example.kmd_reader.ui.format.formatRelativeReadTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.TimeUnit

class WorkDetailReadingStateTest {
    private val now = TimeUnit.DAYS.toMillis(100)

    @Test
    fun resolverCoversProgressAndRevisionStates() {
        assertState(null, "current", "开始阅读", null)
        assertState(item(progress = 0f), "current", "开始阅读", null)
        assertState(item(progress = .42f, savedRevision = "current"), "current", "继续阅读", "已读 42%")
        assertState(item(progress = .42f, savedRevision = null), "current", "继续阅读", "已读 42%")
        assertState(item(progress = .42f, savedRevision = "saved"), null, "继续阅读", "已读 42%")
        assertState(item(progress = .42f, savedRevision = "saved"), "current", "开始阅读", "作品已更新，将从头开始")
        assertState(item(progress = 1f), "current", "开始阅读", "已读完")
        assertState(item(progress = 2f), "current", "开始阅读", "已读完")
        assertState(item(progress = -1f), "current", "开始阅读", null)
    }

    @Test
    fun resolverBuildsTimeSummariesWithoutLeakingPreviousWorkState() {
        assertState(
            item(progress = .42f, lastReadAt = now - TimeUnit.DAYS.toMillis(1)),
            "current",
            "继续阅读",
            "已读 42% · 上次阅读 1 天前"
        )
        assertState(
            item(progress = 0f, lastReadAt = now - TimeUnit.DAYS.toMillis(1)),
            "current",
            "开始阅读",
            "上次阅读 1 天前"
        )

        val state = ShelfState(shelf = listOf(item(workId = "a", progress = .42f)))
        assertEquals("继续阅读", resolveWorkDetailReadingState(state.findByWorkId("a"), "current", now).buttonLabel)
        assertEquals("开始阅读", resolveWorkDetailReadingState(state.findByWorkId("b"), "current", now).buttonLabel)
    }

    @Test
    fun shelfLookupCoversBothMutuallyExclusiveGroups() {
        val shelfItem = item(workId = "shelf", progress = .2f)
        val historyItem = item(workId = "history", progress = .3f, onShelf = false)
        val state = ShelfState(shelf = listOf(shelfItem), history = listOf(historyItem))

        assertEquals(shelfItem, state.findByWorkId("shelf"))
        assertEquals(historyItem, state.findByWorkId("history"))
        assertNull(state.findByWorkId("missing"))
    }

    @Test
    fun relativeTimeHandlesSpecifiedBoundaries() {
        assertEquals("今天", formatRelativeReadTime(now, now))
        assertEquals("今天", formatRelativeReadTime(now, now + 1))
        assertEquals("1 天前", formatRelativeReadTime(now, now - TimeUnit.DAYS.toMillis(1)))
        assertEquals("29 天前", formatRelativeReadTime(now, now - TimeUnit.DAYS.toMillis(29)))
        assertEquals("1 个月前", formatRelativeReadTime(now, now - TimeUnit.DAYS.toMillis(30)))
    }

    private fun assertState(
        item: ShelfItem?,
        currentRevision: String?,
        button: String,
        summary: String?
    ) {
        val state = resolveWorkDetailReadingState(item, currentRevision, now)
        assertEquals(button, state.buttonLabel)
        assertEquals(summary, state.summary)
    }

    private fun item(
        workId: String = "work",
        progress: Float = 0f,
        savedRevision: String? = "current",
        lastReadAt: Long? = null,
        onShelf: Boolean = true
    ) = ShelfItem(
        workId = workId,
        title = "Work",
        authorName = "Author",
        modeLabel = "滚动",
        readingProgress = progress,
        lastReadAt = lastReadAt,
        importedAt = null,
        activeRevisionId = savedRevision,
        onShelf = onShelf,
        hasLocalSource = true
    )
}
