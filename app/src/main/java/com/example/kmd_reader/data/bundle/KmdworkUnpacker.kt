package com.example.kmd_reader.data.bundle

import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * `.kmdwork` zip 解包器。流式解压 + Z1–Z9 安全防护 + manifest 引用闭合 + contentHash 校验。
 *
 * 规范来源：
 * - spike `r3-d-storage-spike.md` §5.2 防护清单 + §5.3 参考实现骨架
 * - `work-bundle-format.md` §5 安全清单
 *
 * 流程：解压全部 entry → 解析 work.json → Z8 引用闭合 → Z5 contentHash → 返回结果。
 * 任何步骤失败抛 [KmdworkUnpackException]，并清理已写入的半解文件。
 */
object KmdworkUnpacker {

    // spike §5.2 上限常量
    internal const val MAX_TOTAL_UNCOMPRESSED = 50L * 1024 * 1024  // 50MB
    internal const val MAX_ENTRY_BYTES = 10L * 1024 * 1024          // 10MB
    internal const val MAX_ENTRIES = 1024

    private const val WORK_JSON = "work.json"

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 解压 `.kmdwork` zip 流到 [targetDir]，校验 manifest + contentHash，返回结果。
     *
     * @param input zip 字节流（SAF 复制的 `.kmdwork`，untrusted）
     * @param targetDir 解压目标目录（filesDir/bundles/<bundleId>/）
     * @return 解压结果（含 manifest、entry source、contentHash）
     * @throws KmdworkUnpackException 任何校验失败；抛出前已清理 targetDir 下半解文件。
     */
    fun unpack(input: InputStream, targetDir: File): UnpackResult {
        targetDir.mkdirs()
        val extractedPaths = mutableSetOf<String>()
        try {
            extractAll(input, targetDir, extractedPaths)

            // Z8: work.json 必须存在
            if (WORK_JSON !in extractedPaths) {
                if (extractedPaths.isEmpty()) throw KmdworkUnpackException.EmptyBundle()
                throw KmdworkUnpackException.ManifestMissing()
            }

            // 解析 work.json
            val manifest = parseManifest(File(targetDir, WORK_JSON))

            // Z8: entry script 必须存在
            val entryPath = manifest.entry
            if (entryPath !in extractedPaths) {
                throw KmdworkUnpackException.EntryMissing(entryPath)
            }

            // Z8: assetManifest 引用的 asset 必须都在解压结果中
            manifest.assetManifest?.let { am ->
                am.fonts.forEach { font ->
                    if (font.url !in extractedPaths) {
                        throw KmdworkUnpackException.AssetReferenceUnclosed(font.url)
                    }
                }
                am.assets.values.forEach { ref ->
                    if (ref.url !in extractedPaths) {
                        throw KmdworkUnpackException.AssetReferenceUnclosed(ref.url)
                    }
                }
            }

            // 读取 entry source
            val entryFile = File(targetDir, entryPath)
            val entrySource = entryFile.readText()

            // Z5: contentHash 校验（exportRevisionId 对应的 revision contentHash）
            val contentHash = sha256Hex(entryFile.readBytes())
            manifest.exportRevisionId?.let { exportId ->
                val exportRev = manifest.revisions.find { it.id == exportId }
                if (exportRev != null && exportRev.contentHash != contentHash) {
                    throw KmdworkUnpackException.ContentHashMismatch(
                        expected = exportRev.contentHash, actual = contentHash
                    )
                }
            }

            return UnpackResult(
                bundleId = manifest.bundleId,
                manifest = manifest,
                entryPath = entryPath,
                entrySource = entrySource,
                extractedPaths = extractedPaths.toSet(),
                contentHash = contentHash
            )
        } catch (e: KmdworkUnpackException) {
            cleanup(targetDir)
            throw e
        } catch (e: Exception) {
            cleanup(targetDir)
            throw KmdworkUnpackException.ManifestInvalid(e)
        }
    }

    // ── Z1–Z4/Z6/Z9: 流式解压 + 安全防护 ──

