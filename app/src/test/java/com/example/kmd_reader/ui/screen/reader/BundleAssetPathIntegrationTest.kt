package com.example.kmd_reader.ui.screen.reader

import com.example.kmd_reader.data.bundle.BundleStore
import com.example.kmd_reader.data.bundle.BundleStoreModule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * R3-D4 审查 High 修复回归：把"展开写入位置"（BundleStore.ensureExtracted）和
 * "WebView 读取位置"（resolveBundleAssetFile → openBundleAsset）接成同一条链。
 *
 * 之前 openBundleAsset 用 cacheDir/<bundleId> 缺 runtime-extract 一层，导致稳定 miss。
 * 此测试用真实 BundleStore 写入 + resolveBundleAssetFile 解析，钉死两端路径必须对齐。
 */
class BundleAssetPathIntegrationTest {

    @Test
    fun ensureExtractedWriteAlignsWithResolveRead() {
        // 用与 BundleStoreModule.create 相同的 cache 布局：filesDir/bundles + cacheDir/runtime-extract
        val filesDir = Files.createTempDirectory("bap-files").toFile()
        val cacheRoot = Files.createTempDirectory("bap-cache").toFile()
        val store = BundleStore(
            bundlesDir = File(filesDir, "bundles").apply { mkdirs() },
            cacheDir = File(cacheRoot, BundleStoreModule.RUNTIME_EXTRACT_DIR).apply { mkdirs() }
        )
        val bid = "bundle-int"
        // 构造 bundle 源内容（bundleDir 返回 File，直接 apply）
        store.bundleDir(bid).apply {
            mkdirs()
            File(this, "scripts").mkdirs()
            File(this, "scripts/main.kmd").writeText("title: integration")
            File(this, "assets").mkdirs()
            File(this, "assets/font.woff2").writeBytes(byteArrayOf(0x00, 0x01, 0x02))
        }

        // 展开写入
        val extracted = store.ensureExtracted(bid)
        assertNotNull(extracted)

        // WebView 读取端解析的路径必须指向同一文件
        val fontFile = resolveBundleAssetFile(cacheRoot, bid, "assets/font.woff2")
        assertNotNull("resolveBundleAssetFile 应解析出字体文件路径", fontFile)
        assertEquals(
            File(extracted, "assets/font.woff2").canonicalFile,
            fontFile
        )
        // 文件确实存在且内容正确
        assertEquals(true, fontFile!!.isFile)
        assertEquals("font.woff2", fontFile.name)

        val scriptFile = resolveBundleAssetFile(cacheRoot, bid, "scripts/main.kmd")
        assertNotNull(scriptFile)
        assertEquals("title: integration", scriptFile!!.readText())
    }

    @Test
    fun resolveReturnsNullForTraversal() {
        val cacheRoot = Files.createTempDirectory("bap-trav").toFile()
        // 未展开任何内容，仅验证穿越 guard：rest 含 .. 应返回 null
        assertNull(resolveBundleAssetFile(cacheRoot, "bid", "../escape"))
        assertNull(resolveBundleAssetFile(cacheRoot, "bid", "assets/../../etc"))
    }
}