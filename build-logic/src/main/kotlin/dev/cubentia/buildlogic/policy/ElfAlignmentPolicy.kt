package dev.cubentia.buildlogic.policy

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

/**
 * Pure-Kotlin ELF64 `PT_LOAD` page-alignment checker for Android 15+ (`targetSdk 36`) 16 KB page-size compliance.
 * Inspects every `arm64-v8a` and `x86_64` `.so` inside AARs (`jni/<abi>/`) or APKs (`lib/<abi>/`) and verifies that
 * every `PT_LOAD` program header has `p_align >= 16384` (`0x4000`) and divisible by `16384`.
 */
object ElfAlignmentPolicy {
    const val REQUIRED_PAGE_SIZE = 16_384L
    private const val ELF_CLASS_64 = 2
    private const val ELF_DATA_LSB = 1
    private const val ELF_DATA_MSB = 2
    private const val PT_LOAD = 1
    private const val ELF64_HEADER_MIN_BYTES = 64
    private val REQUIRED_64BIT_ABIS = setOf("arm64-v8a", "x86_64")

    data class LibraryCheck(
        val source: String,
        val abi: String,
        val library: String,
        val minLoadAlign: Long?,
        val error: String? = null,
    ) {
        val passes: Boolean
            get() =
                error == null &&
                    minLoadAlign != null &&
                    minLoadAlign >= REQUIRED_PAGE_SIZE &&
                    minLoadAlign % REQUIRED_PAGE_SIZE == 0L
    }

    /** Inspects one raw ELF64 shared library byte array. */
    fun checkElf64(
        bytes: ByteArray,
        source: String,
        abi: String,
        library: String,
    ): LibraryCheck {
        if (bytes.size < ELF64_HEADER_MIN_BYTES) {
            return LibraryCheck(source, abi, library, null, "Truncated ELF header (${bytes.size} bytes)")
        }
        if (bytes[0] != 0x7F.toByte() || bytes[1] != 'E'.code.toByte() || bytes[2] != 'L'.code.toByte() || bytes[3] != 'F'.code.toByte()) {
            return LibraryCheck(source, abi, library, null, "Not an ELF binary")
        }
        val elfClass = bytes[4].toInt() and 0xFF
        if (elfClass != ELF_CLASS_64) {
            return LibraryCheck(source, abi, library, null, "Expected ELF64 (class=2) for $abi, got class=$elfClass")
        }
        val order =
            when (bytes[5].toInt() and 0xFF) {
                ELF_DATA_LSB -> ByteOrder.LITTLE_ENDIAN
                ELF_DATA_MSB -> ByteOrder.BIG_ENDIAN
                else -> return LibraryCheck(source, abi, library, null, "Unknown ELF endianness")
            }
        val buf = ByteBuffer.wrap(bytes).order(order)
        val phoff = buf.getLong(32)
        val phentsize = buf.getShort(54).toInt() and 0xFFFF
        val phnum = buf.getShort(56).toInt() and 0xFFFF
        if (phoff < 0 || phentsize < 56 || phnum <= 0) {
            return LibraryCheck(source, abi, library, null, "Invalid program header table (phoff=$phoff, entsize=$phentsize, num=$phnum)")
        }
        val tableEnd = phoff + phnum.toLong() * phentsize.toLong()
        if (tableEnd > bytes.size.toLong()) {
            return LibraryCheck(source, abi, library, null, "Program header table exceeds file length ($tableEnd > ${bytes.size})")
        }
        var minAlign: Long? = null
        for (i in 0 until phnum) {
            val offset = (phoff + i.toLong() * phentsize.toLong()).toInt()
            val pType = buf.getInt(offset)
            if (pType == PT_LOAD) {
                val pAlign = buf.getLong(offset + 48)
                minAlign = if (minAlign == null) pAlign else minOf(minAlign, pAlign)
            }
        }
        if (minAlign == null) {
            return LibraryCheck(source, abi, library, null, "No PT_LOAD segments found")
        }
        val error =
            if (minAlign < REQUIRED_PAGE_SIZE || minAlign % REQUIRED_PAGE_SIZE != 0L) {
                "PT_LOAD p_align=0x${minAlign.toString(16)} ($minAlign) is not 16 KB aligned (required >= 0x4000)"
            } else {
                null
            }
        return LibraryCheck(source, abi, library, minAlign, error)
    }

    /** Inspects every 64-bit native `.so` inside an AAR (`jni/<abi>/<lib>.so`) or APK (`lib/<abi>/<lib>.so`). */
    fun checkArchive(
        archive: File,
        sourceLabel: String = archive.name,
    ): List<LibraryCheck> {
        if (!archive.isFile) return emptyList()
        if (archive.extension.equals("so", ignoreCase = true)) {
            val abi = archive.parentFile?.name.orEmpty()
            return if (abi in REQUIRED_64BIT_ABIS) {
                listOf(checkElf64(archive.readBytes(), sourceLabel, abi, archive.name))
            } else {
                emptyList()
            }
        }
        if (archive.extension.lowercase() !in setOf("aar", "apk", "jar", "zip")) return emptyList()
        val results = mutableListOf<LibraryCheck>()
        ZipFile(archive).use { zip ->
            zip.entries().asSequence().forEach { entry ->
                if (entry.isDirectory || !entry.name.endsWith(".so")) return@forEach
                val abi = extractAbi(entry.name) ?: return@forEach
                if (abi !in REQUIRED_64BIT_ABIS) return@forEach
                val libName = entry.name.substringAfterLast('/')
                val bytes = zip.getInputStream(entry).use { it.readBytes() }
                results += checkElf64(bytes, sourceLabel, abi, libName)
            }
        }
        return results
    }

    /**
     * Names of the LiteRT archives on the classpath when none of [checks] inspected a `libLiteRt*.so`: the runtime is
     * present but its native library escaped the gate (renamed, moved, or only shipped for 32-bit ABIs).
     */
    fun uninspectedLiteRt(
        archives: Collection<File>,
        checks: List<LibraryCheck>,
    ): List<String> {
        val liteRt = archives.map { it.name }.filter { "litert" in it.lowercase() }.sorted()
        return if (liteRt.isNotEmpty() && checks.none { it.library.startsWith("libLiteRt") }) liteRt else emptyList()
    }

    private fun extractAbi(entryName: String): String? {
        val rest =
            when {
                entryName.startsWith("jni/") -> entryName.removePrefix("jni/")
                entryName.startsWith("lib/") -> entryName.removePrefix("lib/")
                else -> return null
            }
        return rest.substringBefore('/', missingDelimiterValue = "").takeIf { it.isNotEmpty() }
    }
}
