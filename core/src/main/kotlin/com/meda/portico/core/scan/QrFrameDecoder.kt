package com.meda.portico.core.scan

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.nio.ByteBuffer

/**
 * A camera Y plane, compacted to one byte per pixel: ZXing wants a tight greyscale plane and the
 * camera hands over a strided one. Ported from the old intercom app's `TransferActivity.decode`,
 * which scanned a real code on real phones (2026-09-13).
 *
 * Both strides are honoured. A row stride wider than the frame is normal (alignment), and a pixel
 * stride of 2 on the Y plane is rare but real; either one, ignored, shears the image so that it
 * never decodes, which looks exactly like "the camera cannot see it".
 */
object LumaPlane {
    fun compact(buf: ByteBuffer, rowStride: Int, pixelStride: Int, width: Int, height: Int): ByteArray {
        val luma = ByteArray(width * height)
        val row = ByteArray(rowStride)
        val src = buf.duplicate()
        for (y in 0 until height) {
            val pos = y * rowStride
            if (pos >= src.limit()) break
            src.position(pos)
            val len = minOf(rowStride, src.remaining())
            src.get(row, 0, len)
            if (pixelStride == 1) {
                System.arraycopy(row, 0, luma, y * width, minOf(width, len))
            } else {
                var x = 0
                while (x < width && x * pixelStride < len) {
                    luma[y * width + x] = row[x * pixelStride]
                    x++
                }
            }
        }
        return luma
    }
}

/**
 * Decodes one greyscale frame. The whole frame is read, not just the aiming square: a code
 * slightly outside the square still scans. QR only, with TRY_HARDER, as the old app.
 *
 * Not thread-safe (ZXing's reader keeps state); the scanner uses one per camera thread. Returns
 * the code's text or null; never throws, never logs. The text is the enrollment's secrets: the
 * caller hands it to [ScanHandoff] and keeps no copy.
 */
class QrFrameDecoder {
    private val reader = QRCodeReader()
    private val hints = mapOf(
        DecodeHintType.TRY_HARDER to true,
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
    )

    fun decode(luma: ByteArray, width: Int, height: Int): String? {
        if (width <= 0 || height <= 0 || luma.size < width * height) return null
        return try {
            val src = PlanarYUVLuminanceSource(luma, width, height, 0, 0, width, height, false)
            reader.decode(BinaryBitmap(HybridBinarizer(src)), hints).text
        } catch (_: Exception) {
            null // no code in this frame (NotFound/Checksum/Format), or a frame ZXing rejects
        } finally {
            reader.reset()
        }
    }

    override fun toString(): String = "QrFrameDecoder"
}
