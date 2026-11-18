package dev.cubentia.domain.recognition

import dev.cubentia.domain.recognition.evaluation.matrix.MatrixCell
import dev.cubentia.domain.recognition.evaluation.matrix.MatrixFeature
import dev.cubentia.domain.recognition.evaluation.matrix.MatrixMarkdown
import dev.cubentia.domain.recognition.evaluation.matrix.MatrixRuns
import dev.cubentia.domain.recognition.evaluation.matrix.MatrixScoring
import dev.cubentia.domain.recognition.evaluation.matrix.MatrixTruth
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class MatrixEvaluationTest {
    @TempDir lateinit var directory: File
    private val solved = "WWWWWWWWWRRRRRRRRRGGGGGGGGGYYYYYYYYYOOOOOOOOOBBBBBBBBB"

    private fun cell(
        feature: MatrixFeature,
        light: String = "warm",
        device: String = "sm-s721u1",
    ) = checkNotNull(MatrixCell.parse("${device}_stka_${light}_${feature.token}"))

    private fun truth(vararg rows: String) =
        MatrixTruth.load(File(directory, "truth.csv").apply { writeText(rows.joinToString("\n", "cell,run,truth\n")) })

    @Test fun `parses all features and counts only matching run lines`() {
        val records =
            listOf(
                "guidedScan run elapsedMs=100 repromptFaces=1 corrections=0 valid=true outcome=ACCEPTED state=$solved initial=$solved",
                "quickScan run elapsedMs=100 fallbackFaces=1 repromptFaces=1 corrections=0 outcome=ACCEPTED state=$solved initial=$solved",
                "quickCheck run kind=Partial captures=2 elapsedMs=234 certainty=VERIFIED matches=true observed=U:WWWWWWWWW expected=$solved expectedHome=$solved",
                "recognize run elapsedMs=21 outcome=SEVERAL case=- candidates=case-oll-27,case-oll-28 captures=2",
                "placement run elapsedMs=7 outcome=SUGGESTED suggested=stage-02-first-cross accepted=none",
            )
        val lines =
            records.map { "11-18 12:00:00.000 100 101 D CubentiaScanDiag: $it device=SM-S721U1" } +
                listOf("quickCheck,kind=Full", "quickScan frame detected=true", "unrelatedguidedScan run foo=bar")
        MatrixFeature.entries.zip(records).forEach { (feature, record) ->
            val run = MatrixRuns.parse(cell(feature), lines.asSequence()).single()
            assertEquals(1, run.index)
            assertEquals("SM-S721U1", run["device"])
            assertEquals(
                record.substringAfter(" run ") + " device=SM-S721U1",
                run.fields.entries.joinToString(" ") { "${it.key}=${it.value}" },
            )
        }
    }

    @Test fun `skips malformed and unknown log names and accepts absent directory`() {
        for (name in listOf(
            "foo.log",
            "sm-s721u1_stka_warm_other.log",
            "sm-s721u1_stka_studio_guided.log",
            "SM-S721U1_stka_warm_guided.log",
        )) {
            File(directory, name).writeText("guidedScan run outcome=CANCELLED device=SM-S721U1")
        }
        val loaded = MatrixRuns.load(directory)
        assertEquals(cell(MatrixFeature.GUIDED), loaded.cells.keys.single())
        assertEquals(3, loaded.skipped.size)
        assertTrue(MatrixRuns.load(File(directory, "absent")).cells.isEmpty())
    }

    @Test fun `truth normalizes scramble to home and indexed rows override defaults`() {
        val guided = cell(MatrixFeature.GUIDED)
        val quick = cell(MatrixFeature.QUICK_SCAN)
        val lookup =
            truth(
                "# ignored",
                "${guided.name},*,R U",
                "${guided.name},1,R U x",
                "${guided.name},3,",
                "${quick.name},*,",
                "${cell(MatrixFeature.QUICK_CHECK).name},*,mismatch",
                "${cell(MatrixFeature.RECOGNIZE).name},*,LL_SOLVED",
                "${cell(MatrixFeature.PLACEMENT).name},*,SOLVED",
            )
        val runs = MatrixRuns.parse(guided, List(3) { "guidedScan run outcome=ACCEPTED" }.asSequence())
        assertEquals(lookup[runs[0]], lookup[runs[1]])
        assertTrue(lookup[runs[0]] != solved)
        assertEquals(solved, lookup[runs[2]])
        assertEquals(solved, lookup[MatrixRuns.parse(quick, sequenceOf("quickScan run outcome=ACCEPTED")).single()])
    }

    @Test fun `bad truth identifies file line and reason`() {
        val cases =
            listOf(
                "${cell(MatrixFeature.GUIDED).name},1,R Z" to "invalid scramble",
                "${cell(MatrixFeature.QUICK_CHECK).name},*,maybe" to "unknown truth",
                "${cell(MatrixFeature.RECOGNIZE).name},0,case-oll-27" to "invalid run",
                "${cell(MatrixFeature.PLACEMENT).name},2,stage-bad" to "unknown truth",
                "bad,*,R" to "invalid cell",
            )
        for ((row, reason) in cases) {
            val error = assertThrows(IllegalArgumentException::class.java) { truth("# comment", row) }
            assertTrue(error.message.orEmpty().contains("truth.csv:3: $reason"))
        }
    }

    @Test fun `scan metrics count blanks corrections and wrong accepts with indoor verdicts`() {
        val feature = MatrixFeature.GUIDED
        val warm = cell(feature)
        val low = cell(feature, "low")
        val other = cell(feature, device = "other")
        val altered = "R-WWR" + solved.drop(5)
        val lookup = truth("${warm.name},*,", "${low.name},*,", "${other.name},*,")
        val runs =
            MatrixRuns.parse(
                warm,
                sequenceOf(
                    "guidedScan run elapsedMs=30000 corrections=3 outcome=ACCEPTED valid=true state=$solved initial=$altered",
                    "guidedScan run elapsedMs=60000 corrections=4 outcome=ACCEPTED valid=true state=R${solved.drop(1)} initial=$solved",
                    "guidedScan run elapsedMs=10 corrections=0 outcome=CANCELLED valid=false state=- initial=-",
                ),
            )
        val metrics = MatrixScoring.score(feature, runs, lookup).associateBy { it.name }
        assertTrue(metrics.getValue("Valid with ≤ 3 corrections").value.startsWith("1/3"))
        assertEquals("MISSED", metrics.getValue("Accepted median time").verdict)
        assertTrue(metrics.getValue("Sticker accuracy").value.startsWith("95/96"))
        assertTrue(metrics.getValue("Initial-read accuracy").value.startsWith("94/96"))
        assertTrue(metrics.getValue("Confidently wrong").value.startsWith("1/96"))
        assertTrue(metrics.getValue("Accepted but wrong").value.startsWith("1/2"))
        assertEquals("NO DATA", MatrixScoring.score(feature, runs.map { it.copy(cell = low) }, lookup)[2].verdict)
        assertEquals("NO DATA", MatrixScoring.score(feature, runs.map { it.copy(cell = other) }, lookup)[0].verdict)
    }

    @Test fun `scores Quick Check recognition and placement and identifies missing truth`() {
        fun score(
            feature: MatrixFeature,
            expected: String,
            vararg lines: String,
        ): Map<String, String> {
            val cell = cell(feature)
            val runs = MatrixRuns.parse(cell, lines.asSequence().map { "${feature.tag} run $it" })
            return MatrixScoring
                .score(
                    feature,
                    runs,
                    truth("${cell.name},*,$expected"),
                ).associate { it.name to "${it.value} ${it.verdict}" }
        }
        val check =
            score(
                MatrixFeature.QUICK_CHECK,
                "match",
                "kind=Partial captures=3 certainty=VERIFIED matches=false observed=U:RWWWRWWWW expectedHome=$solved",
                "kind=Full captures=2 certainty=VERIFIED matches=-",
                "kind=Full captures=2 certainty=VERIFIED matches=true",
                "kind=- captures=0 certainty=CANCELLED",
            )
        assertTrue(check.getValue("False VERIFIED").startsWith("2/4"))
        assertTrue(check.getValue("Match confirmed").startsWith("1/4"))
        assertTrue(check.getValue("Captures").endsWith("MISSED"))
        assertTrue(check.getValue("Confidently wrong (Partial matches)").startsWith("1/8"))
        assertTrue(check.getValue("Confidently wrong (Partial matches)").endsWith("MISSED"))
        val recognition =
            score(
                MatrixFeature.RECOGNIZE,
                "case-oll-27",
                "outcome=UNIQUE case=case-oll-28 elapsedMs=10",
                "outcome=SEVERAL candidates=case-oll-28,case-oll-29 elapsedMs=1000",
            )
        assertTrue(recognition.getValue("Wrong single Case").endsWith("MISSED"))
        assertTrue(recognition.getValue("SEVERAL missing truth candidate").startsWith("1/1"))
        assertTrue(recognition.getValue("Result time").endsWith("MISSED"))
        val placement =
            score(
                MatrixFeature.PLACEMENT,
                "stage-02-first-cross",
                "suggested=stage-02-first-cross accepted=stage-01-fundamentals",
                "suggested=stage-03-white-layer accepted=none",
            )
        assertTrue(placement.getValue("Suggestion matches truth").startsWith("1/2"))
        assertTrue(placement.getValue("Accepted differs from suggestion").startsWith("2/2"))
        val cell = cell(MatrixFeature.RECOGNIZE)
        val runs = MatrixRuns.parse(cell, sequenceOf("recognize run outcome=SEVERAL candidates=case-oll-28 elapsedMs=10"))
        val missing = MatrixScoring.score(cell.feature, runs, truth())
        assertTrue(missing.first().value.contains("no truth"))
        assertEquals("NO DATA", missing.first().verdict)
    }

    @Test fun `regenerates a populated report and preserves truth indices after malformed records`() {
        val first = cell(MatrixFeature.GUIDED)
        val second = cell(MatrixFeature.GUIDED, device = "lenovo-tb128xu")
        val record = "guidedScan run elapsedMs=100 corrections=0 valid=true outcome=ACCEPTED state=$solved initial=$solved"
        File(directory, "${first.name}.log").writeText(
            "foreign line\n" + "guidedScan run outcome=INVALID broken\n" +
                "guidedScan run outcome=CANCELLED outcome=ACCEPTED\n$record\n",
        )
        File(directory, "${second.name}.log").writeText(record.replace("elapsedMs=100", "elapsedMs=-"))
        val lookup = truth("${first.name},1,R", "${first.name},2,U", "${first.name},3,", "${second.name},1,")
        val loaded = MatrixRuns.load(directory)
        val runs = loaded.cells.getValue(first)
        assertEquals(listOf(1, 2, 3), runs.map { it.index })
        assertTrue(runs.take(2).all { it.fields.isEmpty() })
        assertEquals(solved, lookup[runs.last()])
        assertEquals(listOf("${first.name}.log:2", "${first.name}.log:3"), loaded.malformed)
        val report = File(directory, "report.md").apply { writeText("Before\n${MatrixMarkdown.START}\n${MatrixMarkdown.END}\nAfter\n") }
        dev.cubentia.domain.recognition.evaluation.matrix.main(
            arrayOf(directory.path, File(directory, "truth.csv").path, report.path),
        )
        val content = report.readText()
        val block = content.substringAfter(MatrixMarkdown.START).substringBefore(MatrixMarkdown.END)
        val perDevice = block.substringAfter("### Per device").substringBefore("### Matrix coverage")
        assertTrue("| sm-s721u1 | guided | 3 | 0 | Sticker accuracy | 48/48 (100.000%) | MET |" in perDevice)
        assertTrue("| lenovo-tb128xu | guided | 1 | 0 | Accepted median time | — (0/1 timed) | NO DATA |" in perDevice)
        assertTrue("| All devices | guided | 4 | 0 | Accepted median time | 100.0 ms (1/2 timed) | NO DATA |" in block)
        assertTrue("guided: 2/24 cells present. Devices lenovo-tb128xu, sm-s721u1. Cubes stka. Lights warm." in block)
        assertTrue("${first.name}.log:2" in block)
        assertTrue(content.startsWith("Before\n${MatrixMarkdown.START}"))
        assertTrue(content.endsWith("${MatrixMarkdown.END}\nAfter\n"))
    }

    @Test fun `empty matrix has no data and only marker contents change`() {
        val old = "Before\r\n${MatrixMarkdown.START}\r\nold\r\n${MatrixMarkdown.END}\r\nAfter\r\n"
        val block = MatrixMarkdown.render(MatrixRuns(emptyMap(), emptyList()), truth())
        assertEquals(
            "Before\r\n${MatrixMarkdown.START}\r\n\r\nnew\r\n\r\n${MatrixMarkdown.END}\r\nAfter\r\n",
            MatrixMarkdown.replace(old, "new"),
        )
        assertTrue(MatrixFeature.entries.all { "| All devices | ${it.token} | 0 | 0 |" in block })
        assertTrue(block.lineSequence().filter { it.startsWith("| All devices") }.all { it.endsWith("| NO DATA |") })
    }

    @Test fun `a measured breach is missed despite an untimed run and recognize outcomes are checked against truth`() {
        val cell = cell(MatrixFeature.RECOGNIZE)
        val lookup = truth("${cell.name},1,NOT_AT_LL", "${cell.name},2,case-oll-27", "${cell.name},3,LL_SOLVED")
        val runs =
            MatrixRuns.parse(
                cell,
                sequenceOf(
                    "recognize run elapsedMs=1000 outcome=NOT_AT_LL case=- candidates=-",
                    "recognize run elapsedMs=- outcome=UNIQUE case=case-oll-27 candidates=-",
                    "recognize run elapsedMs=10 outcome=NO_FIT case=- candidates=-",
                ),
            )
        val metrics = MatrixScoring.score(cell.feature, runs, lookup).associateBy { it.name }
        assertEquals("MISSED", metrics.getValue("Result time").verdict)
        assertTrue(metrics.getValue("Outcome matches truth").value.startsWith("2/3"))
    }

    @Test fun `Quick Scan median time is met below 15 s and missed at 15 s`() {
        val cell = cell(MatrixFeature.QUICK_SCAN)

        fun verdict(ms: Int) =
            MatrixScoring
                .score(
                    cell.feature,
                    MatrixRuns.parse(
                        cell,
                        sequenceOf("quickScan run elapsedMs=$ms fallbackFaces=0 repromptFaces=0 corrections=0 outcome=ACCEPTED"),
                    ),
                    truth(),
                ).first { it.name == "Accepted median time" }
                .verdict
        assertEquals("MET", verdict(14_999))
        assertEquals("MISSED", verdict(15_000))
    }

    @Test fun `a non-gating device's rows are reported only`() {
        val other = cell(MatrixFeature.GUIDED, device = "sm-t733")
        File(directory, "${other.name}.log").writeText(
            "guidedScan run elapsedMs=100 corrections=0 valid=true outcome=ACCEPTED state=$solved initial=$solved\n",
        )
        truth("${other.name},*,")
        val block = MatrixMarkdown.render(MatrixRuns.load(directory), MatrixTruth.load(File(directory, "truth.csv")))
        val row = block.lineSequence().first { it.startsWith("| ${other.name} | guided |") && "Valid with" in it }
        assertTrue(row.endsWith("| reported only |"), row)
    }
}
