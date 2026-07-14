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
 * 当前只在导入/展开边界维护 runtime cache。作品删除、Room/bundle/revision 级联、
 * onTrimMemory 与孤儿扫描仍属于 Post-R3 数据生命周期设计，不由本类单方面执行。
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
            // renameTo 可能失败（跨文件系统/权限），检查返回值——
            // 失败时 destDir 已被删但真正内容还留在 tempDir，必须清理 + 抛异常。
            if (!tempDir.renameTo(destDir)) {
                // fallback：手动复制（同文件系统下 renameTo 几乎不会失败，这是兜底）
                try {
                    tempDir.copyRecursively(destDir)
                    tempDir.deleteRecursively()
                } catch (copyEx: Exception) {
                    destDir.deleteRecursively()
                    throw IllegalStateException("failed to move bundle to $destDir", copyEx)
                }
            }
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
        } catch (e: IllegalStateException) {
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

    // ── R3-D4 展开：播放前把 bundle 的 assets/scripts 复制到 cacheDir/runtime-extract/<bundleId>/，
    //    供 shouldInterceptRequest 的 kmd-reader-assets.local host 服务（spike §4.3/§4.5 第一步）。
    //    idempotent：cacheDir 已存在且非空直接返回。失败清理半成品 cache 抛错。
    //    不复制 work.json（runtime 不消费 manifest 字节）/revisions/（R3-E 范畴）。
    //    spike §2.4 单作品假设：D4 不主动清旧 cache（clearRuntimeCache 已存在，留导入/后续接线）。
    //    展开缓存子目录名 = BundleStoreModule.RUNTIME_EXTRACT_DIR（ensureExtracted 写入与
    //    ReaderRuntimeHost.openBundleAsset 读取引用同一常量，避免路径错位稳定 miss）。
    fun ensureExtracted(bundleId: String): File {
        ensureSafeBundleId(bundleId)
        val sourceDir = bundleDir(bundleId)
        ensureCanonicalContainance(sourceDir, bundlesDir)
        val targetDir = runtimeCacheDir(bundleId)
        ensureCanonicalContainance(targetDir, cacheDir)
        if (targetDir.exists() && targetDir.isDirectory && (targetDir.listFiles()?.isNotEmpty() == true)) {
            return targetDir
        }
        if (!sourceDir.exists()) {
            throw IllegalStateException("bundle 不存在：$bundleId")
        }
        try {
            targetDir.mkdirs()
            // 只复制 runtime 消费的子树：assets/ + scripts/。
            listOf("assets", "scripts").forEach { sub ->
                val src = File(sourceDir, sub)
                if (src.exists() && src.isDirectory) {
                    src.copyRecursively(File(targetDir, sub))
                }
            }
        } catch (e: Exception) {
            // 半解失败：清掉不完整 cache，避免后续播放读到残缺 assets
            targetDir.deleteRecursively()
            throw e
        }
        return targetDir
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
