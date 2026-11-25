package dev.cubentia.app.camera

import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import dev.cubentia.app.camera.continuous.LiteRtStickerCenterDetector
import dev.cubentia.app.camera.corner.LiteRtCornerDetector
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Optional
import javax.inject.Inject

/** The debug graph (`QUICK_SCAN=BETA`, `CASE_RECOGNITION=GA`) binds both LiteRT detectors behind their optional seams. */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
class CornerDetectorBindingTest {
    @get:Rule
    val hilt = HiltAndroidRule(this)

    @Inject
    internal lateinit var cornerDetector: Optional<CornerDetector>

    @Inject
    internal lateinit var stickerCenterDetector: Optional<StickerCenterDetector>

    @Before
    fun setup() = hilt.inject()

    @Test
    fun debugGraphBindsTheLiteRtCornerDetector() {
        assertTrue("Optional<CornerDetector> is empty in the debug graph", cornerDetector.isPresent)
        assertTrue("Bound ${cornerDetector.get()::class}", cornerDetector.get() is LiteRtCornerDetector)
    }

    /** Story 10.31: the debug graph (`QUICK_SCAN` above OFF) binds the LiteRT v2 INT8 sticker-center detector. */
    @Test
    fun debugGraphBindsTheLiteRtStickerCenterDetector() {
        assertTrue("Optional<StickerCenterDetector> is empty in the debug graph", stickerCenterDetector.isPresent)
        assertTrue("Bound ${stickerCenterDetector.get()::class}", stickerCenterDetector.get() is LiteRtStickerCenterDetector)
    }
}
