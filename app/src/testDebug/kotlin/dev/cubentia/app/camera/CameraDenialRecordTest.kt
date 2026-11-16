package dev.cubentia.app.camera

import android.app.Application
import android.content.Context
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Story 10.2: the camera-denied-before record behind the permanent-denial rule. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CameraDenialRecordTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Test
    fun `a denial is remembered until the permission is granted`() {
        assertFalse(CameraDenialRecord.readAndMark(context))
        assertTrue(CameraDenialRecord.readAndMark(context))
        CameraDenialRecord.clear(context)
        assertFalse(CameraDenialRecord.readAndMark(context))
    }
}
