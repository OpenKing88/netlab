package io.github.openking88.netlab

import io.github.openking88.netlab.capture.CaptureBody
import io.github.openking88.netlab.capture.CapturedBytes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPOutputStream

class CaptureBodyTest {

    @Test
    fun `文本 body 正常解码`() {
        val bytes = """{"name":"domain-switch"}""".toByteArray(StandardCharsets.UTF_8)

        val text = CaptureBody.decode(CapturedBytes.captured(bytes, false), StandardCharsets.UTF_8)

        assertEquals("""{"name":"domain-switch"}""", text)
    }

    @Test
    fun `二进制 body 不会被解成乱码文本`() {
        // 前 8 字节是合法 ASCII，后面是控制字符 —— Monitor 只采样 64 字节时这里会误判成文本
        val binary = ByteArray(1024).apply {
            "PNGDATA".toByteArray(StandardCharsets.US_ASCII).copyInto(this)
            for (index in 7 until size) {
                this[index] = 0x00
            }
        }

        val text = CaptureBody.decode(CapturedBytes.captured(binary, false), StandardCharsets.UTF_8)

        assertTrue("二进制不应解出文本", text.isEmpty())
    }

    @Test
    fun `gzip 编码的响应会被解压`() {
        val plain = """{"compressed":true}""".toByteArray(StandardCharsets.UTF_8)
        val gzipped = ByteArrayOutputStream().use { output ->
            GZIPOutputStream(output).use { it.write(plain) }
            output.toByteArray()
        }

        val text = CaptureBody.decode(CapturedBytes.captured(gzipped, true), StandardCharsets.UTF_8)

        assertEquals("""{"compressed":true}""", text)
    }

    @Test
    fun `未捕获或超限的 body 解码为空`() {
        assertEquals("", CaptureBody.decode(CapturedBytes.omitted(), StandardCharsets.UTF_8))
        assertEquals("", CaptureBody.decode(CapturedBytes.tooLarge(), StandardCharsets.UTF_8))
        assertEquals("", CaptureBody.decode(CapturedBytes.empty(), StandardCharsets.UTF_8))
        assertEquals("", CaptureBody.decode(null, StandardCharsets.UTF_8))
    }

    @Test
    fun `记录能反映耗时与是否被改写`() {
        val record = io.github.openking88.netlab.capture.CaptureRecord().apply {
            startedAtMillis = 1_000L
            finishedAtMillis = 1_250L
        }
        assertEquals(250L, record.durationMillis())
        assertFalse(record.isFinished())
        assertFalse(record.isRewritten())

        record.state = io.github.openking88.netlab.capture.CaptureRecord.STATE_COMPLETED
        record.originalUrl = "https://api.example.com/v1/user"
        assertTrue(record.isFinished())
        assertTrue(record.isRewritten())
    }
}
