package dev.cubentia.app.solver

import dev.cubentia.app.di.SolverModule
import dev.cubentia.solver.PruningTableProvider
import dev.cubentia.solver.PruningTables
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/**
 * Story 3.8 I/O matrix for the selected production delivery (bundled asset, first-run fallback in
 * `filesDir`) through the production [PruningTableSolverSource] seam.
 */
class ProductionTableDeliveryTest {
    @TempDir lateinit var dataDir: File

    private val generations = AtomicInteger()

    private fun source(
        bundled: () -> InputStream,
        generate: (() -> Unit) -> PruningTables = {
            generations.incrementAndGet()
            tables
        },
    ) = PruningTableSolverSource(PruningTableProvider(SolverModule.fallbackFile(dataDir), generate = generate, bundled = bundled))

    private val missingAsset: () -> InputStream = { throw FileNotFoundException(SolverModule.TABLE_ASSET) }

    @Test
    fun `fallback file is app-private non-evictable storage, never the cache dir`() {
        assertEquals(File(dataDir, "files/solver/pruning-v1.bin"), SolverModule.fallbackFile(dataDir))
        assertEquals("solver/pruning-v1.bin", SolverModule.TABLE_ASSET)
    }

    @Test
    fun `cold process with the bundled asset is ready without generation or private writes`() =
        runBlocking {
            val source = source({ ByteArrayInputStream(assetBytes) })
            assertFalse(source.isReady)
            assertTrue(source.load() is SolverLoad.Ready)
            assertTrue(source.isReady)
            assertEquals(0, generations.get())
            assertFalse(SolverModule.fallbackFile(dataDir).exists())
        }

    @Test
    fun `source forwards generation signal and retained solver never signals`() =
        runBlocking {
            var signals = 0
            val source =
                source(missingAsset, generate = {
                    assertEquals(1, signals)
                    tables
                })
            assertTrue(source.load { signals++ } is SolverLoad.Ready)
            assertEquals(1, signals)
            assertTrue(source.load { error("ready must not signal") } is SolverLoad.Ready)
        }

    @Test
    fun `new process reuses the persisted fallback file without regenerating`() =
        runBlocking {
            assertTrue(source(missingAsset).load() is SolverLoad.Ready)
            assertEquals(1, generations.get())
            assertArrayEquals(assetBytes, SolverModule.fallbackFile(dataDir).readBytes())
            val later = source(missingAsset, generate = { error("must not regenerate") })
            assertTrue(later.load() is SolverLoad.Ready)
        }

    @Test
    fun `corrupt asset and corrupt or short fallback file recover by regeneration, never a crash`() =
        runBlocking {
            val corruptAsset = assetBytes.clone().also { it[100] = (it[100].toInt() xor 1).toByte() }
            val file = SolverModule.fallbackFile(dataDir).apply { parentFile.mkdirs() }
            file.writeBytes(assetBytes.copyOf(64))
            assertTrue(source({ ByteArrayInputStream(corruptAsset) }).load() is SolverLoad.Ready)
            assertEquals(1, generations.get())
            file.writeBytes(assetBytes.clone().also { it[200] = (it[200].toInt() xor 1).toByte() })
            assertTrue(source({ ByteArrayInputStream(assetBytes.copyOf(10)) }).load() is SolverLoad.Ready)
            assertEquals(2, generations.get())
            assertArrayEquals(assetBytes, file.readBytes())
        }

    @Test
    fun `cancel during preparation publishes nothing and the next accept retries`() =
        runBlocking {
            val entered = CompletableDeferred<Unit>()
            val release = CountDownLatch(1)
            val opens = AtomicInteger()
            val source =
                source({
                    if (opens.incrementAndGet() == 1) {
                        entered.complete(Unit)
                        release.await()
                    }
                    ByteArrayInputStream(assetBytes)
                })
            val solve = launch(Dispatchers.Default) { source.load() }
            entered.await()
            solve.cancel()
            release.countDown()
            solve.join()
            assertFalse(source.isReady)
            assertTrue(source.load() is SolverLoad.Ready)
            assertEquals(2, opens.get())
        }

    @Test
    fun `fallback write failure is a sealed failure that leaves the occupant intact and can retry`() =
        runBlocking {
            val occupant = File(dataDir, "files/solver").apply { parentFile.mkdirs() }
            occupant.writeText("occupied")
            val source = source(missingAsset)
            assertEquals(SolverLoad.Failed, source.load())
            assertFalse(source.isReady)
            assertEquals("occupied", occupant.readText())
            assertEquals(listOf("solver"), occupant.parentFile.list()!!.toList())
            occupant.delete()
            assertTrue(source.load() is SolverLoad.Ready)
        }

    private companion object {
        val tables: PruningTables by lazy { PruningTables.generate() }
        val assetBytes: ByteArray by lazy {
            val dir =
                kotlin.io.path
                    .createTempDirectory("asset")
                    .toFile()
            val file = File(dir, "pruning-v1.bin")
            runBlocking { PruningTableProvider(file, generate = { tables }).load() }
            file.readBytes().also { dir.deleteRecursively() }
        }
    }
}
