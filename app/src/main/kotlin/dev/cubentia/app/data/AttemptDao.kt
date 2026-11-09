package dev.cubentia.app.data

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query

@Dao
interface AttemptDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(rows: List<AttemptEntity>)

    @Query("SELECT * FROM attempt ORDER BY seq")
    suspend fun all(): List<AttemptEntity>
}
