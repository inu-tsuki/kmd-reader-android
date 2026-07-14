package com.example.kmd_reader.ui.screen.mine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class BookshelfUiProjectionTest {
    private val now = TimeUnit.DAYS.toMillis(100)

    @Test
    fun emptySnapshotUsesSelectedViewEmptyState() {
        val state = ShelfState()

        val library = state.toBookshelfUiModel(BookshelfView.Library, now)
        val history = state.toBookshelfUiModel(BookshelfView.History, now)

        assertEquals(BookshelfEmptyState.Library, library.emptyState)
        assertEquals(BookshelfEmptyState.History, history.emptyState)
        assertTrue(library.continueReading.isEmpty())
        assertTrue(history.continueReading.isEmpty())
    }

    @Test
    fun continueMergesSortsDeduplicatesNewestAndLimitsToThree() {
        val state = ShelfState(
            shelf = listOf(
                item("old", progress = .2f, lastReadAt = now - 50),
                item("duplicate", progress = .2f, lastReadAt = now - 20),
                item("third", progress = .2f, lastReadAt = now - 30),
                item("fourth", progress = .2f, lastReadAt = now - 40)
            ),
            history = listOf(
                item("newest", progress = .2f, lastReadAt = now - 10, onShelf = false),
                item("duplicate", progress = .7f, lastReadAt = now - 5, onShelf = false)
            )
        )

        val result = state.toBookshelfUiModel(BookshelfView.Library, now)

        assertEquals(listOf("duplicate", "newest", "third"), result.continueReading.map { it.workId })
        assertEquals(.7f, result.continueReading.first().progress)
    }

    @Test
    fun continueExcludesInvalidProgressAndMissingReadTime() {
        val state = ShelfState(shelf = listOf(
            item("zero", progress = 0f, lastReadAt = now),
            item("complete", progress = 1f, lastReadAt = now),
            item("negative", progress = -.1f, lastReadAt = now),
            item("over", progress = 1.1f, lastReadAt = now),
            item("nan", progress = Float.NaN, lastReadAt = now),
            item("infinite", progress = Float.POSITIVE_INFINITY, lastReadAt = now),
            item("no-time", progress = .5f, lastReadAt = null),
            item("valid", progress = .5f, lastReadAt = now)
        ))

        val result = state.toBookshelfUiModel(BookshelfView.Library, now)

        assertEquals(listOf("valid"), result.continueReading.map { it.workId })
        assertFalse(result.localShelf.first { it.workId == "complete" }.canContinue)
        assertNull(result.localShelf.first { it.workId == "complete" }.progress)
    }

    @Test
    fun libraryGroupsSourcesWithoutMutatingInputOrder() {
        val firstLocal = item("local-1", hasLocalSource = true)
        val network = item("network", hasLocalSource = false)
        val secondLocal = item("local-2", hasLocalSource = true)
        val shelf = listOf(firstLocal, network, secondLocal)
        val state = ShelfState(shelf = shelf)

        val result = state.toBookshelfUiModel(BookshelfView.Library, now)

        assertEquals(listOf("local-1", "local-2"), result.localShelf.map { it.workId })
        assertEquals(listOf("network"), result.networkShelf.map { it.workId })
        assertEquals("本地可读", result.localShelf.first().sourceLabel)
        assertEquals("需联网", result.networkShelf.first().sourceLabel)
        assertEquals(shelf, state.shelf)
    }

    @Test
    fun historyOnlyProgressKeepsLibraryContinueVisibleWithoutLibraryEmptyState() {
        val history = item("history", progress = .5f, lastReadAt = now - TimeUnit.DAYS.toMillis(1), onShelf = false)
        val library = ShelfState(history = listOf(history)).toBookshelfUiModel(BookshelfView.Library, now)
        val historyView = ShelfState(history = listOf(history)).toBookshelfUiModel(BookshelfView.History, now)

        assertEquals(listOf("history"), library.continueReading.map { it.workId })
        assertTrue(library.localShelf.isEmpty())
        assertTrue(library.networkShelf.isEmpty())
        assertEquals(BookshelfEmptyState.None, library.emptyState)
        assertTrue(historyView.continueReading.isEmpty())
        assertEquals(listOf("history"), historyView.history.map { it.workId })
    }

    @Test
    fun historyStaysSeparateAndDoesNotProduceContinueSection() {
        val history = item("history", progress = .5f, lastReadAt = now - TimeUnit.DAYS.toMillis(1), onShelf = false)
        val result = ShelfState(history = listOf(history)).toBookshelfUiModel(BookshelfView.History, now)

        assertEquals(listOf("history"), result.history.map { it.workId })
        assertTrue(result.continueReading.isEmpty())
        assertEquals("上次阅读 1 天前", result.history.single().timeLabel)
    }

    @Test
    fun cardTimeUsesInjectedClockAtFormatterBoundaries() {
        val state = ShelfState(shelf = listOf(
            item("today", importedAt = now),
            item("month", importedAt = now - TimeUnit.DAYS.toMillis(30))
        ))

        val cards = state.toBookshelfUiModel(BookshelfView.Library, now).localShelf.associateBy { it.workId }

        assertEquals("导入于 今天", cards.getValue("today").timeLabel)
        assertEquals("导入于 1 个月前", cards.getValue("month").timeLabel)
    }

    private fun item(
        workId: String,
        progress: Float = 0f,
        lastReadAt: Long? = null,
        importedAt: Long? = null,
        onShelf: Boolean = true,
        hasLocalSource: Boolean = true
    ) = ShelfItem(
        workId = workId,
        title = "Title $workId",
        authorName = "Author $workId",
        modeLabel = "舞台",
        readingProgress = progress,
        lastReadAt = lastReadAt,
        importedAt = importedAt,
        activeRevisionId = null,
        onShelf = onShelf,
        hasLocalSource = hasLocalSource
    )
}
