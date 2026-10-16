package org.connectbot.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

class ImageDimensionsTest {
    /** A PNG header: signature, IHDR length and type, width, height, then the nine bytes the reader skips. */
    private fun png(width: Int, height: Int): ImageBytes {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0d, 0x0a, 0x1a, 0x0a))
        DataOutputStream(out).apply {
            writeInt(13)
            write("IHDR".toByteArray(Charsets.US_ASCII))
            writeInt(width)
            writeInt(height)
        }
        out.write(ByteArray(9))
        return ImageBytes.Builder(1 shl 20).also { it.write(out.toByteArray()) }.build()
    }

    @Test fun readsPngDimensionsFromTheHeaderOnly() {
        val d = ImageDimensions.read(png(640, 480), InlineImageLimits())
        assertEquals(640, d.width)
        assertEquals(480, d.height)
        assertEquals("image/png", d.mime)
    }

    @Test fun refusesDimensionsBeyondTheLimit() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            ImageDimensions.read(png(20_000, 10), InlineImageLimits())
        }
        assertTrue(e.message!!.startsWith("E2BIG:"))
    }

    @Test fun refusesUnknownFormats() {
        val bytes = ImageBytes.Builder(64).also { it.write(ByteArray(16) { 1 }) }.build()
        assertThrows(IllegalArgumentException::class.java) { ImageDimensions.read(bytes, InlineImageLimits()) }
    }
}
