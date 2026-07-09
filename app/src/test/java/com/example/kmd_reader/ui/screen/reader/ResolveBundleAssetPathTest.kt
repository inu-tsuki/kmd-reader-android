package com.example.kmd_reader.ui.screen.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * R3-D4 回归：resolveBundleAssetPath 路径解析 + 穿越 guard。
 *
 * 局限（诚实记录）：此测试只验证路径解析纯函数，不覆盖 WebView shouldInterceptRequest 的实际
 * 拦截行为（需 WebView/Context，属真机/Robolectric 验证范畴）。openBundleAsset 的文件读取
 * 依赖 Context.cacheDir，同样不在此单测覆盖。
 */
class ResolveBundleAssetPathTest {

    @Test
    fun parsesBundleIdAndRest() {
        val result = resolveBundleAssetPath("/bundle-123/assets/fonts/x.woff2")
        assertEquals("bundle-123" to "assets/fonts/x.woff2", result)
    }

    @Test
    fun parsesWhenRestHasNoSubdirectory() {
        val result = resolveBundleAssetPath("/bundle-123/main.kmd")
        assertEquals("bundle-123" to "main.kmd", result)
    }

    @Test
    fun returnsNullForNullPath() {
        assertNull(resolveBundleAssetPath(null))
    }

    @Test
    fun returnsNullForBlankPath() {
        assertNull(resolveBundleAssetPath("/"))
        assertNull(resolveBundleAssetPath(""))
    }

    @Test
    fun returnsNullWhenRestIsBlank() {
        // 只有 bundleId、无 rest
        assertNull(resolveBundleAssetPath("/bundle-123"))
        assertNull(resolveBundleAssetPath("/bundle-123/"))
    }

    @Test
    fun rejectsTraversalInRest() {
        // rest 含 .. → 拒绝（防 cacheDir 逃逸）
        assertNull(resolveBundleAssetPath("/bundle-123/assets/../../etc/passwd"))
        assertNull(resolveBundleAssetPath("/bundle-123/.."))
    }

    @Test
    fun rejectsTraversalInBundleId() {
        // bundleId 含 .. → 拒绝
        assertNull(resolveBundleAssetPath("/../cache/sensitive"))
        assertNull(resolveBundleAssetPath("/.."))
    }
}