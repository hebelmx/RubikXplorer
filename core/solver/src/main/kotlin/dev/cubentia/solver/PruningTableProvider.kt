package dev.cubentia.solver

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

enum class PruningTableSource { BUNDLED, GENERATED, CACHE }

sealed interface PruningTableLoadResult {
    /** [cacheReadNanos] is the validated read of the bundled asset or the private file, whichever served or missed. */
    data class Ready(
        val tables: PruningTables,
        val source: PruningTableSource,
        val generationNanos: Long,
        val cacheReadNanos: Long,
        val cacheWriteNanos: Long,
    ) : PruningTableLoadResult

    data class Failed(
        val reason: String,
    ) : PruningTableLoadResult
}

/**
 * Authored in-house storage plumbing; no third-party solver code.
 * Solver adapter; no startup policy. Share one instance to serialize callers. Cancellation
 * propagates and is retryable. A failed read is a miss; a failed write is an explicit failure and
 * leaves the old file intact.
 *
 * Order (Story 3.8): [bundled], when given, is a stream of the same versioned, checksummed file
 * shipped in the APK (production delivery); it is read on [ioDispatcher] and fully validated. If it
 * is absent or invalid, the private [cacheFile] is tried, then tables are generated on
 * [cpuDispatcher] and written atomically to [cacheFile] (the first-run path, kept as a fallback).
 */
class PruningTableProvider(
    private val cacheFile: File,
    private val cpuDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val generate: (() -> Unit) -> PruningTables = PruningTables::generate,
    private val bundled: (() -> InputStream)? = null,
) {
    private val mutex = Mutex()
    private var ready: PruningTableLoadResult.Ready? = null

    suspend fun load(): PruningTableLoadResult =
        mutex.withLock {
            ready?.let { return@withLock it }
            try {
                if (bundled != null) {
                    val bundledStart = System.nanoTime()
                    val shipped = withContext(ioDispatcher) { readBundled(bundled) }
                    if (shipped != null) {
                        val nanos = System.nanoTime() - bundledStart
                        return@withLock PruningTableLoadResult
                            .Ready(shipped, PruningTableSource.BUNDLED, 0, nanos, 0)
                            .also { ready = it }
                    }
                }
                val readStart = System.nanoTime()
                val cached = withContext(ioDispatcher) { read() }
                val readNanos = System.nanoTime() - readStart
                if (cached != null) {
                    PruningTableLoadResult.Ready(cached, PruningTableSource.CACHE, 0, readNanos, 0)
                } else {
                    val tables =
                        withContext(cpuDispatcher) {
                            val context = currentCoroutineContext()
                            generate { context.ensureActive() }
                        }
                    val writeStart = System.nanoTime()
                    withContext(ioDispatcher) { write(tables) }
                    PruningTableLoadResult.Ready(
                        tables,
                        PruningTableSource.GENERATED,
                        tables.generationNanos,
                        readNanos,
                        System.nanoTime() - writeStart,
                    )
                }.also { ready = it }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: IOException) {
                PruningTableLoadResult.Failed(failure.message ?: "pruning-cache-io-failed")
            } catch (failure: Exception) {
                PruningTableLoadResult.Failed(failure.message ?: "pruning-table-preparation-failed")
            }
        }

    private fun read(): PruningTables? =
        try {
            // Fixed length checked before allocating; no size or count is trusted from disk.
            if (cacheFile.length() != FILE_BYTES) null else FileInputStream(cacheFile).use(::decode)
        } catch (_: IOException) {
            null
        }

    @Suppress("TooGenericExceptionCaught") // Any failure opening the shipped asset is a miss, never a crash.
    private fun readBundled(open: () -> InputStream): PruningTables? =
        try {
            open().use(::decode)
        } catch (_: IOException) {
            null
        } catch (_: RuntimeException) {
            null
        }

    /** Sizes come from the coordinate definitions, never from the stream; trailing bytes reject it. */
    private fun decode(stream: InputStream): PruningTables? {
        val input = DataInputStream(stream)
        if (input.readInt() != MAGIC || input.readInt() != VERSION) return null
        val checksum = ByteArray(32).also(input::readFully)
        val digest = MessageDigest.getInstance("SHA-256")
        val tables =
            PruningTables.pairs.map { (a, b) ->
                ByteArray(a.size * b.size).also {
                    input.readFully(it)
                    digest.update(it)
                }
            }
        return if (!MessageDigest.isEqual(checksum, digest.digest()) || input.read() != -1) null else PruningTables(tables, 0)
    }

    private suspend fun write(tables: PruningTables) {
        val parent = cacheFile.absoluteFile.parentFile
        if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Cannot create pruning cache directory")
        val temp = File.createTempFile("pruning-", ".tmp", parent)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            tables.distances.forEach(digest::update)
            FileOutputStream(temp).use { file ->
                val output = DataOutputStream(file)
                output.writeInt(MAGIC)
                output.writeInt(VERSION)
                output.write(digest.digest())
                for (table in tables.distances) {
                    currentCoroutineContext().ensureActive()
                    output.write(table)
                }
                output.flush()
                file.fd.sync()
            }
            currentCoroutineContext().ensureActive()
            Files.move(temp.toPath(), cacheFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temp.delete()
        }
    }

    companion object {
        private const val MAGIC = 0x52585054

        // Bump for coordinate ordering, move metric, projections, or serialization changes.
        private const val VERSION = 1
        private val FILE_BYTES = PruningTables.expectedByteSize.toLong() + 40
    }
}
