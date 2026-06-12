package com.example.kmd_reader.presentation

import com.example.kmd_reader.domain.model.IssueSeverity
import com.example.kmd_reader.domain.model.IssueSource
import com.example.kmd_reader.domain.model.KmdSourceRange
import com.example.kmd_reader.domain.model.KmdSourceSnapshot
import com.example.kmd_reader.domain.model.ScriptIssue
import com.example.kmd_reader.data.mock.MockWorks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KmdReaderReducerCompanionTest {
    @Test
    fun openReviewActivatesReviewCompanion() {
        val state = KmdReaderReducer.reduce(
            state = KmdReaderState(),
            action = KmdReaderAction.OpenReview
        )

        assertTrue(state.deskStack.isReviewOpen)
        assertEquals(ReaderCompanionType.Review, state.readerCompanion.active)
        assertEquals(ReaderChromeMode.Reviewing, state.readerChrome.mode)
        assertEquals(ReaderChromeVisibility.Visible, state.readerChrome.visibility)
    }

    @Test
    fun closeReviewClearsCompanionAndRestoresReadingChrome() {
        val reviewing = KmdReaderReducer.reduce(
            state = KmdReaderState(),
            action = KmdReaderAction.OpenReview
        )

        val state = KmdReaderReducer.reduce(
            state = reviewing,
            action = KmdReaderAction.CloseReview
        )

        assertFalse(state.deskStack.isReviewOpen)
        assertNull(state.readerCompanion.active)
        assertFalse(state.readerCompanion.expanded)
        assertEquals(ReaderChromeMode.Reading, state.readerChrome.mode)
    }

    @Test
    fun genericCompanionDoesNotOpenReviewDeskFlag() {
        val state = KmdReaderReducer.reduce(
            state = KmdReaderState(),
            action = KmdReaderAction.OpenReaderCompanion(ReaderCompanionType.WorkInfo)
        )

        assertFalse(state.deskStack.isReviewOpen)
        assertEquals(ReaderCompanionType.WorkInfo, state.readerCompanion.active)
        assertEquals(ReaderChromeMode.Reading, state.readerChrome.mode)
    }

    @Test
    fun issuesCompanionUsesReviewToolChromeAndDeskFlag() {
        val state = KmdReaderReducer.reduce(
            state = KmdReaderState(),
            action = KmdReaderAction.OpenReaderCompanion(ReaderCompanionType.Issues)
        )

        assertTrue(state.deskStack.isReviewOpen)
        assertEquals(ReaderCompanionType.Issues, state.readerCompanion.active)
        assertEquals(ReaderChromeMode.Reviewing, state.readerChrome.mode)

        val closed = KmdReaderReducer.reduce(
            state = state,
            action = KmdReaderAction.CloseReaderCompanion
        )

        assertFalse(closed.deskStack.isReviewOpen)
        assertNull(closed.readerCompanion.active)
        assertEquals(ReaderChromeMode.Reading, closed.readerChrome.mode)
    }

    @Test
    fun toggleCompanionExpandedOnlyWorksWhenActive() {
        val inactive = KmdReaderReducer.reduce(
            state = KmdReaderState(),
            action = KmdReaderAction.ToggleReaderCompanionExpanded
        )
        assertFalse(inactive.readerCompanion.expanded)

        val active = KmdReaderReducer.reduce(
            state = KmdReaderState(
                readerCompanion = ReaderCompanionState(active = ReaderCompanionType.Review)
            ),
            action = KmdReaderAction.ToggleReaderCompanionExpanded
        )
        assertTrue(active.readerCompanion.expanded)
    }

    @Test
    fun closeReaderCompanionClosesReviewFlagWhenReviewIsActive() {
        val state = KmdReaderReducer.reduce(
            state = KmdReaderState(
                deskStack = DeskStackState(isReviewOpen = true),
                readerCompanion = ReaderCompanionState(active = ReaderCompanionType.Review),
                readerChrome = ReaderChromeState(mode = ReaderChromeMode.Reviewing)
            ),
            action = KmdReaderAction.CloseReaderCompanion
        )

        assertFalse(state.deskStack.isReviewOpen)
        assertNull(state.readerCompanion.active)
        assertEquals(ReaderChromeMode.Reading, state.readerChrome.mode)
    }

    @Test
    fun closeNonReviewCompanionPreservesChromeMode() {
        val state = KmdReaderReducer.reduce(
            state = KmdReaderState(
                readerCompanion = ReaderCompanionState(active = ReaderCompanionType.Diagnostics),
                readerChrome = ReaderChromeState(mode = ReaderChromeMode.Error)
            ),
            action = KmdReaderAction.CloseReaderCompanion
        )

        assertNull(state.readerCompanion.active)
        assertEquals(ReaderChromeMode.Error, state.readerChrome.mode)
    }

    @Test
    fun selectIssueFocusesSourceRange() {
        val issue = ScriptIssue(
            id = "issue-line-3",
            workId = "glass-rail",
            severity = IssueSeverity.Warning,
            source = IssueSource.Runtime,
            location = "line 3",
            message = "检查第三行。",
            suggestion = "定位到源码。"
        )
        val state = KmdReaderReducer.reduce(
            state = KmdReaderState(
                deskStack = DeskStackState(currentWorkId = "glass-rail"),
                issuesByWorkId = mapOf("glass-rail" to listOf(issue)),
                sourceSnapshotsByWorkId = mapOf(
                    "glass-rail" to KmdSourceSnapshot.fromSource(
                        workId = "glass-rail",
                        revisionId = "rev-1",
                        content = "one\ntwo\nthree\nfour",
                        fetchedAtMillis = 1L
                    )
                )
            ),
            action = KmdReaderAction.SelectIssue("issue-line-3")
        )

        assertEquals("issue-line-3", state.issueFocus.selectedIssueId)
        assertEquals(3, state.issueFocus.focusedSourceRange?.startLine)
    }

    @Test
    fun startIssueDraftFromPlaybackUsesCurrentLineAndRevision() {
        val state = KmdReaderReducer.reduce(
            state = KmdReaderState(
                works = MockWorks.works,
                deskStack = DeskStackState(currentWorkId = "glass-rail"),
                sourceSnapshotsByWorkId = mapOf(
                    "glass-rail" to KmdSourceSnapshot.fromSource(
                        workId = "glass-rail",
                        revisionId = "rev-test",
                        content = "one\ntwo\nthree\nfour",
                        fetchedAtMillis = 1L
                    )
                ),
                readerSession = ReaderSessionState.Ready(
                    workId = "glass-rail",
                    progress = 0.5f,
                    isPlaying = true,
                    timeMs = 1200L,
                    durationMs = 2400L,
                    currentLine = 3,
                    currentMarkerId = "m3"
                )
            ),
            action = KmdReaderAction.StartIssueDraftFromPlayback
        )

        val draft = requireNotNull(state.issueFocus.issueDraft)
        assertEquals("glass-rail", draft.workId)
        assertEquals("rev-test", draft.revisionId)
        assertEquals(3, draft.sourceRange?.startLine)
        assertEquals(0.5f, requireNotNull(draft.playbackAnchor).progress ?: -1f, 0.001f)
        assertEquals(ReaderChromeMode.Reviewing, state.readerChrome.mode)
    }

    @Test
    fun closeAndReopenIssueUpdatesLocalStatus() {
        val closed = KmdReaderReducer.reduce(
            state = KmdReaderState(),
            action = KmdReaderAction.CloseIssue("issue-1")
        )

        assertEquals(IssueLocalStatus.Closed, closed.issueFocus.statusFor("issue-1").status)

        val reopened = KmdReaderReducer.reduce(
            state = closed,
            action = KmdReaderAction.ReopenIssue("issue-1")
        )

        assertEquals(IssueLocalStatus.Open, reopened.issueFocus.statusFor("issue-1").status)
    }

    @Test
    fun selectSourceLineKeepsIssueFocusAndMarksSelectedLine() {
        val state = KmdReaderReducer.reduce(
            state = KmdReaderState(
                issueFocus = IssueFocusState(
                    selectedIssueId = "issue-1",
                    focusedSourceRange = KmdSourceRange(8)
                )
            ),
            action = KmdReaderAction.SelectSourceLine(11)
        )

        assertEquals("issue-1", state.issueFocus.selectedIssueId)
        assertEquals(8, state.issueFocus.focusedSourceRange?.startLine)
        assertEquals(11, state.issueFocus.selectedSourceLine)
        assertEquals(ReaderChromeMode.Reviewing, state.readerChrome.mode)
    }
}
