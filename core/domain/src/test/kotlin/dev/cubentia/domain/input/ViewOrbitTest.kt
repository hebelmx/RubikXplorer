package dev.cubentia.domain.input

import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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

    @Test
    fun `isHome checks tolerance on yaw and pitch including wrap`() {
        assertTrue(ViewOrbit.DEFAULT.isHome())
        assertTrue(ViewOrbit.DEFAULT.rotated(0.9, -0.9).isHome())
        assertFalse(ViewOrbit.DEFAULT.rotated(1.1, 0.0).isHome())
        assertFalse(ViewOrbit.DEFAULT.rotated(0.0, 1.1).isHome())
        // Wrapping around 360° lands back within tolerance of DEFAULT.
        assertTrue(ViewOrbit.DEFAULT.rotated(360.0 - 0.5, 0.4).isHome())
        assertFalse(ViewOrbit.DEFAULT.rotated(180.0, 0.0).isHome())
    }

    @Test
    fun `towards interpolates shortest yaw path and returns target at fraction 1`() {
        val from = ViewOrbit(-170.0, -40.0)
        val target = ViewOrbit(170.0, 20.0)
        // Shortest path from -170 to +170 is -20 degrees, passing through 180.
        val mid = from.towards(target, 0.5)
        assertEquals(180.0, kotlin.math.abs(mid.yawDegrees), 1e-9)
        assertEquals(-10.0, mid.pitchDegrees, 1e-9)
        assertEquals(from, from.towards(target, 0.0))
        assertEquals(from, from.towards(target, Double.NaN))
        assertEquals(target, from.towards(target, 1.0))

        // From -155° back to DEFAULT (~33.3°): shortest delta is -171.7°, so halfway is near +119°.
        val nearWrap = ViewOrbit(-155.0, 10.0)
        val halfwayHome = nearWrap.towards(ViewOrbit.DEFAULT, 0.5)
        assertTrue(halfwayHome.yawDegrees > 90.0)
        assertEquals(ViewOrbit.DEFAULT, nearWrap.towards(ViewOrbit.DEFAULT, 1.0))
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
