package dev.rubikxplorer.domain.recognition

import dev.rubikxplorer.domain.cube.Color
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class ScanFixtureEvaluationTest {
    @Test
    fun `labeled fixture has explicit truth and evaluates without guessing`() {
        val fixture = Json.decodeFromString<ScanFixtureSet>(File("../../Fixtures/labeled/face-samples.json").readText())
        assertTrue(fixture.samples.isNotEmpty())
        val scored =
            fixture.samples.map { labeled ->
                val result = NearestCenterClassifier.classify(labeled.sample, fixture.calibration, labeled.truth)
                assertTrue(result is ClassificationResult.Classified)
                (result as ClassificationResult.Classified).value
            }
        assertEquals(fixture.samples.sumOf { it.truth.size }, scored.sumOf { it.total })
        assertEquals(fixture.samples.sumOf { it.truth.size }, scored.sumOf { it.correct })
        assertEquals(Color.WHITE, scored.single().colors.first())
    }

    @Test
    fun `missing calibration and malformed truth are unavailable`() {
        val fixture = Json.decodeFromString<ScanFixtureSet>(File("../../Fixtures/labeled/face-samples.json").readText())
        val labeled = fixture.samples.single()
        assertTrue(
            NearestCenterClassifier.classify(
                labeled.sample,
                CalibrationCenters(emptyMap()),
                labeled.truth,
            ) is ClassificationResult.Unavailable,
        )
        assertTrue(
            NearestCenterClassifier.classify(
                labeled.sample,
                fixture.calibration,
                labeled.truth.dropLast(1),
            ) is ClassificationResult.Unavailable,
        )
        assertTrue(NearestCenterClassifier.classify(labeled.sample, fixture.calibration) is ClassificationResult.Unavailable)
        assertTrue(
            NearestCenterClassifier.classify(
                labeled.sample.copy(stickers = emptyList()),
                fixture.calibration,
                labeled.truth,
            ) is ClassificationResult.Unavailable,
        )
        CaptureQuality.entries.filterNot { it == CaptureQuality.ACCEPTABLE }.forEach { quality ->
            assertTrue(
                NearestCenterClassifier.predict(
                    labeled.sample.copy(quality = quality),
                    fixture.calibration,
                ) is ClassificationResult.Unavailable,
            )
        }
    }
}
