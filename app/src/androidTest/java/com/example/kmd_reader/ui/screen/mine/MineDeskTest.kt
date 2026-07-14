package com.example.kmd_reader.ui.screen.mine

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.kmd_reader.presentation.ImportState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MineDeskTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun toolbarAndCardActionsInvokeOnlyTheirOwnCallbacks() {
        var imports = 0
        var settings = 0
        var details = 0
        var continues = 0
        composeRule.setContent {
            MineDesk(
                shelfState = ShelfState(shelf = listOf(item(progress = .4f, lastReadAt = 1L))),
                importState = ImportState.Idle,
                selectedView = BookshelfView.Library,
                nowMillis = 2L,
                onBookshelfViewChange = {},
                onOpenImport = { imports++ },
                onOpenWork = { details++ },
                onContinueReading = { continues++ },
                onOpenSettings = { settings++ }
            )
        }

        composeRule.onNodeWithContentDescription("导入作品").performClick()
        composeRule.onNodeWithContentDescription("打开阅读设置").performClick()
        composeRule.onNodeWithTag("continue-continue-work").performClick()
        composeRule.onNodeWithTag("details-continue-work").performClick()

        assertEquals(1, imports)
        assertEquals(1, settings)
        assertEquals(1, continues)
        assertEquals(1, details)
    }

    @Test
    fun cardMetadataOpensDetailsExactlyOnce() {
        var details = 0
        composeRule.setContent {
            MineDesk(
                shelfState = ShelfState(shelf = listOf(item())),
                importState = ImportState.Idle,
                selectedView = BookshelfView.Library,
                nowMillis = 0L,
                onBookshelfViewChange = {},
                onOpenImport = {},
                onOpenWork = { details++ },
                onContinueReading = {},
                onOpenSettings = {}
            )
        }

        composeRule.onNodeWithText("A very long work title that must remain reachable on narrow screens").performClick()

        assertEquals(1, details)
    }

    @Test
    fun segmentedControlRecomposesHistoryWithoutContinueContent() {
        val state = ShelfState(
            shelf = listOf(item(workId = "shelf", progress = .4f, lastReadAt = 1L)),
            history = listOf(item(workId = "history", progress = .4f, lastReadAt = 1L, onShelf = false))
        )
        composeRule.setContent {
            var selected by remember { mutableStateOf(BookshelfView.Library) }
            MineDesk(
                shelfState = state,
                importState = ImportState.Idle,
                selectedView = selected,
                nowMillis = 2L,
                onBookshelfViewChange = { selected = it },
                onOpenImport = {},
                onOpenWork = {},
                onContinueReading = {},
                onOpenSettings = {}
            )
        }

        composeRule.onNodeWithTag("bookshelf-view-History").performClick()
        composeRule.onNodeWithText("阅读历史").assertIsDisplayed()
        composeRule.onAllNodesWithTag("continue-continue-shelf").assertCountEquals(0)

        composeRule.onNodeWithTag("bookshelf-view-Library").performClick()
        composeRule.onNodeWithTag("continue-continue-shelf").assertIsDisplayed()
    }

    @Test
    fun historyOnlyContinueRemainsVisibleInLibrary() {
        var continues = 0
        composeRule.setContent {
            MineDesk(
                shelfState = ShelfState(
                    history = listOf(item(progress = .4f, lastReadAt = 1L, onShelf = false))
                ),
                importState = ImportState.Idle,
                selectedView = BookshelfView.Library,
                nowMillis = 2L,
                onBookshelfViewChange = {},
                onOpenImport = {},
                onOpenWork = {},
                onContinueReading = { continues++ },
                onOpenSettings = {}
            )
        }

        composeRule.onNodeWithTag("continue-continue-work").assertIsDisplayed()
        composeRule.onAllNodesWithText("还没有作品").assertCountEquals(0)
        composeRule.onNodeWithTag("continue-continue-work").performClick()

        assertEquals(1, continues)
    }

    @Test
    fun idleImportStateHasNoVisualFootprint() {
        composeRule.setContent {
            MineDesk(
                shelfState = ShelfState(),
                importState = ImportState.Idle,
                selectedView = BookshelfView.Library,
                nowMillis = 0L,
                onBookshelfViewChange = {},
                onOpenImport = {},
                onOpenWork = {},
                onContinueReading = {},
                onOpenSettings = {}
            )
        }
        composeRule.onAllNodesWithText("正在导入作品…").assertCountEquals(0)
    }

    @Test
    fun importingStateIsVisible() {
        composeRule.setContent {
            MineDesk(
                shelfState = ShelfState(),
                importState = ImportState.Importing,
                selectedView = BookshelfView.Library,
                nowMillis = 0L,
                onBookshelfViewChange = {},
                onOpenImport = {},
                onOpenWork = {},
                onContinueReading = {},
                onOpenSettings = {}
            )
        }

        composeRule.onNodeWithText("正在导入作品…").assertIsDisplayed()
    }

    @Test
    fun failedImportShowsRetryAction() {
        var imports = 0
        composeRule.setContent {
            MineDesk(
                shelfState = ShelfState(),
                importState = ImportState.Failed("无效文件"),
                selectedView = BookshelfView.Library,
                nowMillis = 0L,
                onBookshelfViewChange = {},
                onOpenImport = { imports++ },
                onOpenWork = {},
                onContinueReading = {},
                onOpenSettings = {}
            )
        }
        composeRule.onNodeWithText("导入失败").assertIsDisplayed()
        composeRule.onNodeWithText("重新选择文件").performClick()
        assertEquals(1, imports)
    }

    private fun item(
        workId: String = "work",
        progress: Float = 0f,
        lastReadAt: Long? = null,
        onShelf: Boolean = true
    ) = ShelfItem(
        workId = workId,
        title = "A very long work title that must remain reachable on narrow screens",
        authorName = "A very long author name that must remain readable",
        modeLabel = "舞台",
        readingProgress = progress,
        lastReadAt = lastReadAt,
        importedAt = null,
        activeRevisionId = null,
        onShelf = onShelf,
        hasLocalSource = true
    )
}
