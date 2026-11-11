package dev.cubentia.solver

import dev.cubentia.domain.cube.CubeState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Clean-room projection checks from https://kociemba.org/math/pruning.htm; cache tests are in-house. */
class PruningTablesTest {
    @TempDir lateinit var directory: File

    @Test
    fun `all projections are complete and consistent on every edge`() {
        val solved = CubeState.SOLVED.cubies
        PruningTables.pairs.forEachIndexed { index, (first, second) ->
            val values = tables.distances[index]
            assertEquals(0, tables.distance(index, first.encode(solved), second.encode(solved)))
            val a = MoveTables.transitions(first)
            val b = MoveTables.transitions(second)
            val moves = MoveTables.moves(first).size
            for (state in values.indices) {
                val distance = values[state].toInt()
                assertTrue(distance >= 0)
                var descends = distance == 0
                for (move in 0 until moves) {
                    val next = a[state / second.size * moves + move] * second.size + b[state % second.size * moves + move]
                    val other = values[next].toInt()
                    if (kotlin.math.abs(distance - other) > 1) fail<Unit>("Inconsistent $index:$state")
                    if (other == distance - 1) descends = true
                }
                assertTrue(descends)
            }
        }
        assertEquals(4031685, tables.byteSize)
        assertEquals(0, tables.phase1Distance(0, 0, 0))
        assertEquals(0, tables.phase2Distance(0, 0, 0))
        assertThrows(IllegalArgumentException::class.java) { tables.phase1Distance(-1, 0, 0) }
        println("Pruning host evidence: bytes=${tables.byteSize}, generationNanos=${tables.generationNanos}")
    }

    @Test
    fun `cold warm corrupt truncated oversized and version mismatched caches`() =
        runBlocking {
            val file = File(directory, "tables")
            val calls = AtomicInteger()

            fun provider() =
                PruningTableProvider(file, generate = {
                    calls.incrementAndGet()
                    tables
                })
            val cold = provider().load() as PruningTableLoadResult.Ready
            assertEquals(PruningTableSource.GENERATED, cold.source)
            assertEquals(PruningTableSource.CACHE, (provider().load() as PruningTableLoadResult.Ready).source)
            val valid = file.readBytes()
            for (broken in listOf(
                valid.copyOf(12),
                valid + byteArrayOf(0),
                valid.clone().also { it[7] = 99 },
                valid.clone().also {
                    it[50] =
                        (it[50].toInt() xor 1).toByte()
                },
            )) {
                file.writeBytes(broken)
                assertEquals(PruningTableSource.GENERATED, (provider().load() as PruningTableLoadResult.Ready).source)
            }
            assertEquals(5, calls.get())
            val warm = provider().load() as PruningTableLoadResult.Ready
            tables.distances.zip(warm.tables.distances).forEach { (a, b) -> assertArrayEquals(a, b) }
            assertEquals(0L, warm.generationNanos)
        }

    @Test
    fun `concurrent callers generate once and retain ready tables`() =
        runBlocking {
            val calls = AtomicInteger()
            val provider =
                PruningTableProvider(File(directory, "tables"), generate = {
                    calls.incrementAndGet()
                    tables
                })
            val results = List(8) { async { provider.load() } }.awaitAll()
            assertEquals(1, calls.get())
            assertTrue(results.all { it === results.first() })
        }

    @Test
    fun `cancelled generation does not publish and may retry`() =
        runBlocking {
            val file = File(directory, "tables")
            val calls = AtomicInteger()
            val provider =
                PruningTableProvider(file, generate = {
                    if (calls.incrementAndGet() == 1) throw CancellationException("cancelled")
                    tables
                })
            assertThrows(CancellationException::class.java) { runBlocking { provider.load() } }
            assertFalse(file.exists())
            assertTrue(provider.load() is PruningTableLoadResult.Ready)
            assertEquals(listOf("tables"), directory.list()!!.toList())
        }

    @Test
    fun `filesystem failure is an error value and does not retain ready state`() =
        runBlocking {
            val parent = File(directory, "not-directory").apply { writeText("occupied") }
            val provider = PruningTableProvider(File(parent, "tables"), generate = { tables })
            assertTrue(provider.load() is PruningTableLoadResult.Failed)
            assertTrue(provider.load() is PruningTableLoadResult.Failed)
            assertEquals("occupied", parent.readText())
        }

    @Test
    fun `generation uses CPU dispatcher and actual caller cancellation is retryable`() =
        runBlocking {
            Executors.newSingleThreadExecutor { work -> Thread(work, "pruning-test-cpu") }.asCoroutineDispatcher().use { cpu ->
                val started = CompletableDeferred<Unit>()
                val calls = AtomicInteger()
                val file = File(directory, "tables")
                var thread = ""
                val provider =
                    PruningTableProvider(file, cpuDispatcher = cpu, generate = { check ->
                        thread = Thread.currentThread().name
                        if (calls.incrementAndGet() == 1) {
                            started.complete(Unit)
                            while (true) check()
                        }
                        tables
                    })
                withTimeout(5000) {
                    val worker = launch { provider.load() }
                    started.await()
                    worker.cancelAndJoin()
                }
                assertFalse(file.exists())
                assertTrue(provider.load() is PruningTableLoadResult.Ready)
                assertTrue(thread.startsWith("pruning-test-cpu"), thread)
            }
        }

