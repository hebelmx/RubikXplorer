package dev.rubikxplorer.app.data

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.PrimaryKey

@Entity(tableName = "session_snapshot")
data class SessionSnapshotEntity(
    @PrimaryKey val kind: String,
    @ColumnInfo(name = "session_id") val sessionId: String,
    val revision: Long,
    @ColumnInfo(name = "written_seq") val writtenSeq: Long,
    val payload: String,
)
