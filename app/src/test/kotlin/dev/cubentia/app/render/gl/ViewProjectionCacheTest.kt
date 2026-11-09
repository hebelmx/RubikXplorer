package dev.cubentia.app.render.gl

import dev.cubentia.domain.input.ViewOrbit
import dev.cubentia.domain.input.ViewPose
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ViewProjectionCacheTest {
    @Test fun matrixFollowsEyeAndSurfaceSizeLikePicking() {
        val cache = ViewProjectionCache()
        cache.resize(1080, 1920)
        val default = cache.matrixFor(ViewOrbit.DEFAULT.eye())
        assertArrayEquals(ViewPose(1080, 1920, ViewOrbit.DEFAULT.eye()).viewProjection(), default, 0f)
        val eye = ViewOrbit.DEFAULT.rotated(45.0, -45.0).eye()
        val rotated = cache.matrixFor(eye)
        assertArrayEquals(ViewPose(1080, 1920, eye).viewProjection(), rotated, 0f)
        assertFalse(default.contentEquals(rotated))
        cache.resize(800, 600)
        assertArrayEquals(ViewPose(800, 600, eye).viewProjection(), cache.matrixFor(eye), 0f)
    }
}
