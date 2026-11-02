package dev.rubikxplorer.solver

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
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

enum class PruningTableSource { GENERATED, CACHE }

sealed interface PruningTableLoadResult {
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
 * Optional solver adapter; no startup policy. Share one instance to serialize callers. The Android
 * shell supplies a file under its private cache directory. Cancellation propagates and is retryable.
 * A failed read is a cache miss; a failed write is an explicit failure and leaves the old file intact.
 */
class PruningTableProvider(
    private val cacheFile: File,
    private val cpuDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val generate: (() -> Unit) -> PruningTables = PruningTables::generate,
) {
    private val mutex = Mutex()
    private var ready: PruningTableLoadResult.Ready? = null

    suspend fun load(): PruningTableLoadResult =
        mutex.withLock {
            ready?.let { return@withLock it }
            try {
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
            if (cacheFile.length() != FILE_BYTES) {
                null
            } else {
                DataInputStream(FileInputStream(cacheFile)).use { input ->
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
                    if (!MessageDigest.isEqual(checksum, digest.digest()) || input.read() != -1) {
                        null
                    } else {
                        PruningTables(tables, 0)
                    }
                }
            }
        } catch (_: IOException) {
            null
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
