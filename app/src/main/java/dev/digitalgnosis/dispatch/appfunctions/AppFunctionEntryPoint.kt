package dev.digitalgnosis.dispatch.appfunctions

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.digitalgnosis.dispatch.data.CmailRepository
import dev.digitalgnosis.dispatch.data.SessionRepository
import dev.digitalgnosis.dispatch.data.VoiceNotificationRepository
import dev.digitalgnosis.dispatch.network.BaseFileBridgeClient

/**
 * Hilt EntryPoint for AppFunctions wiring.
 *
 * AppFunctionConfiguration.Provider is called on the Application class before Hilt's
 * member injection runs, so we cannot use @Inject fields. Instead, we pull the
 * dependencies through EntryPointAccessors in DispatchApplication.appFunctionConfiguration.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface AppFunctionEntryPoint {
    fun cmailRepository(): CmailRepository
    fun sessionRepository(): SessionRepository
    fun voiceNotificationRepository(): VoiceNotificationRepository
    fun fileBridgeClient(): BaseFileBridgeClient
}
