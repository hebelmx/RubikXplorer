package dev.cubentia.app.ui.shell

import dev.cubentia.application.port.AlgorithmStatusLoad
import dev.cubentia.domain.content.AlgorithmId
import dev.cubentia.domain.content.AlgorithmStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class AlgorithmOfferTest {
    @Test fun `offer is hidden until loaded and acceptance filters the current statuses`() {
        val ids = listOf(AlgorithmId("a"), AlgorithmId("b"), AlgorithmId("c"))
        assertEquals(emptyList<AlgorithmId>(), algorithmsToOffer(ids, null))
        assertEquals(emptyList<AlgorithmId>(), algorithmsToOffer(ids, AlgorithmStatusLoad.Failed))
        val offered = algorithmsToOffer(ids, AlgorithmStatusLoad.Loaded(emptyMap()))
        assertEquals(ids, offered)
        assertEquals(
            listOf(ids[2]),
            algorithmsToOffer(
                offered,
                AlgorithmStatusLoad.Loaded(
                    mapOf(
                        ids[0] to AlgorithmStatus.LEARNED,
                        ids[1] to AlgorithmStatus.LEARNING,
                        ids[2] to AlgorithmStatus.NOT_STARTED,
                    ),
                ),
            ),
        )
        assertEquals(emptyList<AlgorithmId>(), algorithmsToOffer(offered, AlgorithmStatusLoad.Failed))
    }
}
