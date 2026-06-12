package com.example.kmd_reader.domain.kmd

import com.example.kmd_reader.domain.model.OrientationHint
import com.example.kmd_reader.domain.model.PresentationMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KmdSourceMetadataParserTest {
    @Test
    fun parsesDesignViewportFromFrontMatter() {
        val hints = KmdSourceMetadataParser.parsePresentationHints(
            """
            ---
            title: Glass Rail
            mode: stage
            designWidth: 1920
            designHeight: 1080
            ---

            玻璃铁轨反射出远处的城市灯火。
            """.trimIndent()
        )

        assertEquals(PresentationMode.Stage, hints.presentationMode)
        assertEquals(1920, hints.designWidth)
        assertEquals(1080, hints.designHeight)
        assertEquals(OrientationHint.Landscape, hints.orientationHint)
        assertEquals("16:9", hints.aspectRatio)
        assertTrue(hints.hasDesignViewport)
    }

    @Test
    fun returnsEmptyHintsWithoutFrontMatter() {
        val hints = KmdSourceMetadataParser.parsePresentationHints("没有 frontmatter 的 KMD。")

        assertEquals(null, hints.presentationMode)
        assertEquals(null, hints.designWidth)
        assertEquals(null, hints.designHeight)
        assertFalse(hints.hasAnyHint)
    }
}
