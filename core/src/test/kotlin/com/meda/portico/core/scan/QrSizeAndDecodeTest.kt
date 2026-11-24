package com.meda.portico.core.scan

import com.meda.portico.core.enroll.EnrollmentParseResult
import com.meda.portico.core.enroll.EnrollmentPayload
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import kotlin.random.Random

/**
 * Spec §5.4: measure the real payload's QR version at the error-correction level the server uses
 * (`enrollment_flow.py` `_build_qr_data_uri`: python-qrcode, `ERROR_CORRECT_M`, `fit=True`,
 * compact JSON), and prove that the app's frame decoder ([QrFrameDecoder], the exact code the
 * camera path runs) reads a dense code out of a camera-sized luma frame.
 *
 * What this cannot prove: optics. Both ends here are ZXing (encode, then decode a synthetic
 * frame), so it proves the format, the stride handling and the decoder's reach at a given
 * module size, never a phone camera's focus, glare or a moiré off an LCD. Only a real scan does.
 */
class QrSizeAndDecodeTest {

    /** ZXing's encoder picks the same mode (byte: the JSON has lower case) and the smallest version that fits, as python-qrcode's fit=True does. */
    private fun versionAtM(text: String): Int =
        Encoder.encode(text, ErrorCorrectionLevel.M, mapOf(EncodeHintType.CHARACTER_SET to "UTF-8")).version.versionNumber

    @Test
    fun `the realistic payload is 579 bytes and needs QR version 19 at ECC M`() {
        val payload = ScanFixtures.realisticPayload()
        assertEquals(183, ScanFixtures.HA_LONG_LIVED_TOKEN.length, "HA's long-lived token shape")
        assertTrue(EnrollmentPayload.parse(payload) is EnrollmentParseResult.Ok, "the fixture is a valid enrollment")
        val bytes = payload.toByteArray(Charsets.UTF_8).size
        val version = versionAtM(payload)
        println("PR-C2 QR size: realistic payload $bytes bytes -> version $version at ECC M (${17 + 4 * version} modules a side)")
        assertEquals(579, bytes)
        // Byte-mode capacity at M: version 18 holds 560 bytes, version 19 holds 624.
        assertEquals(19, version)
        // Spec §5.4's ceiling: above version 20 shorten the keys or move phone_name out.
        assertTrue(version <= 20)
    }

    @Test
    fun `without the Tailscale key (manual mode) it is version 17`() {
        val payload = ScanFixtures.realisticPayload().replace(Regex(""""tailscale_authkey":"[^"]*","""), "")
        assertTrue(EnrollmentPayload.parse(payload) is EnrollmentParseResult.Ok)
        println("PR-C2 QR size: no tailscale_authkey ${payload.length} bytes -> version ${versionAtM(payload)} at ECC M")
        assertEquals(17, versionAtM(payload))
    }

    /**
     * A FINDING, pinned so it is not lost: a 24-letter Hebrew phone name, escaped as Python's
     * `json.dumps` does by default (`\uXXXX`, 6 bytes a letter), pushes the code to version 21,
     * past spec §5.4's ceiling of about 20. It still decodes (see the version-40 test); the
     * remedy, if a phone shows it is too dense on a laptop screen, is the server's
     * (`ensure_ascii=False` saves ~100 bytes, or shorter keys), not the app's.
     */
    @Test
    fun `a Hebrew phone name, escaped as json-dumps does, reaches version 21`() {
        val payload = ScanFixtures.realisticPayload(ScanFixtures.hebrewNameEscaped())
        val r = EnrollmentPayload.parse(payload) as EnrollmentParseResult.Ok
        assertEquals("יאנה - טלפון פיקסל שמונה", r.payload.phoneName)
        val version = versionAtM(payload)
        println("PR-C2 QR size: with a Hebrew phone name ${payload.length} bytes -> version $version at ECC M")
        assertEquals(21, version)
        val raw = ScanFixtures.realisticPayload("יאנה - טלפון פיקסל שמונה")
        println("PR-C2 QR size: same name as raw UTF-8 ${raw.toByteArray().size} bytes -> version ${versionAtM(raw)} at ECC M")
        assertEquals(raw, QrFrameDecoder().decode(Frame.render(Encoder.encode(raw, ErrorCorrectionLevel.M, mapOf(EncodeHintType.CHARACTER_SET to "UTF-8")).matrix.toBits(), 4, 1280, 720).luma, 1280, 720))
    }

    @Test
    fun `the realistic code decodes from a 1280x720 frame at 4 px a module, through padded strides`() {
        val payload = ScanFixtures.realisticPayload()
        val matrix = Encoder.encode(payload, ErrorCorrectionLevel.M).matrix
        val frame = Frame.render(matrix.toBits(), modulePx = 4, width = 1280, height = 720)
        // A camera Y plane: rows padded to a 1344-byte stride (alignment), as real sensors do.
        val plane = frame.asPlane(rowStride = 1344, pixelStride = 1)
        val luma = LumaPlane.compact(plane, rowStride = 1344, pixelStride = 1, width = 1280, height = 720)
        assertEquals(payload, QrFrameDecoder().decode(luma, 1280, 720))
    }

