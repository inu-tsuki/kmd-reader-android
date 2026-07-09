package com.example.kmd_reader.data.bundle

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * R3-D4 回归：BundleStore.ensureExtracted 展开层。
 *
 * 纯 JVM（BundleStore 只用 java.io.File，无 Android 依赖）；用 tmp dir 模拟 filesDir/cacheDir。
 * 验证：idempotent、assets/scripts 复制正确、不复制 work.json、半解失败清理 cache。
 */
class BundleStoreEnsureExtractedTest {

    private lateinit var bundlesDir: File
    private lateinit var cacheDir: File
    private lateinit var store: BundleStore

    @Before
    fun setUp() {
        bundlesDir = Files.createTempDirectory("bundles").toFile()
        cacheDir = Files.createTempDirectory("cache").toFile()
        store = BundleStore(bundlesDir = bundlesDir, cacheDir = cacheDir)
    }

    @After
    fun tearDown() {
        bundlesDir.deleteRecursively()
        cacheDir.deleteRecursively()
    }

    @Test
    fun extractsAssetsAndScripts() {
        val bid = "bundle-1"
        // 构造 filesDir/bundles/bundle-1/ 内容
        val srcDir = File(bundlesDir, bid).apply { mkdirs() }
        File(srcDir, "work.json").writeText("{}") // 不应被复制
        File(srcDir, "scripts").mkdirs()
        File(srcDir, "scripts/main.kmd").writeText("title: test")
        File(srcDir, "assets").mkdirs()
        File(srcDir, "assets/font.woff2").writeBytes(byteArrayOf(0x00, 0x01))
        File(srcDir, "revisions").mkdirs() // 不应被复制
        File(srcDir, "revisions/rev-1.kmd").writeText("rev")

        val extracted = store.ensureExtracted(bid)

        assertTrue(extracted.exists())
        assertTrue(File(extracted, "scripts/main.kmd").exists())
        assertEquals("title: test", File(extracted, "scripts/main.kmd").readText())
        assertTrue(File(extracted, "assets/font.woff2").exists())
        // work.json 不复制
        assertTrue(!File(extracted, "work.json").exists())
        // revisions/ 不复制
        assertTrue(!File(extracted, "revisions").exists())
    }

    @Test
    fun idempotentWhenCacheAlreadyExtracted() {
        val bid = "bundle-2"
        val srcDir = File(bundlesDir, bid).apply { mkdirs() }
        File(srcDir, "scripts").mkdirs()
        File(srcDir, "scripts/main.kmd").writeText("v1")
        File(srcDir, "assets").mkdirs()
        File(srcDir, "assets/a.png").writeBytes(byteArrayOf(0xFF.toByte()))

        val first = store.ensureExtracted(bid)
        // 修改源，验证 idempotent 不重抽（cache 非空直接返回，源改动不反映）
        File(srcDir, "scripts/main.kmd").writeText("v2")
        val second = store.ensureExtracted(bid)

        assertEquals(first, second)
        // 仍是 v1（未重新提取）
        assertEquals("v1", File(second, "scripts/main.kmd").readText())
    }

    @Test
    fun emptyBundleDirExtractsEmptyCache() {
        val bid = "bundle-3"
        File(bundlesDir, bid).mkdirs() // 空 bundle 目录

        val extracted = store.ensureExtracted(bid)

        assertTrue(extracted.exists())
        assertTrue(extracted.isDirectory)
    }

    @Test(expected = IllegalStateException::class)
    fun throwsWhenBundleSourceMissing() {
        store.ensureExtracted("nonexistent-bundle")
    }
}