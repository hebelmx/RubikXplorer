package dev.cubentia.app.data

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query

@Dao
interface SessionSnapshotDao {
    @Query("SELECT * FROM session_snapshot WHERE kind = :kind")
    suspend fun find(kind: String): SessionSnapshotEntity?

    @Query("SELECT * FROM session_snapshot ORDER BY written_seq DESC LIMIT 1")
    suspend fun latest(): SessionSnapshotEntity?

    @Query("SELECT COALESCE(MAX(written_seq), 0) FROM session_snapshot")
    suspend fun sequence(): Long

    @Query("DELETE FROM session_snapshot")
    suspend fun clear()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun replace(entity: SessionSnapshotEntity)
}
