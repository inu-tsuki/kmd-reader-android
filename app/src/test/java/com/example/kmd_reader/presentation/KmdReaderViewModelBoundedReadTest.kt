package com.example.kmd_reader.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

/**
 * R3-D3 审查报告 Medium 修复：readBoundedKmd 的 bounded read 机制单测。
 *
 * 验证：超上限立即停止读取并抛 IOException，不读完整个文件（避免超大非 zip 文件先 readBytes 进内存）。
 *
 * 局限（诚实记录）：此测试只验证 bounded read 机制本身，不覆盖 SAF ContentResolver 实际流——
 * 后者在真机环境由 Android Framework 管理，属真机/集成验证范畴，单测无法驱动。
 */
class KmdReaderViewModelBoundedReadTest {

    @Test
    fun underLimitReturnsFullTextWithPrefix() {
        val prefix = byteArrayOf(0x74, 0x65, 0x73, 0x74) // "test"
        val body = "KMD body 内容".toByteArray(Charsets.UTF_8)
        val stream = CountingInputStream(ByteArrayInputStream(body))

        val text = readBoundedKmd(stream, alreadyRead = prefix, maxBytes = 1024L)

        assertEquals("test" + "KMD body 内容", text)
        assertEquals(body.size, stream.bytesRead)
        assertTrue("body 应被完整读取", stream.bytesRead == body.size)
    }

    @Test
    fun underLimitReturnsFullTextWithoutPrefix() {
        val body = "纯文本".toByteArray(Charsets.UTF_8)
        val stream = CountingInputStream(ByteArrayInputStream(body))

        val text = readBoundedKmd(stream, alreadyRead = ByteArray(0), maxBytes = 1024L)

        assertEquals("纯文本", text)
        assertEquals(body.size, stream.bytesRead)
    }

    @Test
    fun abortsAtLimitAndDoesNotReadWholeStream() {
        // 总量远超上限：确保读到上限附近即抛错，而不是把整个文件读完
        val overflow = ByteArray(500_000) // 500KB，上限 100 字节
        val stream = CountingInputStream(ByteArrayInputStream(overflow))

        try {
            readBoundedKmd(stream, alreadyRead = ByteArray(0), maxBytes = 100L)
            fail("应抛 IOException")
        } catch (e: IOException) {
            assertTrue("错误信息应含 MB 上限", e.message!!.contains("MB"))
        }
        // 关键断言：没有读完整个 500KB 文件，只读到上限附近（maxBytes + 一次 buffer 上限）
        assertTrue(
            "不应读完整个文件，实际读取 ${stream.bytesRead} 字节",
            stream.bytesRead <= 100L + 8 * 1024
        )
        assertTrue("至少应尝试读取一部分，实际 ${stream.bytesRead}", stream.bytesRead > 0)
    }

    @Test
    fun prefixCountsTowardLimitAndAborts() {
        // 前缀已经占满上限 → 立即抛错，body 一字节都不读
        val prefix = ByteArray(101) // 超过 100 上限
        val body = "should not be read".toByteArray(Charsets.UTF_8)
        val stream = CountingInputStream(ByteArrayInputStream(body))

        try {
            readBoundedKmd(stream, alreadyRead = prefix, maxBytes = 100L)
            fail("前缀超上限应立即抛 IOException")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("MB"))
        }
        assertEquals(0, stream.bytesRead)
        assertTrue("前缀已超上限，body 不应被读取", stream.bytesRead == 0)
    }

    /** 记录底层真实读取字节数的 InputStream 包装，用于断言"没有读完整个文件"。 */
    private class CountingInputStream(private val delegate: InputStream) : InputStream() {
        var bytesRead = 0; private set

        override fun read(): Int {
            val b = delegate.read()
            if (b >= 0) bytesRead += 1
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = delegate.read(b, off, len)
            if (n > 0) bytesRead += n
            return n
        }
    }
}