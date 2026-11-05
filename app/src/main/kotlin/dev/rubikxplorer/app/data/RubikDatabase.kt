package dev.rubikxplorer.app.data

import androidx.room3.Database
import androidx.room3.RoomDatabase

@Database(entities = [SessionSnapshotEntity::class], version = 1, exportSchema = true)
abstract class RubikDatabase : RoomDatabase() {
    abstract fun snapshots(): SessionSnapshotDao
}
