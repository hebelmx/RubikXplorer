package dev.cubentia.domain.recognition.evaluation.oq7

import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.recognition.FaceSample
import dev.cubentia.domain.recognition.Lab
import dev.cubentia.domain.recognition.LiveFixtureRecord
import dev.cubentia.domain.recognition.evaluation.FixtureGroup

/** Recording session of a fixture group: schema 1 records are session A (no frames), schema 2 records session A2. */
enum class Session(
    val label: String,
) {
    A("Session A"),
    A2("Session A2"),
    ;

    companion object {
        fun of(schemaVersion: Int): Session =
            when (schemaVersion) {
                1 -> A
                2 -> A2
                else -> throw IllegalArgumentException("Unsupported live fixture schema version: $schemaVersion")
            }
    }
}

/** The OQ-7 approaches and variants, by the ids every table uses. */
enum class Variant(
    val id: String,
) {
    K_CAL("K-cal"),
    K_SELF("K-self"),
    O_CAL("O-cal"),
    O_SELF("O-self"),
    M_LOCO("M-loco"),
}

/** What the OpenCV pipeline measures in one grid cell of a frame (central ROI, bright pixels masked). */
data class CellFeature(
    /** Mean of the unmasked ROI pixels. */
    val mean: Lab,
    /** Histogram mode: mean of the pixels in the peak bin and its 26 neighbors. */
    val mode: Lab,
    /** Pixels counted in [mode] / unmasked ROI pixels. */
    val modeShare: Double,
)

/** One recorded face with its optional frame features (nine cells, row-major) or null when it has no frame. */
data class FaceObservation(
    val record: LiveFixtureRecord,
    val cells: List<CellFeature>?,
) {
    val face: Face get() = record.labeledSample.sample.face
    val samples: List<Lab> get() = record.labeledSample.sample.stickers
    val truth: List<Color> get() = record.labeledSample.truth
    val centerColor: Color get() = truth[FaceSample.CENTER_INDEX]
}

data class GroupObservation(
    val name: String,
    val session: Session,
    val device: String,
    val cube: String,
    val faces: List<FaceObservation>,
) {
    val hasFrames: Boolean get() = faces.isNotEmpty() && faces.all { it.cells != null }
}

object Observations {
    /** Maps loaded fixture groups to observations; [frames] measures a record's frame, or returns null when it has none. */
    fun from(
        groups: List<FixtureGroup>,
        frames: (FixtureGroup, LiveFixtureRecord) -> List<CellFeature>?,
    ): List<GroupObservation> =
        groups.filter { it.records.isNotEmpty() }.map { group ->
            val first = group.records.first()
            val sessions = group.records.map { Session.of(it.schemaVersion) }.toSet()
            require(sessions.size == 1) { "Group ${group.name} mixes schema versions" }
            GroupObservation(
                name = group.name,
                session = sessions.single(),
                device = "${first.device.manufacturer} ${first.device.model}",
                cube = first.cube.lowercase(java.util.Locale.ROOT),
                faces =
                    group.records.sortedBy { it.labeledSample.sample.face }.map { record ->
                        val cells = frames(group, record)
                        require(cells == null || cells.size == CELLS) { "Frame of ${group.name} must give $CELLS cells" }
                        FaceObservation(record, cells)
                    },
            )
        }

    const val CELLS = 9
}
