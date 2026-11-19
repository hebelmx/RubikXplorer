package dev.cubentia.domain.recognition

import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.cube.Facelets
import dev.cubentia.domain.recognition.RecognitionFixtures.CALIBRATION
import dev.cubentia.domain.recognition.RecognitionFixtures.LABS
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** #174: wrong color counts point at the scanned stickers that most likely are the missing color. */
class SuspectRankingTest {
    private val solved = CubeState.SOLVED.toFacelets().colors
    private val counts = "msg.validation.wrong-color-counts"

    private val letters =
        mapOf(
            'W' to Color.WHITE,
            'Y' to Color.YELLOW,
            'G' to Color.GREEN,
            'B' to Color.BLUE,
            'R' to Color.RED,
            'O' to Color.ORANGE,
        )

    /** A yellow between yellow and orange, still a CONFIDENT yellow: about 22 ΔE from yellow, 34 from orange. */
    private val orangeishYellow = Lab(77.0, 15.0, 74.0)

    /**
     * The Story 10.7 Lenovo scan (#174 log): six unposed faces, read in pixel order, all confident, 10 yellow and 8
     * orange. Each color measures its [LABS] value except F's pixel 1, the misread orange.
     */
    private fun issueSamples(): List<FaceSample> =
        listOf(
            Color.GREEN to "OYYOGOYBR",
            Color.RED to "RRBRRBYYG",
            Color.BLUE to "GGGBBGBWW",
            Color.ORANGE to "BOYGOYWWW",
            Color.WHITE to "OYOGWWBYW",
            Color.YELLOW to "RROWYRGBR",
        ).map { (center, read) ->
            val stickers =
                read.mapIndexed { index, letter ->
                    if (center == Color.GREEN && index == 1) orangeishYellow else LABS.getValue(letters.getValue(letter))
                }
            RecognitionFixtures.sample(center = center, stickers = stickers, face = Face.entries.first { Color.home(it) == center })
        }

    @Test fun `the issue scan ranks the orange-looking yellow first and pre-selects it`() {
        val samples = issueSamples()
        val trace = ScanAssembler.trace(samples, CALIBRATION)
        assertEquals(ScanBranch.NO_VALID_REVIEW, trace.branch)
        assertTrue(trace.samples.all { it.uncertain.isEmpty() })

        val review = ScanReview.fromSamples(samples)
        assertTrue(review.blanks.isEmpty())
        assertEquals(10, review.stickers.count { it == Color.YELLOW })
        assertEquals(8, review.stickers.count { it == Color.ORANGE })
        val misread = requireNotNull(review.evidence).labs.indexOf(orangeishYellow)
        assertNotEquals(-1, misread)
        assertEquals(Color.YELLOW, review.stickers[misread])

        val suspects = review.suspects
        assertEquals(SuspectRanking.MAX_SUSPECTS, suspects.size)
        assertEquals(Suspect(misread, Color.ORANGE), suspects.first())
        assertTrue(suspects.all { review.stickers[it.index] == Color.YELLOW && it.likely == Color.ORANGE })
        // The other yellows tie, so the lowest net indexes follow.
        val others = review.stickers.indices.filter { it % 9 != 4 && it != misread && review.stickers[it] == Color.YELLOW }
        assertEquals(others.take(2), suspects.drop(1).map { it.index })
        assertEquals(misread, review.selected)

        val problem = review.problems.single { it.reason.key == counts }
        assertEquals(suspects.map { it.index }, problem.positions)
        assertEquals(problem.positions, problem.piece)
        assertTrue(review.outlined.containsAll(problem.positions))

        val painted = review.paint(Color.ORANGE)
        assertNull(requireNotNull(painted.evidence).labs[misread])
        assertTrue(painted.problems.none { it.reason.key == counts })
        assertTrue(painted.suspects.isEmpty())
    }

    /** Solved, with L's sticker 38 read yellow: 10 yellow, 8 orange. */
    private val oneOrangeAsYellow = solved.toMutableList().also { it[38] = Color.YELLOW }

    private fun evidence(
        colors: List<Color?>,
        vararg overrides: Pair<Int, Lab>,
    ): ScanEvidence {
        val labs = colors.map { it?.let(LABS::getValue) }.toMutableList()
        overrides.forEach { (index, lab) -> labs[index] = lab }
        return ScanEvidence(labs, LABS)
    }

    @Test fun `ties go to the lower sticker index and centers are never suspects`() {
        // Every yellow measures exactly yellow; the D center even measures orange, but centers are fixed.
        val review = ScanReview(oneOrangeAsYellow, null, evidence(oneOrangeAsYellow, 31 to LABS.getValue(Color.ORANGE)))
        assertEquals(listOf(27, 28, 29).map { Suspect(it, Color.ORANGE) }, review.suspects)
    }

