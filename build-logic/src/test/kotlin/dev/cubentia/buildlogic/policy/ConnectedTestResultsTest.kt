package dev.cubentia.buildlogic.policy

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ConnectedTestResultsTest {
    @Test
    fun `an instrumentation crash before any test is a failure`() {
        assertEquals(
            listOf("android-test.testRunFailed: Test run failed to complete. Instrumentation run failed due to Process crashed."),
            ConnectedTestResults.failures(CRASHED),
        )
    }

    @Test
    fun `passing and skipped tests are not failures`() {
        assertEquals(emptyList<String>(), ConnectedTestResults.failures(PASSED))
    }

    @Test
    fun `reports are read from every device directory`(
        @TempDir dir: File,
    ) {
        File(dir, "TEST-a.xml").writeText(PASSED)
        File(dir, "device").mkdirs()
        File(dir, "device/TEST-b.xml").writeText(CRASHED)
        File(dir, "test-result.pb").writeText("not xml")
        assertEquals(1, ConnectedTestResults.failuresIn(dir).size)
        assertEquals(emptyList<String>(), ConnectedTestResults.failuresIn(File(dir, "missing")))
    }

    private companion object {
        const val CRASHED =
            """<?xml version='1.0' encoding='UTF-8' ?>
<testsuites tests="1" failures="1" errors="0" skipped="0" time="0.000">
  <testsuite name="android-test" tests="1" failures="1" errors="0" skipped="0" time="0.000">
    <testcase name="testRunFailed" classname="android-test" time="0.000">
      <failure>Test run failed to complete. Instrumentation run failed due to Process crashed.</failure>
    </testcase>
  </testsuite>
</testsuites>"""
        const val PASSED =
            """<?xml version='1.0' encoding='UTF-8' ?>
<testsuites tests="2" failures="0" errors="0" skipped="1">
  <testsuite name="dev.cubentia.app.X" tests="2" failures="0" errors="0" skipped="1">
    <testcase name="a" classname="dev.cubentia.app.X" time="0.1" />
    <testcase name="b" classname="dev.cubentia.app.X" time="0.0"><skipped /></testcase>
  </testsuite>
</testsuites>"""
    }
}
