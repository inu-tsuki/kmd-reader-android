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

            // 拒绝不支持的 formatVersion
            if (manifest.formatVersion != 1) {
                throw KmdworkUnpackException.UnsupportedFormatVersion(manifest.formatVersion)
            }

            // 校验 bundleId 安全性（防止 `../` 逃逸在 BundleStore 侧——这里提前拦）
            validateBundleId(manifest.bundleId)

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

            // Z5: contentHash 校验。exportRevisionId 必须存在、指向的 revision 必须存在、
            // 且 contentHash 必须匹配——缺失或悬空直接拒绝（不让 Z5 静默跳过）。
            val contentHash = sha256Hex(entryFile.readBytes())
            val exportId = manifest.exportRevisionId
                ?: throw KmdworkUnpackException.ExportRevisionMissing()
            val exportRev = manifest.revisions.find { it.id == exportId }
                ?: throw KmdworkUnpackException.ExportRevisionDangling(exportId)
            if (exportRev.contentHash != contentHash) {
                throw KmdworkUnpackException.ContentHashMismatch(
                    expected = exportRev.contentHash, actual = contentHash
                )
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

                    // Z1+: 拒绝任何含 `..`、绝对路径、空段的 entry name（比单纯 canonical 检查更严）。
                    // 这同时让 Z6 重复检测使用「safe bundle-relative path」——
                    // `scripts/main.kmd` 与 `scripts/../scripts/main.kmd` 不会都通过（后者直接被拒）。
                    val safePath = validateEntryName(entry.name)

                    // Z1: zip slip（路径穿越）。API 30+ ZipPathValidator 是 defense-in-depth，
                    // API 28/29 手动 canonicalPath 检查（spike §5.2 Z1）。
                    val outFile = File(targetDir, safePath)
                    val targetCanonical = targetDir.canonicalPath + File.separator
                    if (!outFile.canonicalPath.startsWith(targetCanonical)) {
                        throw KmdworkUnpackException.ZipSlip(entry.name)
                    }

                    // Z6: 重复路径——用规范化后的 safePath 记录，防 `a/../a` 绕过
                    if (!extractedPaths.add(safePath)) {
                        throw KmdworkUnpackException.DuplicatePath(safePath)
                    }

                    // Z3: 单 entry 大小上限（entry.size 来自 zip local header）
                    val entrySize = entry.size
                    if (entrySize > MAX_ENTRY_BYTES) {
                        throw KmdworkUnpackException.EntryTooBig(safePath, entrySize)
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
                                throw KmdworkUnpackException.EntryTooBig(safePath, written)
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

    /**
     * 校验 entry name 为安全的 bundle-relative 路径。
     * 拒绝：绝对路径、含 `..` 的路径段、空段、以 `/` 开头或包含 `\` 的路径。
     * 返回规范化后的路径（统一分隔符为 `/`）。
     */
    private fun validateEntryName(name: String): String {
        if (name.isEmpty()) throw KmdworkUnpackException.ZipSlip("(empty name)")
        val normalized = name.replace('\\', '/')
        if (normalized.startsWith("/")) throw KmdworkUnpackException.ZipSlip(name)
        val segments = normalized.split("/")
        for (seg in segments) {
            if (seg.isEmpty()) throw KmdworkUnpackException.ZipSlip(name)
            if (seg == "..") throw KmdworkUnpackException.ZipSlip(name)
            if (seg == ".") throw KmdworkUnpackException.ZipSlip(name)
        }
        return normalized
    }

    /**
     * 校验 bundleId 为安全的单路径段（UUID 格式或类似的单 token）。
     * 防止恶意 manifest 的 bundleId 如 `../victim` 逃逸 filesDir/bundles/。
     */
    private fun validateBundleId(bundleId: String) {
        if (bundleId.isEmpty()) throw KmdworkUnpackException.InvalidBundleId("(empty)")
        if (bundleId.contains("/") || bundleId.contains("\\") ||
            bundleId.contains("..") || bundleId.contains(File.separator)) {
            throw KmdworkUnpackException.InvalidBundleId(bundleId)
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
    class UnsupportedFormatVersion(version: Int) :
        KmdworkUnpackException("unsupported formatVersion: $version")
    class InvalidBundleId(bundleId: String) :
        KmdworkUnpackException("invalid bundleId (path traversal or empty): $bundleId")
    class ExportRevisionMissing :
        KmdworkUnpackException("exportRevisionId is missing (contentHash cannot be verified)")
    class ExportRevisionDangling(exportId: String) :
        KmdworkUnpackException("exportRevisionId points to non-existent revision: $exportId")
}