    private fun extractAll(input: InputStream, targetDir: File, extractedPaths: MutableSet<String>) {
        var total = 0L
        var count = 0
        ZipInputStream(input).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                try {
                    if (entry.isDirectory) continue
                    count++
                    if (count > MAX_ENTRIES) throw KmdworkUnpackException.TooManyEntries()

                    // Z1: zip slip（路径穿越）。API 30+ ZipPathValidator 是 defense-in-depth，
                    // API 28/29 手动 canonicalPath 检查（spike §5.2 Z1）。
                    val outFile = File(targetDir, entry.name)
                    val targetCanonical = targetDir.canonicalPath + File.separator
                    if (!outFile.canonicalPath.startsWith(targetCanonical)) {
                        throw KmdworkUnpackException.ZipSlip(entry.name)
                    }

                    // Z6: 重复路径
                    if (!extractedPaths.add(entry.name)) {
                        throw KmdworkUnpackException.DuplicatePath(entry.name)
                    }

                    // Z3: 单 entry 大小上限（entry.size 来自 zip local header）
                    val entrySize = entry.size
                    if (entrySize > MAX_ENTRY_BYTES) {
                        throw KmdworkUnpackException.EntryTooBig(entry.name, entrySize)
                    }

                    // Z2: 累计大小上限（边写边累加，防止 header 撒谎——spike §5.3 注意点）
                    outFile.parentFile?.mkdirs()
                    var written = 0L
                    outFile.outputStream().use { out ->
                        val buffer = ByteArray(8 * 1024)
                        while (true) {
                            val read = zis.read(buffer)
                            if (read < 0) break
                            written += read
                            if (written > MAX_ENTRY_BYTES) {
                                throw KmdworkUnpackException.EntryTooBig(entry.name, written)
                            }
                            total += read
                            if (total > MAX_TOTAL_UNCOMPRESSED) {
                                throw KmdworkUnpackException.TotalTooBig()
                            }
                            out.write(buffer, 0, read)
                        }
                    }
                } finally {
                    zis.closeEntry()
                }
            }
        }
    }

    private fun parseManifest(file: File): BundleManifest =
        try {
            json.decodeFromString(BundleManifest.serializer(), file.readText())
        } catch (e: Exception) {
            throw KmdworkUnpackException.ManifestInvalid(e)
        }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return buildString(digest.size * 2) {
            digest.forEach { append("%02x".format(it)) }
        }
    }

    private fun cleanup(dir: File) {
        dir.deleteRecursively()
    }
}

data class UnpackResult(
    val bundleId: String,
    val manifest: BundleManifest,
    val entryPath: String,
    val entrySource: String,
    val extractedPaths: Set<String>,
    val contentHash: String
)

sealed class KmdworkUnpackException(message: String) : Exception(message) {
    class ZipSlip(path: String) : KmdworkUnpackException("zip slip: $path")
    class EntryTooBig(path: String, size: Long) :
        KmdworkUnpackException("entry too big ($size bytes): $path")
    class TotalTooBig :
        KmdworkUnpackException("total uncompressed size exceeds ${KmdworkUnpacker.MAX_TOTAL_UNCOMPRESSED} bytes")
    class TooManyEntries :
        KmdworkUnpackException("too many entries (max ${KmdworkUnpacker.MAX_ENTRIES})")
    class DuplicatePath(path: String) : KmdworkUnpackException("duplicate path: $path")
    class ManifestMissing : KmdworkUnpackException("work.json not found in bundle")
    class ManifestInvalid(cause: Throwable) :
        KmdworkUnpackException("manifest invalid: ${cause.message ?: cause::class.simpleName}")
    class EntryMissing(entry: String) :
        KmdworkUnpackException("entry script not found in bundle: $entry")
    class AssetReferenceUnclosed(ref: String) :
        KmdworkUnpackException("asset reference not found in bundle: $ref")
    class ContentHashMismatch(expected: String, actual: String) :
        KmdworkUnpackException("contentHash mismatch: expected=$expected actual=$actual")
    class EmptyBundle : KmdworkUnpackException("empty bundle (no entries)")
}