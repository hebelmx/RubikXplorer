package dev.rubikxplorer.domain.input

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.math.sqrt
import kotlin.math.tan

class ViewPoseTest {
    @Test
    fun `origin is centered and projection inverts rays in both viewport orientations`() {
        for ((width, height) in listOf(800 to 600, 600 to 800)) {
            val pose = ViewPose(width, height)
            assertEquals(ScreenPoint(width / 2.0, height / 2.0), pose.project(Vector3(0.0, 0.0, 0.0)))
            for (point in listOf(Vector3(1.0, -1.0, 1.5), Vector3(-1.5, 0.0, -1.0), Vector3(0.0, 1.5, 0.0))) {
                val screen = pose.project(point)
                val ray = pose.ray(screen.x, screen.y)
                assertEquals(0.0, (point - ray.origin).cross(ray.direction).length(), 1e-10)
            }
        }
    }

    @Test
    fun `default camera matrix matches independently calculated look at and perspective`() {
        val matrix = ViewPose(800, 600).viewProjection()
        val radius = sqrt(4.2 * 4.2 + 4.6 * 4.6 + 6.4 * 6.4)
        val horizontal = sqrt(4.2 * 4.2 + 6.4 * 6.4)
        val scale = 1 / tan(Math.toRadians(19.0))
        val right = doubleArrayOf(6.4 / horizontal, 0.0, -4.2 / horizontal)
        val up = doubleArrayOf(-4.2 * 4.6 / (radius * horizontal), horizontal / radius, -6.4 * 4.6 / (radius * horizontal))
        val backward = doubleArrayOf(4.2 / radius, 4.6 / radius, 6.4 / radius)
        for (col in 0..2) {
            assertEquals(right[col] * scale * 0.75, matrix[col * 4].toDouble(), 1e-6)
            assertEquals(up[col] * scale, matrix[col * 4 + 1].toDouble(), 1e-6)
            assertEquals(-31.0 / 29 * backward[col], matrix[col * 4 + 2].toDouble(), 1e-6)
            assertEquals(-backward[col], matrix[col * 4 + 3].toDouble(), 1e-6)
        }
        assertEquals(0.0, matrix[12].toDouble(), 1e-6)
        assertEquals(0.0, matrix[13].toDouble(), 1e-6)
        assertEquals((31 * radius - 60) / 29, matrix[14].toDouble(), 1e-6)
        assertEquals(radius, matrix[15].toDouble(), 1e-6)
    }

    @Test
    fun `GL matrix lands off-center points where picking projects them in both viewport orientations`() {
        for ((width, height) in listOf(800 to 600, 600 to 800)) {
            val pose = ViewPose(width, height)
            val m = pose.viewProjection()
            for (point in listOf(Vector3(1.5, 1.5, 1.5), Vector3(-1.5, 1.0, 1.5), Vector3(1.5, -1.5, -0.5))) {
                val clip = (0..3).map { r -> m[r] * point.x + m[4 + r] * point.y + m[8 + r] * point.z + m[12 + r] }
                val screen = pose.project(point)
                assertEquals(screen.x, (clip[0] / clip[3] + 1) * width / 2, 1e-3)
                assertEquals(screen.y, (1 - clip[1] / clip[3]) * height / 2, 1e-3)
            }
        }
    }
}
