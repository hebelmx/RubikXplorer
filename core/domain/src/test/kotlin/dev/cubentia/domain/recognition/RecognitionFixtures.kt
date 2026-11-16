package dev.cubentia.domain.recognition

import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.Face

/** Shared synthetic colors for the 10.1 recognition tests. */
internal object RecognitionFixtures {
    val LABS: Map<Color, Lab> =
        mapOf(
            Color.WHITE to Lab(90.0, 0.0, 0.0),
            Color.YELLOW to Lab(85.0, -5.0, 80.0),
            Color.GREEN to Lab(55.0, -60.0, 40.0),
            Color.BLUE to Lab(40.0, 10.0, -50.0),
            Color.RED to Lab(45.0, 65.0, 45.0),
            Color.ORANGE to Lab(65.0, 45.0, 65.0),
        )

    val CALIBRATION = CalibrationCenters(Color.entries.associateWith { LABS.getValue(it) })

    val LOGO = Lab(40.0, 0.0, -40.0)

    fun sample(
        center: Color?,
        stickers: List<Lab> = List(9) { LABS.getValue(center ?: Color.WHITE) },
        up: Color? = null,
        plain: Boolean? = null,
        orientation: Int = 0,
        quality: CaptureQuality = CaptureQuality.ACCEPTABLE,
        face: Face = Face.U,
    ) = FaceSample(
        face = face,
        orientationDegrees = orientation,
        stickers = stickers,
        quality = quality,
        centerColor = center,
        upNeighborColor = up,
        centerPlain = plain,
    )
}