    @Test
    fun `failed atomic replacement preserves destination and cleans temporary file then retries`() =
        runBlocking {
            val destination = File(directory, "tables").apply { mkdir() }
            val marker = File(destination, "keep").apply { writeText("original") }
            val provider = PruningTableProvider(destination, generate = { tables })
            assertTrue(provider.load() is PruningTableLoadResult.Failed)
            assertEquals("original", marker.readText())
            assertEquals(listOf("tables"), directory.list()!!.toList())
            marker.delete()
            destination.delete()
            assertTrue(provider.load() is PruningTableLoadResult.Ready)
        }

    @Test
    fun `generation failure is an error value`() =
        runBlocking {
            val provider = PruningTableProvider(File(directory, "tables"), generate = { error("generation failed") })
            assertEquals(PruningTableLoadResult.Failed("generation failed"), provider.load())
        }

    @Test
    fun `generation invokes cooperative cancellation`() {
        assertThrows(CancellationException::class.java) { PruningTables.generate { throw CancellationException("cancel") } }
    }

    @Test
    fun `bundled asset serves validated tables without generation or private file`() =
        runBlocking {
            val asset = validFileBytes()
            val file = File(directory, "files/tables")
            val calls = AtomicInteger()
            val opens = AtomicInteger()
            val provider =
                PruningTableProvider(file, generate = {
                    calls.incrementAndGet()
                    tables
                }, bundled = {
                    opens.incrementAndGet()
                    ByteArrayInputStream(asset)
                })
            val loaded = provider.load() as PruningTableLoadResult.Ready
            assertEquals(PruningTableSource.BUNDLED, loaded.source)
            assertEquals(0L, loaded.generationNanos)
            tables.distances.zip(loaded.tables.distances).forEach { (a, b) -> assertArrayEquals(a, b) }
            assertSame(loaded, provider.load())
            assertEquals(1, opens.get())
            assertEquals(0, calls.get())
            assertFalse(file.exists())
        }

    @Test
    fun `loaded tables build the move tables on the CPU dispatcher before ready`() =
        runBlocking {
            val asset = validFileBytes()
            Executors.newSingleThreadExecutor { work -> Thread(work, "pruning-test-moves") }.asCoroutineDispatcher().use { cpu ->
                val threads = mutableListOf<String>()
                val counting =
                    object : kotlinx.coroutines.CoroutineDispatcher() {
                        override fun dispatch(
                            context: kotlin.coroutines.CoroutineContext,
                            block: Runnable,
                        ) = cpu.dispatch(context) {
                            threads += Thread.currentThread().name
                            block.run()
                        }
                    }
                val file = File(directory, "files/tables")
                val bundled =
                    PruningTableProvider(file, cpuDispatcher = counting, generate = { error("no generation") }, bundled = {
                        ByteArrayInputStream(asset)
                    }).load() as PruningTableLoadResult.Ready
                assertTrue(bundled.moveTableNanos >= 0)
                assertTrue(threads.isNotEmpty() && threads.all { it == "pruning-test-moves" })
                file.parentFile.mkdirs()
                file.writeBytes(asset)
                threads.clear()
                val cached = PruningTableProvider(file, cpuDispatcher = counting, generate = { error("no generation") }).load()
                assertEquals(PruningTableSource.CACHE, (cached as PruningTableLoadResult.Ready).source)
                assertTrue(threads.isNotEmpty())
            }
        }

    @Test
    fun `invalid or unreadable bundled asset falls back to the private file then generation`() =
        runBlocking {
            val valid = validFileBytes()
            val file = File(directory, "files/tables")
            val calls = AtomicInteger()
            val broken =
                listOf<() -> java.io.InputStream>(
                    { ByteArrayInputStream(valid.copyOf(12)) },
                    { ByteArrayInputStream(valid + byteArrayOf(0)) },
                    { ByteArrayInputStream(valid.clone().also { it[7] = 99 }) },
                    { ByteArrayInputStream(valid.clone().also { it[60] = (it[60].toInt() xor 1).toByte() }) },
                    { throw java.io.FileNotFoundException("solver/pruning-v1.bin") },
                    { throw IllegalStateException("asset manager closed") },
                )
            for (open in broken) {
                file.delete()
                val generated =
                    PruningTableProvider(file, generate = {
                        calls.incrementAndGet()
                        tables
                    }, bundled = open).load() as PruningTableLoadResult.Ready
                assertEquals(PruningTableSource.GENERATED, generated.source)
                val cached = PruningTableProvider(file, generate = { error("no regeneration") }, bundled = open).load()
                assertEquals(PruningTableSource.CACHE, (cached as PruningTableLoadResult.Ready).source)
            }
            assertEquals(broken.size, calls.get())
        }

    @Test
    fun `cancelled bundled read publishes nothing and retries`() =
        runBlocking {
            val asset = validFileBytes()
            val entered = CompletableDeferred<Unit>()
            val release = java.util.concurrent.CountDownLatch(1)
            val opens = AtomicInteger()
            val provider =
                PruningTableProvider(File(directory, "files/tables"), generate = { error("no generation") }, bundled = {
                    if (opens.incrementAndGet() == 1) {
                        entered.complete(Unit)
                        release.await()
                    }
                    ByteArrayInputStream(asset)
                })
            val worker = launch(Dispatchers.Default) { provider.load() }
            entered.await()
            worker.cancel()
            release.countDown()
            worker.join()
            assertTrue(worker.isCancelled)
            val retried = provider.load() as PruningTableLoadResult.Ready
            assertEquals(PruningTableSource.BUNDLED, retried.source)
            assertEquals(2, opens.get())
        }

    private suspend fun validFileBytes(): ByteArray {
        val source = File(directory, "source/tables")
        PruningTableProvider(source, generate = { tables }).load()
        return source.readBytes()
    }

    companion object {
        private val tables by lazy { PruningTables.generate() }
    }
}
