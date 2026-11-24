package dev.cubentia.domain.recognition

import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.cube.Facelets
import dev.cubentia.domain.cube.Notation
import dev.cubentia.domain.cube.NotationResult
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

        // #323: the misread's margin is about 33.5 - 22.4 = 11.2 (qualifies); the exact yellows' is ΔE(Y, O) ≈ 55.9.
        val suspects = review.suspects
        assertEquals(listOf(Suspect(misread, Color.ORANGE)), suspects)
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

    @Test fun `exact reads of a clearly different color are never suspects and centers are never suspects`() {
        // Every yellow measures exactly yellow (margin ΔE(Y, O) ≈ 55.9 > 20); the D center even measures orange (margin
        // about -55.9), but centers are fixed.
        val review = ScanReview(oneOrangeAsYellow, null, evidence(oneOrangeAsYellow, 31 to LABS.getValue(Color.ORANGE)))
        assertTrue(review.suspects.isEmpty())
    }

    @Test fun `a sticker the Learner painted loses its Lab while other scanned stickers still rank`() {
        val review = ScanReview(oneOrangeAsYellow, 38, evidence(oneOrangeAsYellow, 38 to orangeishYellow))
        assertEquals(Suspect(38, Color.ORANGE), review.suspects.first())
        // Painted, even with the same color: the measurement no longer describes what the Learner set.
        val edited = review.paint(Color.YELLOW)
        assertNull(requireNotNull(edited.evidence).labs[38])
        // The remaining yellows measure exactly yellow (margin ≈ 55.9), so nothing qualifies and the line is plain.
        assertTrue(edited.suspects.isEmpty())
        assertTrue(
            edited.problems
                .single { it.reason.key == counts }
                .positions
                .isEmpty(),
        )
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
        // Margins: 46 is ~22.4 from blue and ~87.5 from red (-65.1); 38 is ~16.6 from orange and ~39.4 from yellow
        // (-22.8). Exact reds (ΔE(R, O) ≈ 34.6) and exact yellows (≈ 55.9 to orange) do not qualify.
        assertEquals(listOf(Suspect(46, Color.BLUE), Suspect(38, Color.ORANGE)), review.suspects)
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
        // The remaining yellows are exact (margin ≈ 55.9): no suspect, so the first piece problem's sticker is selected.
        assertTrue(edited.suspects.isEmpty())
        assertEquals(6, edited.selected)
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

    // #323: only stickers that read close to a missing color are suggested.

    /** R' U' R from solved: Up 2 (index 1) reads blue, Up 3 (index 2) orange and Up 9 (index 8) white. */
    private val scrambled =
        CubeState.SOLVED
            .apply((Notation.parse("R' U' R") as NotationResult.Parsed).moves)
            .toFacelets()
            .colors

    private fun assertPlainCountsWithCornerOutlined(
        review: ScanReview,
        painted: Int,
    ) {
        assertTrue(review.suspects.isEmpty())
        assertTrue(review.suspects.none { it.index == 1 })
        val problem = review.problems.single { it.reason.key == counts }
        assertTrue(problem.positions.isEmpty() && problem.piece.isEmpty())
        assertTrue(review.problems.any { it.reason.key != counts && painted in it.piece })
        assertTrue(painted in review.outlined)
    }

    @Test fun `release case A, Up 3 painted blue, suggests no exact blue as orange`() {
        assertEquals(listOf(Color.BLUE, Color.ORANGE), scrambled.subList(1, 3))
        val review = ScanReview(scrambled, null, evidence(scrambled))
        assertTrue(review.problems.isEmpty())
        val painted = review.select(2).paint(Color.BLUE)
        assertEquals(10, painted.stickers.count { it == Color.BLUE })
        assertEquals(8, painted.stickers.count { it == Color.ORANGE })
        assertNull(requireNotNull(painted.evidence).labs[2])
        // Every blue measures exactly blue: margin ΔE(B, O) ≈ 122.8 > 20.
        assertPlainCountsWithCornerOutlined(painted, 2)
    }

    @Test fun `release case B, Up 9 painted blue, suggests no exact blue as white`() {
        assertEquals(Color.WHITE, scrambled[8])
        val review = ScanReview(scrambled, null, evidence(scrambled))
        assertTrue(review.problems.isEmpty())
        val painted = review.select(8).paint(Color.BLUE)
        assertEquals(10, painted.stickers.count { it == Color.BLUE })
        assertEquals(8, painted.stickers.count { it == Color.WHITE })
        // Every blue measures exactly blue: margin ΔE(B, W) ≈ 71.4 > 20.
        assertPlainCountsWithCornerOutlined(painted, 8)
    }

    @Test fun `a sticker that qualifies for two short colors is suggested as the nearer one`() {
        // +2 yellow (38 from orange, 9 from red): -1 orange, -1 red. Sticker 38 measures between orange and red: ~8.7 from
        // orange, ~26.0 from red, ~63.6 from yellow, so both margins (-54.9, -37.6) qualify; orange is nearer.
        val colors =
            solved.toMutableList().also {
                it[38] = Color.YELLOW
                it[9] = Color.YELLOW
            }
        val lab = Lab(60.0, 50.0, 60.0)
        val own = StickerClassifier.deltaE(lab, LABS.getValue(Color.YELLOW))
        assertTrue(StickerClassifier.deltaE(lab, LABS.getValue(Color.RED)) - own <= SuspectRanking.SUSPECT_MARGIN_DELTA_E)
        val review = ScanReview(colors, null, evidence(colors, 38 to lab))
        // Sticker 9 and the other yellows measure exactly yellow (margins ≈ 55.9 to orange, ≈ 87.9 to red).
        assertEquals(listOf(Suspect(38, Color.ORANGE)), review.suspects)
    }

    @Test fun `a margin of exactly the threshold qualifies and just over it does not`() {
        // Orange's center moved to (85, -5, 120): sticker 38 at (85, -5, 90) is 10 from yellow and 30 from orange, a margin
        // of exactly 20.0; at (85, -5, 89.9) the margin is 30.1 - 9.9 = 20.2. Exact yellows' margin is 40.
        val centers = LABS + (Color.ORANGE to Lab(85.0, -5.0, 120.0))

        fun at(b: Double) =
            ScanEvidence(
                oneOrangeAsYellow.mapIndexed { i, color -> if (i == 38) Lab(85.0, -5.0, b) else LABS.getValue(color) },
                centers,
            )
        assertEquals(20.0, SuspectRanking.SUSPECT_MARGIN_DELTA_E)
        assertEquals(listOf(Suspect(38, Color.ORANGE)), SuspectRanking.rank(oneOrangeAsYellow, at(90.0)))
        assertTrue(SuspectRanking.rank(oneOrangeAsYellow, at(89.9)).isEmpty())
    }

    @Test fun `counts off with nothing qualifying keep a plain counts line and fall back to the first piece problem`() {
        // Every sticker measures exactly its read color: the yellows' margin to orange is ≈ 55.9.
        val review =
            ScanReview.from(
                ScanResult.Full(
                    Facelets(oneOrangeAsYellow),
                    List(54) { StickerConfidence.CONFIDENT },
                    oneOrangeAsYellow.map(LABS::getValue),
                ),
                CalibrationCenters(LABS),
            )
        assertTrue(review.evidence != null)
        assertTrue(review.suspects.isEmpty())
        val problem = review.problems.single { it.reason.key == counts }
        assertTrue(problem.positions.isEmpty() && problem.piece.isEmpty())
        // #199's fallback: the first problem that names stickers (the white-yellow corner 6, 38, 18).
        assertEquals(6, review.selected)
    }

    @Test fun `at most three close stickers are listed, smallest margin first, equal margins by lower index`() {
        // L's six non-center stickers read yellow: 15 yellow, 3 orange. Each measures on the yellow-orange line at a
        // fraction t from yellow, so its margin is (1 - 2t) × ΔE(Y, O) ≈ (1 - 2t) × 55.9.
        fun between(t: Double): Lab = Lab(85.0 - 20.0 * t, -5.0 + 50.0 * t, 80.0 - 15.0 * t)
        val colors = solved.toMutableList().also { list -> listOf(36, 37, 38, 39, 41, 42).forEach { list[it] = Color.YELLOW } }
        val review =
            ScanReview(
                colors,
                null,
                evidence(
                    colors,
                    // margin ≈ 5.6
                    42 to between(0.45),
                    // margin ≈ 11.2
                    36 to between(0.40),
                    // margin ≈ 16.8
                    39 to between(0.35),
                    // margin ≈ 16.8, equal to 39: the lower index takes the last slot
                    37 to between(0.35),
                    // margin ≈ 19.0: qualifies, but beyond the cap
                    38 to between(0.33),
                    // margin ≈ 22.4: over 20
                    41 to between(0.30),
                ),
            )
        assertEquals(listOf(42, 36, 37).map { Suspect(it, Color.ORANGE) }, review.suspects)
        assertEquals(SuspectRanking.MAX_SUSPECTS, review.suspects.size)
        assertTrue(review.suspects.none { it.index == 41 })
        assertEquals(listOf(42, 36, 37), review.problems.single { it.reason.key == counts }.positions)
    }
}
