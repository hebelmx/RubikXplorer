package dev.cubentia.app.di

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.cubentia.app.data.RoomCheckpointRepository
import dev.cubentia.app.data.RoomPreferencesRepository
import dev.cubentia.app.data.RoomProgressRepository
import dev.cubentia.app.data.RoomSessionSnapshotRepository
import dev.cubentia.app.data.RubikDatabase
import dev.cubentia.app.data.SerialWriter
import dev.cubentia.app.data.SessionSnapshotDao
import dev.cubentia.application.port.AlgorithmStatusRepository
import dev.cubentia.application.port.CheckpointRepository
import dev.cubentia.application.port.PreferencesRepository
import dev.cubentia.application.port.ProgressRepository
import dev.cubentia.application.port.SessionSnapshotRepository
import dev.cubentia.application.preference.UpdatePreference
import dev.cubentia.application.usecase.CompleteLesson
import dev.cubentia.application.usecase.PassSkipCheck
import dev.cubentia.application.usecase.PlaceFromScan
import dev.cubentia.application.usecase.ResetProgress
import dev.cubentia.application.usecase.SetAlgorithmStatus
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object PersistenceModule {
    @Provides @Singleton
    fun database(
        @ApplicationContext context: Context,
    ): RubikDatabase = Room.databaseBuilder<RubikDatabase>(context, "cubentia.db").setDriver(AndroidSQLiteDriver()).build()

    @Provides @Singleton
    fun checkpoints(database: RubikDatabase): CheckpointRepository = RoomCheckpointRepository(database)

    @Provides @Singleton
    fun attempts(database: RubikDatabase): dev.cubentia.application.port.AttemptRepository =
        dev.cubentia.app.data
            .RoomAttemptRepository(database)

    @Provides @Singleton
    fun preferences(database: RubikDatabase): PreferencesRepository = RoomPreferencesRepository(database)

    @Provides @Singleton
    fun updatePreference(repository: PreferencesRepository): UpdatePreference = UpdatePreference(repository)

    @Provides @Singleton
    fun progress(database: RubikDatabase): ProgressRepository = RoomProgressRepository(database)

    @Provides @Singleton
    fun completeLesson(repository: ProgressRepository): CompleteLesson = CompleteLesson(repository)

    @Provides @Singleton
    fun passSkipCheck(repository: ProgressRepository): PassSkipCheck = PassSkipCheck(repository)

    @Provides @Singleton
    fun placeFromScan(repository: ProgressRepository): PlaceFromScan = PlaceFromScan(repository)

    @Provides @Singleton
    fun resetProgress(repository: ProgressRepository): ResetProgress = ResetProgress(repository)

    @Provides @Singleton
    fun algorithmStatuses(database: RubikDatabase): AlgorithmStatusRepository =
        dev.cubentia.app.data
            .RoomAlgorithmStatusRepository(database)

    @Provides @Singleton
    fun setAlgorithmStatus(repository: AlgorithmStatusRepository): SetAlgorithmStatus = SetAlgorithmStatus(repository)

    @Provides fun dao(database: RubikDatabase): SessionSnapshotDao = database.snapshots()

    @Provides @Singleton
    fun writer(): SerialWriter = SerialWriter()

    @Provides @Singleton
    fun repository(database: RubikDatabase): SessionSnapshotRepository = RoomSessionSnapshotRepository(database)
}
