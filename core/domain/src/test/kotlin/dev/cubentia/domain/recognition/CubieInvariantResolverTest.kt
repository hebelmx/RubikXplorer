package dev.cubentia.domain.recognition

import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.CubeValidator
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.cube.FaceletTables
import dev.cubentia.domain.cube.Facelets
import dev.cubentia.domain.cube.Notation
import dev.cubentia.domain.cube.NotationResult
import dev.cubentia.domain.cube.ValidationResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CubieInvariantResolverTest {
    private val centers = List(6) { RecognitionFixtures.LABS }

    private fun scrambled(moves: String): Facelets {
        val parsed = (Notation.parse(moves) as NotationResult.Parsed).moves
        return parsed.fold(CubeState.SOLVED) { state, move -> state.apply(move) }.toFacelets()
    }

    private fun resolveMasked(
        truth: Facelets,
        uncertainIndices: Set<Int>,
        colorOverrides: Map<Int, Color> = emptyMap(),
        labOverrides: Map<Int, Lab> = emptyMap(),
        centerPlain: BooleanArray = BooleanArray(6) { true },
        confidenceOverrides: Map<Int, StickerConfidence> = emptyMap(),
    ): ResolutionOutcome {
        val colors = truth.colors.mapIndexed { i, c -> colorOverrides[i] ?: c }
        val conf =
            List(Facelets.SIZE) { i ->
                confidenceOverrides[i] ?: if (i in uncertainIndices) StickerConfidence.UNCERTAIN else StickerConfidence.CONFIDENT
            }
        val labs = truth.colors.mapIndexed { i, c -> labOverrides[i] ?: RecognitionFixtures.LABS.getValue(c) }
        return CubieInvariantResolver.resolveNet(
            netColors = colors,
            netConfidence = conf,
            netLabs = labs,
            plausibilityLabs = labs,
            plausibilityCenters = centers,
            centerPlain = centerPlain,
        )
    }

    @Test
    fun `corner chirality deterministically recovers a single masked close-call corner sticker`() {
        val truth = scrambled("R U R' F' D2 L B")
        // Pick a corner sticker whose true color is RED or ORANGE, and feed a Lab midway between RED and ORANGE.
        val target =
            FaceletTables.CORNER_FACELETS
                .flatten()
                .first { truth.colors[it] == Color.RED || truth.colors[it] == Color.ORANGE }
        val closeCallLab = Lab(55.0, 55.0, 55.0) // midway between RED and ORANGE
        val wrongGuess = if (truth.colors[target] == Color.RED) Color.ORANGE else Color.RED

        val outcome =
            resolveMasked(
                truth = truth,
                uncertainIndices = setOf(target),
                colorOverrides = mapOf(target to wrongGuess),
                labOverrides = mapOf(target to closeCallLab),
            )

        assertTrue(outcome is ResolutionOutcome.Resolved, "Expected Resolved, got $outcome")
        val resolved = outcome as ResolutionOutcome.Resolved
        assertEquals(truth, resolved.facelets)
        assertEquals(setOf(target), resolved.resolvedIndices)
        assertTrue(CubeValidator.validate(resolved.facelets) is ValidationResult.Valid)
    }

    @Test
    fun `multi-sticker propagation and piece-typed budgets rescue 9 uncertain stickers uniquely`() {
        val truth = scrambled("U R2 F' L D B2 R' U'")
        // 8 corners (one per corner slot, all deterministically deduced by chirality) + 1 edge sticker = 9 uncertain stickers.
        val masked =
            setOf(
                FaceletTables.CORNER_FACELETS[0][0],
                FaceletTables.CORNER_FACELETS[1][1],
                FaceletTables.CORNER_FACELETS[2][2],
                FaceletTables.CORNER_FACELETS[3][0],
                FaceletTables.CORNER_FACELETS[4][1],
                FaceletTables.CORNER_FACELETS[5][2],
                FaceletTables.CORNER_FACELETS[6][0],
                FaceletTables.CORNER_FACELETS[7][1],
                FaceletTables.EDGE_FACELETS[0][0],
            )
        val outcome = resolveMasked(truth, masked)

        assertTrue(outcome is ResolutionOutcome.Resolved, "Expected Resolved, got $outcome")
        assertEquals(truth, (outcome as ResolutionOutcome.Resolved).facelets)
        assertEquals(masked, outcome.resolvedIndices)
    }

    @Test
    fun `backtracking with edge flip parity resolves an edge whose colors are ambiguous locally`() {
        val truth = scrambled("R U R' U'")
        // Mask both facets of a single edge slot: only one orientation satisfies edge-flip parity (sum == 0 mod 2).
        val edgeSlot = FaceletTables.EDGE_FACELETS[0]
        val masked = edgeSlot.toSet()

        val outcome = resolveMasked(truth, masked)

        assertTrue(outcome is ResolutionOutcome.Resolved, "Expected Resolved, got $outcome")
        assertEquals(truth, (outcome as ResolutionOutcome.Resolved).facelets)
    }

    @Test
    fun `plain center disagreement aborts while logo center is exempt`() {
        val truth = scrambled("R U F'")
        val target = FaceletTables.CORNER_FACELETS[0][0]
        val uCenter = FaceSample.CENTER_INDEX

        val plainUncertain =
            resolveMasked(
                truth = truth,
                uncertainIndices = setOf(target),
                confidenceOverrides = mapOf(uCenter to StickerConfidence.UNCERTAIN),
                centerPlain = BooleanArray(6) { true },
            )
        assertEquals(ResolutionOutcome.Unchanged(ResolutionOutcome.Reason.CONFIDENT_INVARIANT_VIOLATION), plainUncertain)

        val plainWrong =
            resolveMasked(
                truth = truth,
                uncertainIndices = setOf(target),
                colorOverrides = mapOf(uCenter to Color.YELLOW),
                centerPlain = BooleanArray(6) { true },
            )
        assertEquals(ResolutionOutcome.Unchanged(ResolutionOutcome.Reason.CONFIDENT_INVARIANT_VIOLATION), plainWrong)

        val logoCenter =
            resolveMasked(
                truth = truth,
                uncertainIndices = setOf(target),
                confidenceOverrides = mapOf(uCenter to StickerConfidence.UNCERTAIN),
                colorOverrides = mapOf(uCenter to Color.BLUE),
                centerPlain = BooleanArray(6) { it != Face.U.ordinal },
            )
        assertTrue(logoCenter is ResolutionOutcome.Resolved, "Logo center should be exempt, got $logoCenter")
        assertEquals(truth, (logoCenter as ResolutionOutcome.Resolved).facelets)
    }

    @Test
    fun `piece-typed budget catches 5 confident corner stickers of one color even when total count is at most 9`() {
        val truth = CubeState.SOLVED.toFacelets()
        // Solved cube has 4 WHITE corners (U0, U2, U6, U8) and 4 WHITE edges (U1, U3, U5, U7).
        // Make 2 WHITE edges uncertain (so total confident WHITE = 7 <= 9), and corrupt a D-face corner to WHITE (5 confident WHITE corners!).
        val corruptCorner = FaceletTables.CORNER_FACELETS[4][0] // D2 (normally YELLOW)
        val outcome =
            resolveMasked(
                truth = truth,
                uncertainIndices = setOf(1, 3), // U1, U3 edges
                colorOverrides = mapOf(corruptCorner to Color.WHITE),
            )
        assertEquals(ResolutionOutcome.Unchanged(ResolutionOutcome.Reason.CONFIDENT_INVARIANT_VIOLATION), outcome)
    }

    @Test
    fun `confident misread never produces a wrong resolved state`() {
        val truth = scrambled("R U R' U R U2 R'")
        val masked = setOf(FaceletTables.CORNER_FACELETS[0][0], FaceletTables.EDGE_FACELETS[0][0])
        val wrongConfidentIdx = FaceletTables.EDGE_FACELETS[5][0]
        val wrongColor = Color.entries.first { it != truth.colors[wrongConfidentIdx] }

        val outcome =
            resolveMasked(
                truth = truth,
                uncertainIndices = masked,
                colorOverrides = mapOf(wrongConfidentIdx to wrongColor),
            )

        assertTrue(outcome is ResolutionOutcome.Unchanged, "Expected Unchanged on confident misread, got $outcome")
    }

    @Test
    fun `ambiguity safety refuses to guess even when the second valid state is far in Lab distance`() {
        val truth = CubeState.SOLVED.toFacelets()
        // Masking both facets of two solved edges allows both the identity and the 2-edge flip (both valid!).
        // Even though the true labs sit at exact 0 ΔE (and the flipped state is > 60 ΔE away!), algebraic uniqueness
        // must detect AMBIGUOUS and never use Lab distance to manufacture uniqueness.
        val e0 = FaceletTables.EDGE_FACELETS[0]
        val e1 = FaceletTables.EDGE_FACELETS[1]
        val masked = (e0 + e1).toSet()

        val outcome = resolveMasked(truth, masked)
        assertEquals(ResolutionOutcome.Unchanged(ResolutionOutcome.Reason.AMBIGUOUS), outcome)
    }

    @Test
    fun `plausibility guard rejects a uniquely deduced color whose measured Lab is wildly incompatible`() {
        val truth = scrambled("R U F' L D")
        val target = FaceletTables.CORNER_FACELETS[0][0]
        val deducedColor = truth.colors[target]
        val farColor = Color.entries.first { it != deducedColor }
        // Feed a measured Lab sitting right on a completely different color center (>> MAX_PLAUSIBILITY_DELTA_E away).
        val outcome =
            resolveMasked(
                truth = truth,
                uncertainIndices = setOf(target),
                labOverrides = mapOf(target to RecognitionFixtures.LABS.getValue(farColor)),
            )

        assertEquals(ResolutionOutcome.Unchanged(ResolutionOutcome.Reason.PLAUSIBILITY_REJECTED), outcome)
    }

    @Test
    fun `returns NO_UNCERTAIN when all non-center stickers are confident and TOO_MANY_UNCERTAIN above cap`() {
        val truth = CubeState.SOLVED.toFacelets()
        assertEquals(ResolutionOutcome.Unchanged(ResolutionOutcome.Reason.NO_UNCERTAIN), resolveMasked(truth, emptySet()))

        val thirteen = (0 until Facelets.SIZE).filter { it % 9 != 4 }.take(CubieInvariantResolver.MAX_UNCERTAIN_STICKERS + 1).toSet()
        assertEquals(ResolutionOutcome.Unchanged(ResolutionOutcome.Reason.TOO_MANY_UNCERTAIN), resolveMasked(truth, thirteen))
    }
}
