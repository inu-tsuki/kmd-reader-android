package com.example.kmd_reader.data.bundle

import android.content.Context
import java.io.File

object BundleStoreModule {
    // 展开缓存的子目录名。BundleStore 的 cacheDir 字段即 context.cacheDir/此目录；
    // openBundleAsset（ReaderRuntimeHost）读同一层，两处必须引用同一常量避免路径错位（D4 审查 High）。
    const val RUNTIME_EXTRACT_DIR = "runtime-extract"

    fun create(context: Context): BundleStore {
        val bundlesDir = File(context.filesDir, "bundles").also { it.mkdirs() }
        val cacheDir = File(context.cacheDir, RUNTIME_EXTRACT_DIR).also { it.mkdirs() }
        return BundleStore(bundlesDir, cacheDir)
    }
}