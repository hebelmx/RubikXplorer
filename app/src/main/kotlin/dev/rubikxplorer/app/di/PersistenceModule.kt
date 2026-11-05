package dev.rubikxplorer.app.di

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.rubikxplorer.app.data.RoomCheckpointRepository
import dev.rubikxplorer.app.data.RoomSessionSnapshotRepository
import dev.rubikxplorer.app.data.RubikDatabase
import dev.rubikxplorer.app.data.SerialWriter
import dev.rubikxplorer.app.data.SessionSnapshotDao
import dev.rubikxplorer.application.port.CheckpointRepository
import dev.rubikxplorer.application.port.SessionSnapshotRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object PersistenceModule {
    @Provides @Singleton
    fun database(
        @ApplicationContext context: Context,
    ): RubikDatabase = Room.databaseBuilder<RubikDatabase>(context, "rubikxplorer.db").setDriver(AndroidSQLiteDriver()).build()

    @Provides @Singleton
    fun checkpoints(database: RubikDatabase): CheckpointRepository = RoomCheckpointRepository(database)

    @Provides @Singleton
    fun attempts(database: RubikDatabase): dev.rubikxplorer.application.port.AttemptRepository =
        dev.rubikxplorer.app.data
            .RoomAttemptRepository(database)

    @Provides fun dao(database: RubikDatabase): SessionSnapshotDao = database.snapshots()

    @Provides @Singleton
    fun writer(): SerialWriter = SerialWriter()

    @Provides @Singleton
    fun repository(database: RubikDatabase): SessionSnapshotRepository = RoomSessionSnapshotRepository(database)
}
