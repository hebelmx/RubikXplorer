package dev.cubentia.app.camera.continuous

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.lang.management.ManagementFactory

/** GH #406 I/O matrix: nearest-rank percentiles, the fixed field order, window emission, run capacity, fps and `-` fields. */
class ContinuousStageTimingsTest {
    private val windows = mutableListOf<String>()

    private fun timings(runCapacity: Int = ContinuousStageTimings.RUN_CAPACITY) =
        ContinuousStageTimings(NanoClock { 0L }, { windows += it }, runCapacity = runCapacity)

    /** A frame that reached the mapper, every stage [stageMs] long, the analyzer [analyzerMs], starting at [startMs]. */
    private fun ContinuousStageTimings.read(
        startMs: Long,
        analyzerMs: Long,
        stageMs: Long = 1,
        fitted: Int = 1,
        observations: Int = 1,
    ) = record(ms(startMs), ms(startMs + analyzerMs), ms(stageMs), ms(stageMs), ms(stageMs), ms(stageMs), ms(stageMs), fitted, observations)

    private fun ContinuousStageTimings.skipped(
        startMs: Long,
        analyzerMs: Long,
    ) = record(ms(startMs), ms(startMs + analyzerMs), -1, -1, -1, -1, -1, 0, 0)

    private fun ms(value: Long) = value * 1_000_000L

    private fun field(
        line: String,
        name: String,
    ) = Regex("(?:^| )$name=(\\S+)").find(line)!!.groupValues[1]

    @Test
    fun `known samples give nearest-rank percentiles in the fixed field order`() {
        val t = timings()
        // 10 frames, 100 ms apart, analyzer 1..10 ms.
        for (i in 1..10) t.read(startMs = (i - 1) * 100L, analyzerMs = i.toLong(), stageMs = 2, fitted = 2, observations = 3)
        assertEquals(
            "frames=10 read=10 fps=11.0 detectorMs=2.0/2.0/2.0 inferenceMs=2.0/2.0/2.0 fitMs=2.0/2.0/2.0 sampleMs=2.0/2.0/2.0 " +
                "mapMs=2.0/2.0/2.0 analyzerMs=5.0/9.0/10.0 fitted=20 observations=30",
            t.runLine(),
        )
        assertTrue(windows.isEmpty())
    }

    @Test
    fun `a single sample has p50 equal to p90 equal to max`() {
        val t = timings()
        t.read(startMs = 0, analyzerMs = 7, stageMs = 3)
        val line = t.runLine()!!
        assertEquals("7.0/7.0/7.0", field(line, "analyzerMs"))
        assertEquals("3.0/3.0/3.0", field(line, "fitMs"))
        assertEquals("142.9", field(line, "fps"))
    }

    @Test
    fun `a skipped frame counts only in frames and analyzer and stages without samples print a dash`() {
        val t = timings()
        t.skipped(startMs = 0, analyzerMs = 4)
        assertEquals(
            "frames=1 read=0 fps=250.0 detectorMs=- inferenceMs=- fitMs=- sampleMs=- mapMs=- analyzerMs=4.0/4.0/4.0 fitted=0 observations=0",
            t.runLine(),
        )
        t.read(startMs = 10, analyzerMs = 6)
        val line = t.runLine()!!
        assertEquals("2", field(line, "frames"))
        assertEquals("1", field(line, "read"))
        assertEquals("1.0/1.0/1.0", field(line, "detectorMs"))
        assertEquals("4.0/6.0/6.0", field(line, "analyzerMs"))
    }

    @Test
    fun `fps over a zero-length span is a dash and no frame means no run line`() {
        val t = timings()
        assertNull(t.runLine())
        t.skipped(startMs = 5, analyzerMs = 0)
        assertEquals("-", field(t.runLine()!!, "fps"))
    }

    @Test
    fun `a window line is emitted once 2 s have passed since the window's first frame, then the window restarts`() {
        val t = timings()
        // Frames every 500 ms, 10 ms long: the frame starting at 2000 ms ends 2010 ms after the first frame's start.
        for (i in 0..3) t.read(startMs = i * 500L, analyzerMs = 10)
        assertTrue(windows.isEmpty())
        t.read(startMs = 2000, analyzerMs = 20)
        assertEquals(1, windows.size)
        assertEquals("5", field(windows[0], "frames"))
        assertEquals("10.0/20.0/20.0", field(windows[0], "analyzerMs"))
        assertEquals("2.5", field(windows[0], "fps"))

        // The next window starts at the next frame.
        for (i in 5..8) t.read(startMs = i * 500L, analyzerMs = 30)
        assertEquals(1, windows.size)
        t.skipped(startMs = 4500, analyzerMs = 1)
        assertEquals(2, windows.size)
        assertEquals("5", field(windows[1], "frames"))
        assertEquals("4", field(windows[1], "read"))
        assertEquals("30.0/30.0/30.0", field(windows[1], "analyzerMs"))

        // The run line is not cleared by a window.
        assertEquals("10", field(t.runLine()!!, "frames"))
    }

    @Test
    fun `over capacity the run percentiles cover the latest samples while the counts cover every frame`() {
        val t = timings()
        for (i in 1..5000) t.read(startMs = i * 10L, analyzerMs = i.toLong())
        val line = t.runLine()!!
        assertEquals("5000", field(line, "frames"))
        assertEquals("5000", field(line, "read"))
        // Latest 4096 samples are 905..5000 ms: p50 = sorted[2047], p90 = sorted[3686].
        assertEquals("2952.0/4591.0/5000.0", field(line, "analyzerMs"))
    }

    @Test
    fun `recording does not allocate`() {
        val bean = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
        assumeTrue(bean != null && bean.isThreadAllocatedMemorySupported)
        bean!!.isThreadAllocatedMemoryEnabled = true
        val t = timings()
        // Warm up (class loading, JIT) without reaching the 2 s window.
        for (i in 0 until 1_000) t.record(i.toLong(), i + 1L, 1, 1, 1, 1, 1, 1, 9)
        val before = bean.currentThreadAllocatedBytes
        for (i in 0 until 10_000) t.record(i.toLong(), i + 1L, 1, 1, 1, 1, 1, 1, 9)
        val allocated = bean.currentThreadAllocatedBytes - before
        assertTrue(windows.isEmpty())
        assertTrue(allocated < 64 * 1024, "allocated $allocated bytes")
    }
}
