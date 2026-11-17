package dev.cubentia.app.data

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query

@Dao
interface CalibrationDao {
    @Query("SELECT * FROM color_calibration WHERE id = 1")
    suspend fun load(): ColorCalibrationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(entity: ColorCalibrationEntity)

    @Query("DELETE FROM color_calibration")
    suspend fun reset()
}
