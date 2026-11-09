package dev.cubentia.domain.content

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.time.Instant

class AlgorithmStatusesTest {
    @Test fun `empty log has no statuses`() {
        assertEquals(emptyMap<AlgorithmId, AlgorithmStatus>(), AlgorithmStatuses.current(emptyList()))
    }

    @Test fun `last event wins independently of timestamp and other Algorithms`() {
        val a = AlgorithmId("a")
        val b = AlgorithmId("b")
        val at = Instant.fromEpochMilliseconds(0)
        assertEquals(
            mapOf(a to AlgorithmStatus.NOT_STARTED, b to AlgorithmStatus.LEARNED),
            AlgorithmStatuses.current(
                listOf(
                    AlgorithmStatusEvent(a, AlgorithmStatus.LEARNING, at),
                    AlgorithmStatusEvent(b, AlgorithmStatus.LEARNED, at),
                    AlgorithmStatusEvent(a, AlgorithmStatus.NOT_STARTED, at),
                ),
            ),
        )
    }
}
