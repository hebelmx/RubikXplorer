package dev.cubentia.app.data

import androidx.room3.Entity
import androidx.room3.PrimaryKey

@Entity(tableName = "checkpoint")
data class CheckpointEntity(
    @PrimaryKey val kind: String,
    val payload: String,
)
