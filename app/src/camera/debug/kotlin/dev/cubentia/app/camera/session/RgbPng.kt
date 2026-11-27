package dev.cubentia.app.camera.session

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * Story 10.34: a minimal lossless PNG encoder for packed 8-bit RGB (colour type 2, no alpha, filter 0), so the stored
 * model input is exactly the detector's `256 × 256 × 3` bytes on any JVM (no `Bitmap`, no colour management). One
 * encoder is reused for a whole session on the writer thread: one [Deflater], one row buffer and one output buffer, so
 * encoding a frame allocates nothing that grows with the session.
 */
internal class RgbPng(
    private val width: Int,
    private val height: Int,
) {
    init {
        require(width > 0 && height > 0) { "Bad PNG size ${width}x$height" }
    }

    /** A [ByteArrayOutputStream] whose buffer is read in place (no `toByteArray()` copy). */
    private class Bytes(
        size: Int,
    ) : ByteArrayOutputStream(size) {
        val buffer: ByteArray get() = buf

        fun writeInt(value: Int) {
            write(value ushr 24)
            write(value ushr 16)
            write(value ushr 8)
            write(value)
        }
    }

    private val raw = ByteArray((width * 3 + 1) * height)
    private val deflater = Deflater(Deflater.BEST_SPEED)
    private val chunk = ByteArray(CHUNK_BYTES)
    private val compressed = Bytes(raw.size / 2)
    private val png = Bytes(raw.size / 2)
    private val crc = CRC32()
    private val header = ByteArray(HEADER_BYTES)

    /** Encodes [rgb] into the reused buffer; returns the PNG's size in bytes, then [writeTo] writes it. */
    fun encode(rgb: ByteArray): Int {
        require(rgb.size >= width * height * 3) { "RGB buffer too small for ${width}x$height" }
        for (row in 0 until height) {
            val out = row * (width * 3 + 1)
            raw[out] = 0
            System.arraycopy(rgb, row * width * 3, raw, out + 1, width * 3)
        }
        compressed.reset()
        deflater.reset()
        deflater.setInput(raw)
        deflater.finish()
        while (!deflater.finished()) compressed.write(chunk, 0, deflater.deflate(chunk))
        png.reset()
        png.write(SIGNATURE, 0, SIGNATURE.size)
        writeHeader()
        chunk("IDAT", compressed.buffer, compressed.size())
        chunk("IEND", header, 0)
        return png.size()
    }

    /** Writes the last [encode]d PNG to [out]. */
    fun writeTo(out: OutputStream) = out.write(png.buffer, 0, png.size())

    /** Frees the [Deflater]; the encoder is not used afterwards. */
    fun close() = deflater.end()

    private fun writeHeader() {
        fun put(
            at: Int,
            value: Int,
        ) {
            header[at] = (value ushr 24).toByte()
            header[at + 1] = (value ushr 16).toByte()
            header[at + 2] = (value ushr 8).toByte()
            header[at + 3] = value.toByte()
        }
        put(0, width)
        put(4, height)
        header[8] = 8
        header[9] = 2
        header[10] = 0
        header[11] = 0
        header[12] = 0
        chunk("IHDR", header, HEADER_BYTES)
    }

    private fun chunk(
        type: String,
        data: ByteArray,
        length: Int,
    ) {
        png.writeInt(length)
        crc.reset()
        for (c in type) {
            png.write(c.code)
            crc.update(c.code)
        }
        png.write(data, 0, length)
        crc.update(data, 0, length)
        png.writeInt(crc.value.toInt())
    }

    companion object {
        private val SIGNATURE = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A)
        private const val HEADER_BYTES = 13
        private const val CHUNK_BYTES = 64 * 1024

        /** One-off encode (tests and tools): the PNG bytes of [rgb]. */
        fun encode(
            rgb: ByteArray,
            width: Int,
            height: Int,
        ): ByteArray {
            val encoder = RgbPng(width, height)
            try {
                encoder.encode(rgb)
                return ByteArrayOutputStream().also(encoder::writeTo).toByteArray()
            } finally {
                encoder.close()
            }
        }
    }
}
