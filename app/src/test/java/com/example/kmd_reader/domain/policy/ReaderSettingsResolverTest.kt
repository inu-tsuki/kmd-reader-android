package com.example.kmd_reader.domain.policy

import com.example.kmd_reader.data.mock.MockWorks
import com.example.kmd_reader.data.preferences.ReaderPreferences
import com.example.kmd_reader.domain.model.KmdPresentationHints
import com.example.kmd_reader.domain.model.PresentationMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderSettingsResolverTest {
    @Test
    fun viewportUpdatesKeepUserPreferences() {
        val viewport = ReaderViewportPolicy.resolve(
            work = MockWorks.works.first { it.id == "glass-rail" },
            hostWidthPx = 1080,
            hostHeightPx = 2274,
            sourceHints = KmdPresentationHints(presentationMode = PresentationMode.Stage)
        )

        val settings = ReaderSettingsResolver.resolve(
            viewport,
            ReaderPreferences(fontScale = 1.25f, reducedMotion = true)
        )

        assertEquals("stage", settings.presentationMode)
        assertEquals(viewport.runtimeViewport, settings.viewport)
        assertEquals(1.25f, settings.fontScale, 0f)
        assertTrue(settings.reducedMotion)
    }

    @Test
    fun preferenceUpdatesKeepViewportFacts() {
        val viewport = ReaderViewportPolicy.resolve(
            work = MockWorks.works.first { it.id == "rain-city" },
            hostWidthPx = 900,
            hostHeightPx = 1800
        )

        val defaultSettings = ReaderSettingsResolver.resolve(viewport, ReaderPreferences())
        val changedSettings = ReaderSettingsResolver.resolve(
            viewport,
            ReaderPreferences(fontScale = 0.85f, reducedMotion = true)
        )

        assertEquals(defaultSettings.viewport, changedSettings.viewport)
        assertEquals(defaultSettings.presentationMode, changedSettings.presentationMode)
        assertEquals(0.85f, changedSettings.fontScale, 0f)
        assertTrue(changedSettings.reducedMotion)
        assertFalse(defaultSettings.reducedMotion)
    }
}
