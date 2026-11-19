package dev.cubentia.app.render.gl

import android.app.Application
import android.graphics.Bitmap
import dev.cubentia.domain.input.ViewOrbit
import dev.cubentia.domain.input.ViewPose
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CubeGlRendererTest {
    @Test
    fun drawnViewProjectionUsesTheRendererFovAndEye() {
        val renderer = CubeGlRenderer(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888), "", "")
        renderer.onSurfaceChanged(null, 379, 184)
        val eye = ViewOrbit.DEFAULT.rotated(30.0, -20.0).eye()
        renderer.eye = eye
        renderer.fovDegrees = 34.0
        assertArrayEquals(ViewPose(379, 184, eye, 34.0).viewProjection(), renderer.viewProjection(), 0f)
        renderer.fovDegrees = 38.0
        assertArrayEquals(ViewPose(379, 184, eye, 38.0).viewProjection(), renderer.viewProjection(), 0f)
    }
}
