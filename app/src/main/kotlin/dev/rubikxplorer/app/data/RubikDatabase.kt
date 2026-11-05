package dev.rubikxplorer.app.data

import androidx.room3.AutoMigration
import androidx.room3.Database
import androidx.room3.RoomDatabase

@Database(
    entities = [SessionSnapshotEntity::class, CheckpointEntity::class, AttemptEntity::class],
    version = 3,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3)],
)
abstract class RubikDatabase : RoomDatabase() {
    abstract fun attempts(): AttemptDao

    abstract fun checkpoints(): CheckpointDao

    abstract fun snapshots(): SessionSnapshotDao
}
