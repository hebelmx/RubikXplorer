package dev.cubentia.app.data

import androidx.room3.Entity
import androidx.room3.PrimaryKey
import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.recognition.CalibrationCenters
import dev.cubentia.domain.recognition.Lab

@Entity(tableName = "color_calibration")
data class ColorCalibrationEntity(
    @PrimaryKey val id: Int = 1,
    val white_l: Double,
    val white_a: Double,
    val white_b: Double,
    val red_l: Double,
    val red_a: Double,
    val red_b: Double,
    val green_l: Double,
    val green_a: Double,
    val green_b: Double,
    val yellow_l: Double,
    val yellow_a: Double,
    val yellow_b: Double,
    val orange_l: Double,
    val orange_a: Double,
    val orange_b: Double,
    val blue_l: Double,
    val blue_a: Double,
    val blue_b: Double,
) {
    fun calibration(): CalibrationCenters =
        CalibrationCenters(
            mapOf(
                Color.WHITE to Lab(white_l, white_a, white_b),
                Color.RED to Lab(red_l, red_a, red_b),
                Color.GREEN to Lab(green_l, green_a, green_b),
                Color.YELLOW to Lab(yellow_l, yellow_a, yellow_b),
                Color.ORANGE to Lab(orange_l, orange_a, orange_b),
                Color.BLUE to Lab(blue_l, blue_a, blue_b),
            ),
        )

    companion object {
        fun of(calibration: CalibrationCenters): ColorCalibrationEntity =
            ColorCalibrationEntity(
                id = 1,
                white_l = calibration.centers.getValue(Color.WHITE).l,
                white_a = calibration.centers.getValue(Color.WHITE).a,
                white_b = calibration.centers.getValue(Color.WHITE).b,
                red_l = calibration.centers.getValue(Color.RED).l,
                red_a = calibration.centers.getValue(Color.RED).a,
                red_b = calibration.centers.getValue(Color.RED).b,
                green_l = calibration.centers.getValue(Color.GREEN).l,
                green_a = calibration.centers.getValue(Color.GREEN).a,
                green_b = calibration.centers.getValue(Color.GREEN).b,
                yellow_l = calibration.centers.getValue(Color.YELLOW).l,
                yellow_a = calibration.centers.getValue(Color.YELLOW).a,
                yellow_b = calibration.centers.getValue(Color.YELLOW).b,
                orange_l = calibration.centers.getValue(Color.ORANGE).l,
                orange_a = calibration.centers.getValue(Color.ORANGE).a,
                orange_b = calibration.centers.getValue(Color.ORANGE).b,
                blue_l = calibration.centers.getValue(Color.BLUE).l,
                blue_a = calibration.centers.getValue(Color.BLUE).a,
                blue_b = calibration.centers.getValue(Color.BLUE).b,
            )
    }
}
