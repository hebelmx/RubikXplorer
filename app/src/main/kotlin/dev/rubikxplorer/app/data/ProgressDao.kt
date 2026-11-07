package dev.rubikxplorer.app.data

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query

@Dao
interface ProgressDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(rows: List<ProgressEventEntity>)

    @Query("SELECT * FROM progress_event ORDER BY seq")
    suspend fun all(): List<ProgressEventEntity>
}
