package dev.cubentia.solver.benchmark

import java.lang.management.ManagementFactory
import java.lang.management.MemoryType

/** Host JVM peak heap: the sum of the heap pools' peak usage since [reset] (Story 3.3 method). */
class JvmHeapPoolSampler : HeapSampler {
    private val pools = ManagementFactory.getMemoryPoolMXBeans().filter { it.type == MemoryType.HEAP && it.isValid }

    override fun reset() = pools.forEach { it.resetPeakUsage() }

    override fun sample() = Unit

    override fun peakBytes(): Long = pools.sumOf { it.peakUsage?.used ?: 0L }
}
