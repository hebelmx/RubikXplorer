package dev.rubikxplorer.app.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.rubikxplorer.domain.content.ContentSource

@Module
@InstallIn(SingletonComponent::class)
object ContentModule {
    /** The content bundle shipped in `:core:domain` resources. */
    @Provides
    fun provideContentSource(): ContentSource = ContentSource.SHIPPED
}
