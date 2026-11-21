package dev.cubentia.buildlogic.policy

import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Fails a connected (instrumented) test task on any failing JUnit XML result. AGP reports an instrumentation process
 * that crashes before any test runs as a synthetic `testRunFailed` failure in the XML yet exits the task with success.
 */
object ConnectedTestResults {
    /** `classname.name: message` for every `<failure>` or `<error>` in one JUnit XML report. */
    fun failures(xml: String): List<String> {
        val document =
            DocumentBuilderFactory
                .newInstance()
                .newDocumentBuilder()
                .parse(xml.byteInputStream())
        val cases = document.getElementsByTagName("testcase")
        return (0 until cases.length).flatMap { index ->
            val case = cases.item(index) as Element
            listOf("failure", "error").flatMap { tag ->
                val problems = case.getElementsByTagName(tag)
                (0 until problems.length).map { problem ->
                    val text =
                        (problems.item(problem) as Element).let { it.getAttribute("message").ifBlank { it.textContent } }
                    "${case.getAttribute("classname")}.${case.getAttribute("name")}: ${text.trim().lineSequence().first()}"
                }
            }
        }
    }

    /** Every failure in the `TEST-*.xml` reports under [resultsDir]; empty when there are none (or no reports). */
    fun failuresIn(resultsDir: File): List<String> =
        resultsDir
            .walkTopDown()
            .filter { it.isFile && it.name.startsWith("TEST-") && it.extension == "xml" }
            .sortedBy { it.path }
            .flatMap { report -> failures(report.readText()).map { "${report.name}: $it" } }
            .toList()
}
