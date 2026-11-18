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
        ColorCalibrationEntity::class,
    ],
    version = 9,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5),
        AutoMigration(from = 5, to = 6),
        AutoMigration(from = 6, to = 7),
        AutoMigration(from = 7, to = 8),
        AutoMigration(from = 8, to = 9),
    ],
)
abstract class RubikDatabase : RoomDatabase() {
    abstract fun calibration(): CalibrationDao

    abstract fun preferences(): PreferencesDao

    abstract fun attempts(): AttemptDao

    abstract fun checkpoints(): CheckpointDao

    abstract fun snapshots(): SessionSnapshotDao

    abstract fun algorithmStatuses(): AlgorithmStatusDao

    abstract fun progress(): ProgressDao
}
