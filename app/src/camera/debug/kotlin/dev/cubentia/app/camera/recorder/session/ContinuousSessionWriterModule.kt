package dev.cubentia.app.camera.recorder.session

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.cubentia.app.camera.ContinuousSessionWriterFactory

/** Story 10.34 (AD-9 Frames): binds the continuous session writer in debug only (this source set exists only in debug). */
@Module
@InstallIn(SingletonComponent::class)
abstract class ContinuousSessionWriterModule {
    @Binds
    internal abstract fun writerFactory(impl: DebugContinuousSessionWriterFactory): ContinuousSessionWriterFactory
}
