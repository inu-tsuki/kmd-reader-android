package com.example.kmd_reader.data.bundle

import android.content.Context
import java.io.File

object BundleStoreModule {
    fun create(context: Context): BundleStore {
        val bundlesDir = File(context.filesDir, "bundles").also { it.mkdirs() }
        val cacheDir = File(context.cacheDir, "runtime-extract").also { it.mkdirs() }
        return BundleStore(bundlesDir, cacheDir)
    }
}