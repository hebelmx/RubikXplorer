package dev.cubentia.domain.input

import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ViewOrbitTest {
    @Test
    fun `default drag clamp wrap and arrow steps`() {
        assertEquals(0.0, (ViewOrbit.DEFAULT.eye() - ViewPose.DEFAULT_EYE).length(), 1e-9)
        val orbit = ViewOrbit(0.0, 0.0)
        assertEquals(ViewOrbit(-45.0, 0.0), orbit.dragged(100.0, 0.0, 400.0))
        assertEquals(ViewOrbit(0.0, 45.0), orbit.dragged(0.0, 100.0, 400.0))
        assertEquals(85.0, orbit.rotated(0.0, 1000.0).pitchDegrees)
        assertEquals(-85.0, orbit.rotated(0.0, -1000.0).pitchDegrees)
        assertEquals(45.0, (1..9).fold(orbit) { o, _ -> o.rotated(45.0, 0.0) }.yawDegrees)
        val front = Vector3(0.0, 0.0, 1.5)
        assertTrue(
            ViewPose(400, 400, orbit.dragged(100.0, 0.0, 400.0).eye()).project(front).x > ViewPose(400, 400, orbit.eye()).project(front).x,
        )
    }

    @OptIn(io.kotest.common.ExperimentalKotest::class)
    @Test
    fun `arbitrary rotation and drag sequences preserve radius and limits`() =
        runBlocking<Unit> {
            checkAll(PropTestConfig(seed = 503L, iterations = 200), Arb.list(Arb.int(-10000..10000), 1..100)) { values ->
                var orbit = ViewOrbit.DEFAULT
                values.forEachIndexed { i, value ->
                    orbit =
                        if (i % 2 ==
                            0
                        ) {
                            orbit.rotated(value.toDouble(), -value.toDouble())
                        } else {
                            orbit.dragged(value.toDouble(), value.toDouble(), 400.0)
                        }
                    assertEquals(ViewPose.DEFAULT_EYE.length(), orbit.eye().length(), 1e-9)
                    assertTrue(orbit.pitchDegrees in -85.0..85.0)
                    assertTrue(orbit.yawDegrees > -180 && orbit.yawDegrees <= 180)
                }
            }
        }
}
