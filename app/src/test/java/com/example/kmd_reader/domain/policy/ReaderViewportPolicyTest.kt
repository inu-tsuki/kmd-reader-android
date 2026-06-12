package com.example.kmd_reader.domain.policy

import com.example.kmd_reader.data.mock.MockWorks
import com.example.kmd_reader.domain.model.KmdPresentationHints
import com.example.kmd_reader.domain.model.PresentationMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderViewportPolicyTest {
    @Test
    fun landscapeStageUsesDeclaredDesignViewportInPortraitHost() {
        val work = MockWorks.works.first { it.id == "glass-rail" }

        val state = ReaderViewportPolicy.resolve(
            work = work,
            hostWidthPx = 1080,
            hostHeightPx = 2274,
            sourceHints = KmdPresentationHints(
                presentationMode = PresentationMode.Stage,
                designWidth = 1920,
                designHeight = 1080
            )
        )

        assertEquals(1920, state.runtimeViewport.width)
        assertEquals(1080, state.runtimeViewport.height)
        assertEquals("16:9", state.aspectRatio)
        assertTrue(state.letterboxed)
        assertEquals("stage", ReaderViewportPolicy.settingsFor(state).presentationMode)
    }

    @Test
    fun landscapeStageFallsBackToAspectRatioViewportWithoutSourceDesign() {
        val work = MockWorks.works.first { it.id == "glass-rail" }

        val state = ReaderViewportPolicy.resolve(
            work = work,
            hostWidthPx = 1080,
            hostHeightPx = 2274
        )

        assertEquals(960, state.runtimeViewport.width)
        assertEquals(540, state.runtimeViewport.height)
        assertTrue(state.letterboxed)
    }

    @Test
    fun scrollWorkIgnoresDeclaredDesignViewport() {
        val work = MockWorks.works.first { it.id == "rain-city" }

        val state = ReaderViewportPolicy.resolve(
            work = work,
            hostWidthPx = 1080,
            hostHeightPx = 2274,
            sourceHints = KmdPresentationHints(
                presentationMode = PresentationMode.Scroll,
                designWidth = 1080,
                designHeight = 1920
            )
        )

        assertEquals(405, state.runtimeViewport.width)
        assertEquals(720, state.runtimeViewport.height)
        assertFalse(state.letterboxed)
        assertEquals("scroll", ReaderViewportPolicy.settingsFor(state).presentationMode)
    }

    @Test
    fun portraitScrollKeepsVerticalRuntimeViewport() {
        val work = MockWorks.works.first { it.id == "rain-city" }

        val state = ReaderViewportPolicy.resolve(
            work = work,
            hostWidthPx = 1080,
            hostHeightPx = 2274
        )

        assertEquals(405, state.runtimeViewport.width)
        assertEquals(720, state.runtimeViewport.height)
        assertFalse(state.letterboxed)
        assertEquals("scroll", ReaderViewportPolicy.settingsFor(state).presentationMode)
    }

    @Test
    fun pagedAdaptiveWorkKeepsPortraitViewport() {
        val work = MockWorks.works.first { it.id == "star-manual" }

        val portrait = ReaderViewportPolicy.resolve(
            work = work,
            hostWidthPx = 1080,
            hostHeightPx = 2274
        )
        val landscape = ReaderViewportPolicy.resolve(
            work = work,
            hostWidthPx = 2274,
            hostHeightPx = 1080
        )

        assertEquals(390, portrait.runtimeViewport.width)
        assertEquals(720, portrait.runtimeViewport.height)
        assertEquals(390, landscape.runtimeViewport.width)
        assertEquals(720, landscape.runtimeViewport.height)
    }
}
