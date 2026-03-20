package dev.digitalgnosis.dispatch.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.digitalgnosis.dispatch.data.CmailRepository
import dev.digitalgnosis.dispatch.data.CmailRepositoryImpl
import dev.digitalgnosis.dispatch.data.ConfigRepository
import dev.digitalgnosis.dispatch.data.ConfigRepositoryImpl
import dev.digitalgnosis.dispatch.data.DebugRepository
import dev.digitalgnosis.dispatch.data.DebugRepositoryImpl
import dev.digitalgnosis.dispatch.data.EventRepository
import dev.digitalgnosis.dispatch.data.EventRepositoryImpl
import dev.digitalgnosis.dispatch.data.GeminiRepository
import dev.digitalgnosis.dispatch.data.GeminiRepositoryImpl
import dev.digitalgnosis.dispatch.data.HistoryRepository
import dev.digitalgnosis.dispatch.data.HistoryRepositoryImpl
import dev.digitalgnosis.dispatch.data.PulseRepository
import dev.digitalgnosis.dispatch.data.PulseRepositoryImpl
import dev.digitalgnosis.dispatch.data.SessionRepository
import dev.digitalgnosis.dispatch.data.SessionRepositoryImpl
import dev.digitalgnosis.dispatch.data.WhiteboardRepository
import dev.digitalgnosis.dispatch.data.WhiteboardRepositoryImpl
import javax.inject.Singleton

/**
 * Hilt DI module that binds repository interfaces to their production implementations.
 *
 * All repositories are application-scoped singletons. Consumers inject the interface
 * (e.g., [SessionRepository]), never the Impl class directly. This allows full mock
 * substitution in unit tests without a network stack.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindSessionRepository(impl: SessionRepositoryImpl): SessionRepository

    @Binds
    @Singleton
    abstract fun bindCmailRepository(impl: CmailRepositoryImpl): CmailRepository

    @Binds
    @Singleton
    abstract fun bindPulseRepository(impl: PulseRepositoryImpl): PulseRepository

    @Binds
    @Singleton
    abstract fun bindConfigRepository(impl: ConfigRepositoryImpl): ConfigRepository

    @Binds
    @Singleton
    abstract fun bindEventRepository(impl: EventRepositoryImpl): EventRepository

    @Binds
    @Singleton
    abstract fun bindHistoryRepository(impl: HistoryRepositoryImpl): HistoryRepository

    @Binds
    @Singleton
    abstract fun bindWhiteboardRepository(impl: WhiteboardRepositoryImpl): WhiteboardRepository

    @Binds
    @Singleton
    abstract fun bindGeminiRepository(impl: GeminiRepositoryImpl): GeminiRepository

    @Binds
    @Singleton
    abstract fun bindDebugRepository(impl: DebugRepositoryImpl): DebugRepository
}
