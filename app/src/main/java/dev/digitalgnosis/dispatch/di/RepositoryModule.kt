package dev.digitalgnosis.dispatch.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.digitalgnosis.dispatch.data.CmailRepository
import dev.digitalgnosis.dispatch.data.ConfigRepository
import dev.digitalgnosis.dispatch.data.EventRepository
import dev.digitalgnosis.dispatch.data.HistoryRepository
import dev.digitalgnosis.dispatch.data.PulseRepository
import dev.digitalgnosis.dispatch.data.SessionRepository
import dev.digitalgnosis.dispatch.data.WhiteboardRepository
import dev.digitalgnosis.dispatch.network.BaseFileBridgeClient
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object RepositoryModule {

    @Provides
    @Singleton
    fun provideCmailRepository(client: BaseFileBridgeClient): CmailRepository =
        CmailRepository(client)

    @Provides
    @Singleton
    fun provideSessionRepository(client: BaseFileBridgeClient): SessionRepository =
        SessionRepository(client)

    @Provides
    @Singleton
    fun providePulseRepository(client: BaseFileBridgeClient): PulseRepository =
        PulseRepository(client)

    @Provides
    @Singleton
    fun provideConfigRepository(client: BaseFileBridgeClient): ConfigRepository =
        ConfigRepository(client)

    @Provides
    @Singleton
    fun provideEventRepository(client: BaseFileBridgeClient): EventRepository =
        EventRepository(client)

    @Provides
    @Singleton
    fun provideHistoryRepository(client: BaseFileBridgeClient): HistoryRepository =
        HistoryRepository(client)

    @Provides
    @Singleton
    fun provideWhiteboardRepository(client: BaseFileBridgeClient): WhiteboardRepository =
        WhiteboardRepository(client)
}