    @Test
    fun `a pixel stride of 2 is honoured (an ignored one shears the image and never decodes)`() {
        val payload = ScanFixtures.realisticPayload()
        val frame = Frame.render(Encoder.encode(payload, ErrorCorrectionLevel.M).matrix.toBits(), modulePx = 4, width = 960, height = 720)
        val plane = frame.asPlane(rowStride = 960 * 2, pixelStride = 2)
        val luma = LumaPlane.compact(plane, rowStride = 1920, pixelStride = 2, width = 960, height = 720)
        assertEquals(payload, QrFrameDecoder().decode(luma, 960, 720))
    }

    @Test
    fun `the code is read at any quarter turn - the scan path never rotates a frame`() {
        val payload = ScanFixtures.realisticPayload()
        val bits = Encoder.encode(payload, ErrorCorrectionLevel.M).matrix.toBits().rotate90()
        val frame = Frame.render(bits, modulePx = 4, width = 1280, height = 720)
        assertEquals(payload, QrFrameDecoder().decode(frame.luma, 1280, 720))
    }

    /**
     * The densest QR there is: version 40, 177 modules a side, 2,331 bytes at ECC M (2,953 at L;
     * no single QR holds a 4-6 kB payload). In a 1280x720 frame, the size the scanner asks the
     * camera for, it can have at most 3-4 px a module: clean it decodes at 3 px; with sensor-like
     * noise it needs 4 px, which a 1080p frame gives. The real payload (version 19, 93 modules)
     * decodes with the same noise at 3 px a module, a code 279 px tall in a 720-px frame.
     */
    @Test
    fun `the densest QR (version 40, 2331 bytes at M) decodes clean at 3 px a module in 720p, noisy at 4 px in 1080p`() {
        val rnd = Random(7)
        val payload = buildString { while (length < 2331) append("abcdefghijklmnopqrstuvwxyz0123456789"[rnd.nextInt(36)]) }
        val code = Encoder.encode(payload, ErrorCorrectionLevel.M)
        assertEquals(40, code.version.versionNumber)
        val clean = Frame.render(code.matrix.toBits(), modulePx = 3, width = 1280, height = 720)
        assertEquals(payload, QrFrameDecoder().decode(clean.luma, 1280, 720))
        val noisy = Frame.render(code.matrix.toBits(), modulePx = 4, width = 1920, height = 1080, dark = 70, light = 180, noise = 25, rnd = Random(3))
        assertEquals(payload, QrFrameDecoder().decode(noisy.luma, 1920, 1080))
    }

    @Test
    fun `the realistic code decodes from a noisy, low-contrast 720p frame at 3 px a module`() {
        val payload = ScanFixtures.realisticPayload()
        val bits = Encoder.encode(payload, ErrorCorrectionLevel.M).matrix.toBits()
        val frame = Frame.render(bits, modulePx = 3, width = 1280, height = 720, dark = 70, light = 180, noise = 25, rnd = Random(3))
        assertEquals(payload, QrFrameDecoder().decode(frame.luma, 1280, 720))
    }

    @Test
    fun `a frame with no code decodes to null, never throws`() {
        val luma = ByteArray(640 * 480) { 128.toByte() }
        assertNull(QrFrameDecoder().decode(luma, 640, 480))
        assertNull(QrFrameDecoder().decode(ByteArray(0), 0, 0))
        assertNull(QrFrameDecoder().decode(ByteArray(10), 640, 480), "a short buffer is a dropped frame, not a crash")
    }

    // ---- helpers -------------------------------------------------------------------------------

    private class Frame(val luma: ByteArray, val width: Int, val height: Int) {
        /** The frame as a camera hands it over: each row at [rowStride], each pixel at [pixelStride]. */
        fun asPlane(rowStride: Int, pixelStride: Int): ByteBuffer {
            val buf = ByteArray(rowStride * (height - 1) + (width - 1) * pixelStride + 1) { 0x55 }
            for (y in 0 until height) for (x in 0 until width) buf[y * rowStride + x * pixelStride] = luma[y * width + x]
            return ByteBuffer.wrap(buf)
        }

        companion object {
            fun render(
                bits: Array<BooleanArray>,
                modulePx: Int,
                width: Int,
                height: Int,
                dark: Int = 20,
                light: Int = 235,
                noise: Int = 0,
                rnd: Random = Random(1),
            ): Frame {
                val n = bits.size
                val side = n * modulePx
                val ox = (width - side) / 2
                val oy = (height - side) / 2
                require(ox >= 4 * modulePx && oy >= 4 * modulePx) { "no quiet zone: $side px in ${width}x$height" }
                val luma = ByteArray(width * height)
                for (y in 0 until height) for (x in 0 until width) {
                    val mx = (x - ox).floorDiv(modulePx)
                    val my = (y - oy).floorDiv(modulePx)
                    val isDark = mx in 0 until n && my in 0 until n && bits[my][mx]
                    val v = (if (isDark) dark else light) + if (noise > 0) rnd.nextInt(-noise, noise + 1) else 0
                    luma[y * width + x] = v.coerceIn(0, 255).toByte()
                }
                return Frame(luma, width, height)
            }
        }
    }

    private fun com.google.zxing.qrcode.encoder.ByteMatrix.toBits(): Array<BooleanArray> =
        Array(height) { y -> BooleanArray(width) { x -> get(x, y).toInt() == 1 } }

    private fun Array<BooleanArray>.rotate90(): Array<BooleanArray> {
        val n = size
        return Array(n) { y -> BooleanArray(n) { x -> this[n - 1 - x][y] } }
    }

}
