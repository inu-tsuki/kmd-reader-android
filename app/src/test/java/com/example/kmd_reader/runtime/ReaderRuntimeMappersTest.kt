package com.example.kmd_reader.runtime

import com.example.kmd_reader.domain.model.WorkAssetManifest
import com.example.kmd_reader.domain.model.WorkAssetRef
import com.example.kmd_reader.domain.model.WorkFontAsset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R3-D4 回归：toReaderRuntimeAssetManifest 透传 fonts（修复审查报告指出的 fonts 丢弃根因）。
 * 远程作品空 fonts 默认值、本地作品 fonts 一一映射、assets 仍正确映射。
 */
class ReaderRuntimeMappersTest {

    @Test
    fun mapsFontsAndAssetsAndBaseUrl() {
        val manifest = WorkAssetManifest(
            baseUrl = "https://kmd-reader-assets.local/bid/",
            fonts = listOf(
                WorkFontAsset(family = "serif1", url = "assets/fonts/a.woff2", weight = "700", style = "italic"),
                WorkFontAsset(family = "sans1", url = "assets/fonts/b.ttf")
            ),
            assets = mapOf(
                "bg" to WorkAssetRef(url = "assets/bg.png", type = "image"),
                "sfx" to WorkAssetRef(url = "assets/sfx.wav")
            )
        )

        val reader = manifest.toReaderRuntimeAssetManifest()

        assertEquals("https://kmd-reader-assets.local/bid/", reader.baseUrl)
        assertEquals(2, reader.fonts.size)
        assertEquals("serif1", reader.fonts[0].family)
        assertEquals("assets/fonts/a.woff2", reader.fonts[0].url)
        assertEquals("700", reader.fonts[0].weight)
        assertEquals("italic", reader.fonts[0].style)
        assertEquals("sans1", reader.fonts[1].family)
        assertEquals("assets/fonts/b.ttf", reader.fonts[1].url)
        assertEquals(null, reader.fonts[1].weight)
        assertEquals(null, reader.fonts[1].style)
        assertEquals(2, reader.assets.size)
        assertEquals("assets/bg.png", reader.assets["bg"]?.url)
        assertEquals("image", reader.assets["bg"]?.type)
        assertEquals("assets/sfx.wav", reader.assets["sfx"]?.url)
        assertEquals(null, reader.assets["sfx"]?.type)
    }

    @Test
    fun emptyFontsAndAssetsDefaultToEmpty() {
        val manifest = WorkAssetManifest(baseUrl = null)

        val reader = manifest.toReaderRuntimeAssetManifest()

        assertEquals(null, reader.baseUrl)
        assertTrue(reader.fonts.isEmpty())
        assertTrue(reader.assets.isEmpty())
    }
}