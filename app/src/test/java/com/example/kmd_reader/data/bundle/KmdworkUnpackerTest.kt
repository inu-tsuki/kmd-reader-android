package com.example.kmd_reader.data.bundle

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * KmdworkUnpacker 回归 —— spike `r3-d-storage-spike.md` §5.2 Z1–Z9 防护清单 + manifest 校验 + 清理。
 *
 * 用 Robolectric Context.filesDir 作为真实文件系统目标，避免 mock File 行为。
 * 测试 zip 用 ZipOutputStream + ByteArrayOutputStream 构造内存 zip。
 */
@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE, sdk = [28])
class KmdworkUnpackerTest {

    private lateinit var context: Context
    private lateinit var targetDir: java.io.File

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        targetDir = java.io.File(context.filesDir, "test-bundles/${System.nanoTime()}")
        targetDir.mkdirs()
    }

    // ── 测试 zip 构造 helper ──

    private fun buildZip(entries: Map<String, ByteArray>): ByteArrayInputStream {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            entries.forEach { (name, data) ->
                zos.putNextEntry(ZipEntry(name))
                zos.write(data)
                zos.closeEntry()
            }
        }
        return ByteArrayInputStream(baos.toByteArray())
    }

    private fun buildZipWithSizes(entries: List<Pair<String, Long>>): ByteArrayInputStream {
        // 构造指定 size 的 entry（写入对应字节的 0x00），用于大小上限测试。
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            entries.forEach { (name, size) ->
                zos.putNextEntry(ZipEntry(name))
                // ZipOutputStream 会自动压缩，但我们写全零字节让它 STORED 效果接近原始大小。
                // 实际上 zip 压缩零字节序列效果很好，但 entry.size 仍反映原始大小。
                val data = ByteArray(size.toInt().coerceAtMost(Int.MAX_VALUE))
                zos.write(data)
                zos.closeEntry()
            }
        }
        return ByteArrayInputStream(baos.toByteArray())
    }

    private fun workJson(
        bundleId: String = "bundle-test-001",
        entry: String = "scripts/main.kmd",
        contentHash: String? = null,
        exportRevisionId: String? = "rev-1",
        assets: Map<String, BundleAssetRef> = emptyMap(),
        fonts: List<BundleFontAsset> = emptyList()
    ): ByteArray {
        val revisions = if (contentHash != null) {
            """[{"id":"rev-1","parentRevisionId":null,"contentHash":"$contentHash","message":"initial","createdAt":1000}]"""
        } else "[]"
        val assetManifest = if (assets.isEmpty() && fonts.isEmpty()) "null"
            else buildAssetManifestJson(assets, fonts)
        return """
            {
              "formatVersion": 1,
              "bundleId": "$bundleId",
              "entry": "$entry",
              "assetManifest": $assetManifest,
              "revisions": $revisions,
              "exportRevisionId": ${if (exportRevisionId != null) "\"$exportRevisionId\"" else "null"}
            }
        """.trimIndent().toByteArray()
    }

    private fun buildAssetManifestJson(
        assets: Map<String, BundleAssetRef>,
        fonts: List<BundleFontAsset>
    ): String {
        val fontsJson = fonts.joinToString(",") { f ->
            """{"family":"${f.family}","url":"${f.url}","weight":${f.weight?.let { "\"$it\"" } ?: "null"},"style":${f.style?.let { "\"$it\"" } ?: "null"}}"""
        }
        val assetsJson = assets.entries.joinToString(",") { (k, v) ->
            """"$k":{"url":"${v.url}","type":${v.type?.let { "\"$it\"" } ?: "null"}}"""
        }
        return """{"baseUrl":null,"fonts":[$fontsJson],"assets":{$assetsJson}}"""
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return buildString(digest.size * 2) { digest.forEach { append("%02x".format(it)) } }
    }

    // ── 正常路径 ──

    @Test
    fun unpackValidBundleWritesFilesAndReturnsManifest() {
        val source = "---\nmode: stage\n---\nHello KMD".toByteArray()
        val zip = buildZip(mapOf(
            "work.json" to workJson(contentHash = sha256Hex(source)),
            "scripts/main.kmd" to source
        ))

        val result = KmdworkUnpacker.unpack(zip, targetDir)

        assertEquals("bundle-test-001", result.bundleId)
        assertEquals("scripts/main.kmd", result.entryPath)
        assertEquals("---\nmode: stage\n---\nHello KMD", result.entrySource)
        assertTrue(java.io.File(targetDir, "work.json").exists())
        assertTrue(java.io.File(targetDir, "scripts/main.kmd").exists())
    }

    @Test
    fun unpackValidBundleWithAssetsPassesReferenceClosure() {
        val source = "---\nmode: stage\n---\nHello".toByteArray()
        val fontBytes = "fake-font-bytes".toByteArray()
        val zip = buildZip(mapOf(
            "work.json" to workJson(
                contentHash = sha256Hex(source),
                fonts = listOf(BundleFontAsset("MyFont", "assets/fonts/my-font.woff2"))
            ),
            "scripts/main.kmd" to source,
            "assets/fonts/my-font.woff2" to fontBytes
        ))

        val result = KmdworkUnpacker.unpack(zip, targetDir)

        assertEquals("bundle-test-001", result.bundleId)
        assertTrue(result.extractedPaths.contains("assets/fonts/my-font.woff2"))
    }

    @Test
    fun contentHashMatchesLatestRevision() {
        val source = "---\nmode: stage\n---\nHello KMD".toByteArray()
        val hash = sha256Hex(source)
        val zip = buildZip(mapOf(
            "work.json" to workJson(contentHash = hash, exportRevisionId = "rev-1"),
            "scripts/main.kmd" to source
        ))

        val result = KmdworkUnpacker.unpack(zip, targetDir)

        assertEquals(hash, result.contentHash)
    }

    // ── 安全防护 Z1–Z9 ──

    @Test
    fun zipSlipPathTraversalRejected() {
        val zip = buildZip(mapOf(
            "work.json" to workJson(),
            "scripts/main.kmd" to "src".toByteArray(),
            "../../../etc/passwd" to "evil".toByteArray()
        ))

        val ex = assertThrows<KmdworkUnpackException.ZipSlip> {
            KmdworkUnpacker.unpack(zip, targetDir)
        }
        assertTrue(ex.message!!.contains("zip slip"))
        assertFalse("半解文件应被清理", targetDir.exists() && targetDir.listFiles()?.isNotEmpty() == true)
    }

    @Test
    fun entryExceedingMaxSizeRejected() {
        // 构造一个超过 10MB 的 entry
        val bigEntry = ByteArray((KmdworkUnpacker.MAX_ENTRY_BYTES + 1).toInt())
        val zip = buildZip(mapOf(
            "work.json" to workJson(),
            "scripts/main.kmd" to bigEntry
        ))

        assertThrows<KmdworkUnpackException.EntryTooBig> {
            KmdworkUnpacker.unpack(zip, targetDir)
        }
    }

    @Test
    fun totalExceedingMaxSizeRejected() {
        // 构造多个 entry 累计超过 50MB（每个 ~6MB，10 个 = 60MB）
        val entries = (1..10).associate { i ->
            "scripts/part-$i.kmd" to ByteArray(6 * 1024 * 1024)
        }
        val zip = buildZip(entries + ("work.json" to workJson(entry = "scripts/part-1.kmd")))

        assertThrows<KmdworkUnpackException.TotalTooBig> {
            KmdworkUnpacker.unpack(zip, targetDir)
        }
    }

    @Test
    fun tooManyEntriesRejected() {
        // 构造超过 1024 个 entry
        val entries = (1..1025).associate { i ->
            "scripts/file-$i.kmd" to "x".toByteArray()
        }
        // 不放 work.json，让条目数检查先触发（条目数在解压循环中检查）
        val zip = buildZip(entries)

        assertThrows<KmdworkUnpackException.TooManyEntries> {
            KmdworkUnpacker.unpack(zip, targetDir)
        }
    }

    @Test
    fun duplicatePathRejected() {
        // ZipOutputStream 在写入阶段拒绝同名 entry，手动构造 raw zip 字节绕过去重。
        // zip 含 work.json + 两个同名 scripts/main.kmd entry。
        val source1 = "src1".toByteArray()
        val source2 = "src2".toByteArray()
        val combined = buildMultiEntryZip(
            listOf(
                "work.json" to workJson(),
                "scripts/main.kmd" to source1,
                "scripts/main.kmd" to source2  // 重复 entry
            )
        )

        assertThrows<KmdworkUnpackException.DuplicatePath> {
            KmdworkUnpacker.unpack(ByteArrayInputStream(combined), targetDir)
        }
    }

    /**
     * 手动构造 zip 字节（绕过 ZipOutputStream 的去重），支持重复 entry name。
     * 写 N 个 local file header + data + N 个 central directory entry + EOCD。STORED（无压缩）。
     * 注意：zip 格式是 LITTLE-ENDIAN，不能用 DataOutputStream（big-endian）。
     */
    private fun buildMultiEntryZip(entries: List<Pair<String, ByteArray>>): ByteArray {
        val baos = ByteArrayOutputStream()

        val nameBytesList = entries.map { it.first.toByteArray() }
        val crcList = entries.map { e ->
            java.util.zip.CRC32().apply { update(e.second) }.value
        }
        val offsets = mutableListOf<Long>()

        entries.forEachIndexed { i, (_, data) ->
            offsets.add(baos.size().toLong())
            val nameBytes = nameBytesList[i]
            // local file header
            writeIntLE(baos, 0x04034b50)
            writeShortLE(baos, 20)        // version needed
            writeShortLE(baos, 0)        // flags
            writeShortLE(baos, 0)        // compression (STORED)
            writeShortLE(baos, 0)        // mod time
            writeShortLE(baos, 0)        // mod date
            writeIntLE(baos, crcList[i].toInt())
            writeIntLE(baos, data.size)  // compressed size
            writeIntLE(baos, data.size)  // uncompressed size
            writeShortLE(baos, nameBytes.size)
            writeShortLE(baos, 0)        // extra field length
            baos.write(nameBytes)
            baos.write(data)
        }

        val centralStart = baos.size().toLong()
        entries.forEachIndexed { i, (_, data) ->
            val nameBytes = nameBytesList[i]
            // central file header
            writeIntLE(baos, 0x02014b50)
            writeShortLE(baos, 20)       // version made by
            writeShortLE(baos, 20)       // version needed
            writeShortLE(baos, 0)        // flags
            writeShortLE(baos, 0)        // compression (STORED)
            writeShortLE(baos, 0)        // mod time
            writeShortLE(baos, 0)        // mod date
            writeIntLE(baos, crcList[i].toInt())
            writeIntLE(baos, data.size)  // compressed size
            writeIntLE(baos, data.size)  // uncompressed size
            writeShortLE(baos, nameBytes.size)
            writeShortLE(baos, 0)       // extra
            writeShortLE(baos, 0)       // comment
            writeShortLE(baos, 0)       // disk number
            writeShortLE(baos, 0)       // internal attrs
            writeIntLE(baos, 0)          // external attrs
            writeIntLE(baos, offsets[i].toInt())
            baos.write(nameBytes)
        }

        // EOCD
        writeIntLE(baos, 0x06054b50)
        writeShortLE(baos, 0)       // disk number
        writeShortLE(baos, 0)       // disk with central dir
        writeShortLE(baos, entries.size)  // entries on this disk
        writeShortLE(baos, entries.size)  // total entries
        writeIntLE(baos, (baos.size() - centralStart).toInt())  // central dir size
        writeIntLE(baos, centralStart.toInt())  // central dir offset
        writeShortLE(baos, 0)       // comment length

        return baos.toByteArray()
    }

    private fun writeShortLE(baos: ByteArrayOutputStream, v: Int) {
        baos.write(v and 0xFF)
        baos.write((v shr 8) and 0xFF)
    }

    private fun writeIntLE(baos: ByteArrayOutputStream, v: Int) {
        baos.write(v and 0xFF)
        baos.write((v shr 8) and 0xFF)
        baos.write((v shr 16) and 0xFF)
        baos.write((v shr 24) and 0xFF)
    }

    @Test
    fun emptyBundleRejected() {
        val zip = buildZip(emptyMap())

        assertThrows<KmdworkUnpackException.EmptyBundle> {
            KmdworkUnpacker.unpack(zip, targetDir)
        }
    }

    // ── Manifest 校验 Z8 ──

    @Test
    fun entryScriptMissingRejected() {
        val zip = buildZip(mapOf(
            "work.json" to workJson(entry = "scripts/nonexistent.kmd"),
            "scripts/main.kmd" to "src".toByteArray()
        ))

        assertThrows<KmdworkUnpackException.EntryMissing> {
            KmdworkUnpacker.unpack(zip, targetDir)
        }
    }

    @Test
    fun assetReferenceUnclosedRejected() {
        val source = "src".toByteArray()
        val zip = buildZip(mapOf(
            "work.json" to workJson(
                contentHash = sha256Hex(source),
                fonts = listOf(BundleFontAsset("MyFont", "assets/fonts/missing.woff2"))
            ),
            "scripts/main.kmd" to source
            // assets/fonts/missing.woff2 故意不存在
        ))

        assertThrows<KmdworkUnpackException.AssetReferenceUnclosed> {
            KmdworkUnpacker.unpack(zip, targetDir)
        }
    }

    // ── ContentHash 校验 Z5 ──

    @Test
    fun contentHashMismatchRejected() {
        val source = "---\nmode: stage\n---\nactual content".toByteArray()
        val wrongHash = "0000000000000000000000000000000000000000000000000000000000000000"
        val zip = buildZip(mapOf(
            "work.json" to workJson(contentHash = wrongHash, exportRevisionId = "rev-1"),
            "scripts/main.kmd" to source
        ))

        assertThrows<KmdworkUnpackException.ContentHashMismatch> {
            KmdworkUnpacker.unpack(zip, targetDir)
        }
    }

    // ── 清理 ──

    @Test
    fun failedUnpackCleansPartialFiles() {
        // 先写入一个合法文件到 targetDir，再触发失败，验证全部被清理
        java.io.File(targetDir, "pre-existing.txt").writeText("should be cleaned")

        val zip = buildZip(mapOf(
            "work.json" to workJson(),
            "scripts/main.kmd" to "src".toByteArray(),
            "../../../evil" to "evil".toByteArray()
        ))

        assertThrows<KmdworkUnpackException> {
            KmdworkUnpacker.unpack(zip, targetDir)
        }

        assertFalse("targetDir should be cleaned after failure", targetDir.exists())
    }

    // ── assertThrows helper（Kotlin 没有 stdlib 版）──

    private inline fun <reified T : Throwable> assertThrows(block: () -> Unit): T {
        try {
            block()
        } catch (e: Throwable) {
            if (T::class.java.isInstance(e)) return e as T
            throw AssertionError("expected ${T::class.simpleName} but got ${e::class.simpleName}: ${e.message}", e)
        }
        throw AssertionError("expected ${T::class.simpleName} to be thrown")
    }
}