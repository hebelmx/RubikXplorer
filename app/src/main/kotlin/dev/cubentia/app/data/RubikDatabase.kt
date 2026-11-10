package dev.cubentia.app.data

import androidx.room3.AutoMigration
import androidx.room3.Database
import androidx.room3.RoomDatabase

@Database(
    entities = [
        AlgorithmStatusEventEntity::class,
        SessionSnapshotEntity::class,
        CheckpointEntity::class,
        AttemptEntity::class,
        PreferencesEntity::class,
        ProgressEventEntity::class,
    ],
    version = 6,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5),
        AutoMigration(from = 5, to = 6),
    ],
)
abstract class RubikDatabase : RoomDatabase() {
    abstract fun preferences(): PreferencesDao

    abstract fun attempts(): AttemptDao

    abstract fun checkpoints(): CheckpointDao

    abstract fun snapshots(): SessionSnapshotDao

    abstract fun algorithmStatuses(): AlgorithmStatusDao

    abstract fun progress(): ProgressDao
}
