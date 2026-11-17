package dev.cubentia.buildlogic.functional

import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class CheckLearnerTextFunctionalTest {
    @Test
    fun `checkLearnerText accepts baseline resources and rejects a new resource`() {
        val fixture = AndroidFixture("check-learner-text", plugins = listOf("cubentia.android-app"))
        fixture.write("gradle/libs.versions.toml", File("../gradle/libs.versions.toml").readText())
        fixture.write("learner-text-baseline.txt", File("../app/learner-text-baseline.txt").readText())

        val stringsPath = "src/main/res/values/strings.xml"
        fixture.write(
            stringsPath,
            """
            <resources>
                <string name="app_name">Cubentia</string>
            </resources>
            """.trimIndent(),
        )
        val clean = fixture.build("checkLearnerText")
        assertEquals(TaskOutcome.SUCCESS, clean.task(":checkLearnerText")?.outcome, clean.output)

        fixture.write(
            stringsPath,
            """
            <resources>
                <string name="app_name">Cubentia</string>
                <string name="learner_text_violation">Unexpected learner text</string>
            </resources>
            """.trimIndent(),
        )
        val violating = fixture.buildAndFail("checkLearnerText")
        assertEquals(TaskOutcome.FAILED, violating.task(":checkLearnerText")?.outcome, violating.output)
        assertTrue("Learner-facing string literals found" in violating.output, violating.output)
        assertTrue(
            violating.output.lineSequence().any { "$stringsPath:3:" in it && "\"learner_text_violation\"" in it },
            violating.output,
        )
    }
}
