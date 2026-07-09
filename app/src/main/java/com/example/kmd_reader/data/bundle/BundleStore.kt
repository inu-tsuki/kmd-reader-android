package com.example.kmd_reader.data.bundle

import java.io.File
import java.io.InputStream

/**
 * 应用私有 bundle store（spike §2.3 方案 C）。
 *
 * 目录布局：
 * ```
 * filesDir/bundles/<bundleId>/
 *   work.json              # bundle manifest
 *   scripts/main.kmd       # entry source（长期权威副本）
 *   assets/...             # asset 字节
 *   revisions/<revId>.kmd  # revision snapshot（R3-E 写入，D2 只建目录）
 * cacheDir/runtime-extract/<bundleId>/  # 当前播放的展开 cache（D4 消费）
 * ```
 *
 * 清理策略（spike §2.4）：
 * - 导入新作品：清 cacheDir/runtime-extract/ 旧 cache（单作品假设）
 * - 删作品：删 filesDir/bundles/<bundleId>/ + cacheDir + Room 行
 * - onTrimMemory：清 cacheDir/runtime-extract/ 全部（D3 或后续接线）
 * - 启动孤儿扫描：删 Room 索引里不存在的 cacheDir 孤儿（D3 或后续接线）
 */
class BundleStore(
    private val bundlesDir: File,
    private val cacheDir: File
) {
    // ── 路径解析 ──

    fun bundleDir(bundleId: String): File = File(bundlesDir, bundleId)

    fun workJsonFile(bundleId: String): File = File(bundleDir(bundleId), "work.json")

    fun entrySourceFile(bundleId: String): File {
        val manifest = readManifest(bundleId) ?: return File(bundleDir(bundleId), "scripts/main.kmd")
        return File(bundleDir(bundleId), manifest.entry)
    }

    fun revisionsDir(bundleId: String): File = File(bundleDir(bundleId), "revisions")

    fun runtimeCacheDir(bundleId: String): File = File(cacheDir, bundleId)

    // ── 安全 guard ──

    /**
     * 确保 bundleId 是安全的单路径段，且解析后的目录不逃逸 bundlesDir / cacheDir。
     * Defense-in-depth：KmdworkUnpacker 已在导入时校验 bundleId，这里在每次路径操作时再守一道。
     */
    private fun ensureSafeBundleId(bundleId: String) {
        if (bundleId.isEmpty() || bundleId.contains("/") || bundleId.contains("\\") ||
            bundleId.contains("..") || bundleId.contains(File.separator)) {
            throw IllegalArgumentException("unsafe bundleId: $bundleId")
        }
    }

    private fun ensureCanonicalContainance(dir: File, root: File) {
        if (!dir.canonicalPath.startsWith(root.canonicalPath + File.separator)) {
            throw IllegalArgumentException("path escapes store root: ${dir.canonicalPath}")
        }
    }

    // ── 导入 ──

    /**
     * 导入 `.kmdwork` zip 流：解包到 filesDir/bundles/<bundleId>/，返回 manifest + entry source。
     *
     * 失败时 [KmdworkUnpacker] 已清理半解文件，本方法不再额外处理。
     * 成功时清 cacheDir 旧 cache（spike §2.4 单作品假设）。
     */
    fun importBundle(zipInput: InputStream): ImportResult {
        // 先解包到临时目录，拿到 bundleId 后再 rename 到正式位置——
        // 避免 bundleId 未知时无法确定目标目录。
        val tempDir = File(bundlesDir, ".import-tmp-${System.currentTimeMillis()}")
        try {
            val unpacked = KmdworkUnpacker.unpack(zipInput, tempDir)
            // Defense-in-depth：unpacker 已校验 bundleId，这里再守一道 + canonical guard
            ensureSafeBundleId(unpacked.bundleId)
            val destDir = bundleDir(unpacked.bundleId)
            ensureCanonicalContainance(destDir, bundlesDir)
            // 同 bundleId 已存在则覆盖（重新导入）
            if (destDir.exists()) destDir.deleteRecursively()
            tempDir.renameTo(destDir)
            // 建 revisions 目录（R3-E 写入用，D2 只建空目录）
            revisionsDir(unpacked.bundleId).mkdirs()
            // 清旧 cache
            clearRuntimeCache()
            return ImportResult(
                manifest = unpacked.manifest,
                entrySource = unpacked.entrySource,
                contentHash = unpacked.contentHash
            )
        } catch (e: KmdworkUnpackException) {
            tempDir.deleteRecursively()
            throw e
        }
    }

    // ── 读取（D4 播放接线用，D2 先建接口）──

    fun readEntrySource(bundleId: String): String? {
        ensureSafeBundleId(bundleId)
        val file = entrySourceFile(bundleId)
        return if (file.exists()) file.readText() else null
    }

    fun readManifest(bundleId: String): BundleManifest? {
        ensureSafeBundleId(bundleId)
        val file = workJsonFile(bundleId)
        if (!file.exists()) return null
        return try {
            val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            json.decodeFromString(BundleManifest.serializer(), file.readText())
        } catch (e: Exception) {
            null
        }
    }

    fun bundleExists(bundleId: String): Boolean {
        ensureSafeBundleId(bundleId)
        return bundleDir(bundleId).exists()
    }

    // ── 删除（spike §2.4：删作品删三处）──

    fun deleteBundle(bundleId: String) {
        ensureSafeBundleId(bundleId)
        val bDir = bundleDir(bundleId)
        ensureCanonicalContainance(bDir, bundlesDir)
        val cDir = runtimeCacheDir(bundleId)
        ensureCanonicalContainance(cDir, cacheDir)
        bDir.deleteRecursively()
        cDir.deleteRecursively()
    }

    // ── 清理 cache ──

    fun clearRuntimeCache() {
        cacheDir.listFiles()?.forEach { it.deleteRecursively() }
    }

    fun clearRuntimeCache(bundleId: String) {
        ensureSafeBundleId(bundleId)
        val cDir = runtimeCacheDir(bundleId)
        ensureCanonicalContainance(cDir, cacheDir)
        cDir.deleteRecursively()
    }

    // ── 启动孤儿扫描（spike §2.4：删 Room 索引里不存在的 cacheDir 孤儿）──

    fun cleanupOrphanedCaches(validBundleIds: Set<String>) {
        cacheDir.listFiles()?.forEach { dir ->
            if (dir.isDirectory && dir.name !in validBundleIds) {
                dir.deleteRecursively()
            }
        }
    }
}

data class ImportResult(
    val manifest: BundleManifest,
    val entrySource: String,
    val contentHash: String
)