package com.example.kmd_reader.presentation

import org.junit.Assert.assertEquals
import org.junit.Test

class KmdReaderReducerChromeTest {
    @Test
    fun openReaderShowsReadingChrome() {
        val state = KmdReaderReducer.reduce(
            state = KmdReaderState(
                readerChrome = ReaderChromeState(
                    visibility = ReaderChromeVisibility.Hidden,
                    mode = ReaderChromeMode.Reviewing
                )
            ),
            action = KmdReaderAction.OpenReader
        )

        assertEquals(ReaderChromeVisibility.Visible, state.readerChrome.visibility)
        assertEquals(ReaderChromeMode.Reading, state.readerChrome.mode)
    }

    @Test
    fun dimReaderChromeKeepsPinnedChromeVisible() {
        val state = KmdReaderReducer.reduce(
            state = KmdReaderState(
                readerChrome = ReaderChromeState(isPinned = true)
            ),
            action = KmdReaderAction.DimReaderChrome
        )

        assertEquals(ReaderChromeVisibility.Visible, state.readerChrome.visibility)
    }

    @Test
    fun openAndCloseReviewSwitchChromeMode() {
        val reviewing = KmdReaderReducer.reduce(
            state = KmdReaderState(),
            action = KmdReaderAction.OpenReview
        )

        assertEquals(ReaderChromeVisibility.Visible, reviewing.readerChrome.visibility)
        assertEquals(ReaderChromeMode.Reviewing, reviewing.readerChrome.mode)

        val reading = KmdReaderReducer.reduce(
            state = reviewing,
            action = KmdReaderAction.CloseReview
        )

        assertEquals(ReaderChromeVisibility.Visible, reading.readerChrome.visibility)
        assertEquals(ReaderChromeMode.Reading, reading.readerChrome.mode)
    }

    @Test
    fun toggleReaderChromeRestoresFromDimmed() {
        val state = KmdReaderReducer.reduce(
            state = KmdReaderState(
                readerChrome = ReaderChromeState(
                    visibility = ReaderChromeVisibility.Dimmed,
                    lastInteractionAtMillis = 1L
                )
            ),
            action = KmdReaderAction.ToggleReaderChrome(atMillis = 2L)
        )

        assertEquals(ReaderChromeVisibility.Visible, state.readerChrome.visibility)
        assertEquals(2L, state.readerChrome.lastInteractionAtMillis)
    }

    @Test
    fun toggleReaderChromeRestoresFromHidden() {
        val state = KmdReaderReducer.reduce(
            state = KmdReaderState(
                readerChrome = ReaderChromeState(
                    visibility = ReaderChromeVisibility.Hidden,
                    lastInteractionAtMillis = 1L
                )
            ),
            action = KmdReaderAction.ToggleReaderChrome(atMillis = 2L)
        )

        assertEquals(ReaderChromeVisibility.Visible, state.readerChrome.visibility)
        assertEquals(2L, state.readerChrome.lastInteractionAtMillis)
    }

    @Test
    fun retryReaderRuntimeRestartsHostAndShowsChrome() {
        val state = KmdReaderReducer.reduce(
            state = KmdReaderState(
                readerHostRestartToken = 4,
                readerChrome = ReaderChromeState(
                    visibility = ReaderChromeVisibility.Dimmed,
                    mode = ReaderChromeMode.Error
                )
            ),
            action = KmdReaderAction.RetryReaderRuntime
        )

        assertEquals(5, state.readerHostRestartToken)
        assertEquals(ReaderChromeVisibility.Visible, state.readerChrome.visibility)
        assertEquals(ReaderChromeMode.Reading, state.readerChrome.mode)
    }
}
