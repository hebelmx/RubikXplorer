package dev.cubentia.app.ui.scan

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ScanCoachMarkRecordTest {
    @Test fun `the coach mark is pending until shown, then never again`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        assertTrue(ScanCoachMarkRecord.pending(context))
        ScanCoachMarkRecord.markShown(context)
        assertFalse(ScanCoachMarkRecord.pending(context))
        assertFalse(ScanCoachMarkRecord.pending(context))
    }
}