    @Test fun `a sticker the Learner painted loses its Lab while other scanned stickers still rank`() {
        val review = ScanReview(oneOrangeAsYellow, 38, evidence(oneOrangeAsYellow, 38 to orangeishYellow))
        assertEquals(Suspect(38, Color.ORANGE), review.suspects.first())
        // Painted, even with the same color: the measurement no longer describes what the Learner set.
        val edited = review.paint(Color.YELLOW)
        assertNull(requireNotNull(edited.evidence).labs[38])
        assertTrue(edited.suspects.none { it.index == 38 })
        assertEquals(listOf(27, 28, 29), edited.suspects.map { it.index })
        assertEquals(listOf(27, 28, 29), edited.problems.single { it.reason.key == counts }.positions)
    }

    @Test fun `without evidence the counts line names no sticker`() {
        val review = ScanReview(oneOrangeAsYellow, null)
        assertTrue(review.suspects.isEmpty())
        val problem = review.problems.single { it.reason.key == counts }
        assertTrue(problem.positions.isEmpty() && problem.piece.isEmpty())
        val unlabeled = ScanReview.from(ScanResult.Full(Facelets(oneOrangeAsYellow), List(54) { StickerConfidence.CONFIDENT }))
        assertNull(unlabeled.evidence)
        assertTrue(unlabeled.suspects.isEmpty())
        // No suspect: the first problem that names stickers (the white-yellow corner 6, 38, 18) is selected instead.
        assertEquals(6, unlabeled.selected)
        val noCenters =
            ScanReview.from(
                ScanResult.Full(
                    Facelets(oneOrangeAsYellow),
                    List(54) { StickerConfidence.CONFIDENT },
                    oneOrangeAsYellow.map(LABS::getValue),
                ),
            )
        assertNull(noCenters.evidence)
    }

    @Test fun `several over and under colors score each candidate against its nearest short color`() {
        // +1 yellow, +1 red, -1 orange, -1 blue.
        val colors =
            solved.toMutableList().also {
                it[38] = Color.YELLOW
                it[46] = Color.RED
            }
        val review = ScanReview(colors, null, evidence(colors, 38 to Lab(70.0, 30.0, 70.0), 46 to Lab(42.0, 20.0, -30.0)))
        // 38 is ~17 from orange, 46 ~22 from blue, then reds (~35 from orange) beat yellows (~56), lowest index first.
        assertEquals(listOf(Suspect(38, Color.ORANGE), Suspect(46, Color.BLUE), Suspect(9, Color.ORANGE)), review.suspects)
    }

    @Test fun `right counts or any blank rank nothing`() {
        assertTrue(SuspectRanking.rank(solved, evidence(solved)).isEmpty())
        val blank = oneOrangeAsYellow.toMutableList<Color?>().also { it[0] = null }
        assertTrue(SuspectRanking.rank(blank, evidence(blank)).isEmpty())
    }

    @Test fun `corrected with no corrections keeps the evidence, the suspects and the top-suspect selection`() {
        val review = ScanReview.fromSamples(issueSamples())
        val same = review.corrected(emptyMap())
        assertEquals(review.evidence, same.evidence)
        assertEquals(review.suspects, same.suspects)
        assertEquals(review.suspects.first().index, same.selected)
        assertEquals(review, same)
    }

    @Test fun `a correction drops that sticker's Lab and re-ranks`() {
        val review = ScanReview(oneOrangeAsYellow, null, evidence(oneOrangeAsYellow, 38 to orangeishYellow))
        assertEquals(Suspect(38, Color.ORANGE), review.suspects.first())
        // Corrected to the same color: the Learner's edit, so no longer scan evidence.
        val edited = review.corrected(mapOf(38 to Color.YELLOW))
        assertNull(requireNotNull(edited.evidence).labs[38])
        assertEquals(listOf(27, 28, 29), edited.suspects.map { it.index })
        assertEquals(27, edited.selected)
        // Corrected to orange: counts are right, nothing to suspect.
        val fixed = review.corrected(mapOf(38 to Color.ORANGE))
        assertTrue(fixed.suspects.isEmpty())
        assertTrue(fixed.problems.isEmpty())
        assertNull(fixed.selected)
    }

    @Test fun `painting the last blank into wrong counts moves the selection to the top suspect`() {
        val blank = oneOrangeAsYellow.toMutableList<Color?>().also { it[0] = null }
        val review = ScanReview(blank, 0, evidence(oneOrangeAsYellow, 38 to orangeishYellow))
        assertTrue(review.suspects.isEmpty())
        val painted = review.paint(Color.WHITE)
        assertTrue(painted.blanks.isEmpty())
        assertEquals(Suspect(38, Color.ORANGE), painted.suspects.first())
        assertEquals(38, painted.selected)
        // Without evidence the selection stays on the painted sticker.
        assertEquals(0, ScanReview(blank, 0).paint(Color.WHITE).selected)
    }

    @Test fun `select keeps the evidence and equality includes it`() {
        val review = ScanReview(oneOrangeAsYellow, null, evidence(oneOrangeAsYellow))
        assertEquals(review.evidence, review.select(28).evidence)
        assertNotEquals(review, ScanReview(oneOrangeAsYellow, null))
        assertEquals(review, ScanReview(oneOrangeAsYellow, null, evidence(oneOrangeAsYellow)))
        assertEquals(review.hashCode(), ScanReview(oneOrangeAsYellow, null, evidence(oneOrangeAsYellow)).hashCode())
    }
}
