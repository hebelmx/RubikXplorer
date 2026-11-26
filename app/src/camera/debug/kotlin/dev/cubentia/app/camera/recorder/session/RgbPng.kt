package dev.cubentia.app.camera.recorder.session

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * Story 10.34: a minimal lossless PNG encoder for packed 8-bit RGB (colour type 2, no alpha, filter 0), so the stored
 * model input is exactly the detector's `256 × 256 × 3` bytes on any JVM (no `Bitmap`, no colour management).
 */
internal object RgbPng {
    private val SIGNATURE = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A)

    fun encode(
        rgb: ByteArray,
        width: Int,
        height: Int,
    ): ByteArray {
        require(width > 0 && height > 0 && rgb.size >= width * height * 3) { "RGB buffer too small for ${width}x$height" }
        val raw = ByteArray((width * 3 + 1) * height)
        for (row in 0 until height) {
            val out = row * (width * 3 + 1)
            raw[out] = 0
            System.arraycopy(rgb, row * width * 3, raw, out + 1, width * 3)
        }
        val deflater = Deflater(Deflater.BEST_SPEED)
        val compressed =
            ByteArrayOutputStream().use { buffer ->
                deflater.setInput(raw)
                deflater.finish()
                val chunk = ByteArray(64 * 1024)
                while (!deflater.finished()) buffer.write(chunk, 0, deflater.deflate(chunk))
                deflater.end()
                buffer.toByteArray()
            }
        return ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { out ->
                out.write(SIGNATURE)
                val header =
                    ByteArrayOutputStream().use { h ->
                        DataOutputStream(h).use {
                            it.writeInt(width)
                            it.writeInt(height)
                            it.writeByte(8)
                            it.writeByte(2)
                            it.writeByte(0)
                            it.writeByte(0)
                            it.writeByte(0)
                        }
                        h.toByteArray()
                    }
                chunk(out, "IHDR", header)
                chunk(out, "IDAT", compressed)
                chunk(out, "IEND", ByteArray(0))
            }
            bytes.toByteArray()
        }
    }

    private fun chunk(
        out: DataOutputStream,
        type: String,
        data: ByteArray,
    ) {
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        out.writeInt(data.size)
        out.write(typeBytes)
        out.write(data)
        val crc =
            CRC32().apply {
                update(typeBytes)
                update(data)
            }
        out.writeInt(crc.value.toInt())
    }
}
