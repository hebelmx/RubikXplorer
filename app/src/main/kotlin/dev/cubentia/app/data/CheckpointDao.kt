package dev.cubentia.app.data

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query

@Dao
interface CheckpointDao {
    @Query("SELECT * FROM checkpoint WHERE kind = :kind")
    suspend fun find(kind: String): CheckpointEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun replace(entity: CheckpointEntity)
}
