package dev.cubentia.buildlogic.policy

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ElfAlignmentPolicyTest {
    @Test
    fun `accepts 16 KB and 64 KB aligned ELF64 libraries`() {
        val aligned16k = elf64(listOf(0x4000L, 0x4000L))
        val res16k = ElfAlignmentPolicy.checkElf64(aligned16k, "litert.aar", "arm64-v8a", "libLiteRt.so")
        assertTrue(res16k.passes, res16k.toString())
        assertEquals(0x4000L, res16k.minLoadAlign)

        val aligned64k = elf64(listOf(0x10000L, 0x4000L))
        val res64k = ElfAlignmentPolicy.checkElf64(aligned64k, "litert.aar", "x86_64", "libLiteRt.so")
        assertTrue(res64k.passes, res64k.toString())
        assertEquals(0x4000L, res64k.minLoadAlign)
    }

    @Test
    fun `rejects 4 KB aligned ELF64 and malformed binaries`() {
        val aligned4k = elf64(listOf(0x4000L, 0x1000L))
        val res4k = ElfAlignmentPolicy.checkElf64(aligned4k, "bad.aar", "arm64-v8a", "libBad.so")
        assertFalse(res4k.passes)
        assertEquals(0x1000L, res4k.minLoadAlign)
        assertTrue(res4k.error!!.contains("0x1000"))

        val garbage = ElfAlignmentPolicy.checkElf64(ByteArray(128) { 0x42 }, "bad.aar", "arm64-v8a", "libBad.so")
        assertFalse(garbage.passes)
        assertTrue(garbage.error!!.contains("Not an ELF"))
    }

    @Test
    fun `flags LiteRT archives whose libLiteRt so was never inspected`(
        @TempDir dir: File,
    ) {
        val armOnly = File(dir, "litert-2.3.2.aar")
        ZipOutputStream(armOnly.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("jni/armeabi-v7a/libLiteRt.so"))
            zip.write(ByteArray(64))
            zip.closeEntry()
        }
        val camera = File(dir, "camera-core.aar")
        ZipOutputStream(camera.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("jni/arm64-v8a/libsurface_util_jni.so"))
            zip.write(elf64(listOf(0x4000L)))
            zip.closeEntry()
        }
        val checks = listOf(armOnly, camera).flatMap { ElfAlignmentPolicy.checkArchive(it) }
        assertEquals(listOf("litert-2.3.2.aar"), ElfAlignmentPolicy.uninspectedLiteRt(listOf(armOnly, camera), checks))

        val inspected = listOf(ElfAlignmentPolicy.checkElf64(elf64(listOf(0x4000L)), "litert-2.3.2.aar", "arm64-v8a", "libLiteRt.so"))
        assertEquals(emptyList<String>(), ElfAlignmentPolicy.uninspectedLiteRt(listOf(armOnly), inspected))
        assertEquals(emptyList<String>(), ElfAlignmentPolicy.uninspectedLiteRt(listOf(camera), checks))
    }

    @Test
    fun `scans AAR and APK archives for 64-bit ABIs and ignores 32-bit ABIs`(
        @TempDir tempDir: File,
    ) {
        val aar = File(tempDir, "sample.aar")
        ZipOutputStream(aar.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("jni/arm64-v8a/libGood.so"))
            zip.write(elf64(listOf(0x4000L)))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("jni/armeabi-v7a/lib32.so"))
            zip.write(ByteArray(32)) // 32-bit ignored
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("classes.jar"))
            zip.write(ByteArray(16))
            zip.closeEntry()
        }
        val aarChecks = ElfAlignmentPolicy.checkArchive(aar)
        assertEquals(1, aarChecks.size)
        assertTrue(aarChecks.single().passes)

        val apk = File(tempDir, "sample.apk")
        ZipOutputStream(apk.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("lib/x86_64/libBad.so"))
            zip.write(elf64(listOf(0x1000L)))
            zip.closeEntry()
        }
        val apkChecks = ElfAlignmentPolicy.checkArchive(apk)
        assertEquals(1, apkChecks.size)
        assertFalse(apkChecks.single().passes)
    }

    companion object {
        fun elf64(loadAlignments: List<Long>): ByteArray {
            val phoff = 64
            val phentsize = 56
            val bytes = ByteArray(phoff + phentsize * loadAlignments.size)
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            bytes[0] = 0x7F
            bytes[1] = 'E'.code.toByte()
            bytes[2] = 'L'.code.toByte()
            bytes[3] = 'F'.code.toByte()
            bytes[4] = 2 // ELFCLASS64
            bytes[5] = 1 // ELFDATA2LSB
            buf.putLong(32, phoff.toLong())
            buf.putShort(54, phentsize.toShort())
            buf.putShort(56, loadAlignments.size.toShort())
            loadAlignments.forEachIndexed { idx, align ->
                val off = phoff + idx * phentsize
                buf.putInt(off, 1) // PT_LOAD
                buf.putLong(off + 48, align)
            }
            return bytes
        }
    }
}
