package dev.digitalgnosis.dispatch.fcm

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.digitalgnosis.dispatch.config.TokenManager
import dev.digitalgnosis.dispatch.data.CmailOutboxRepository
import dev.digitalgnosis.dispatch.data.CmailRepository
import dev.digitalgnosis.dispatch.data.MessageRepository
import dev.digitalgnosis.dispatch.data.VoiceNotificationRepository
import dev.digitalgnosis.dispatch.audio.PlaybackStateHolder
import dev.digitalgnosis.dispatch.playback.VoiceReplyCoordinator
import dev.digitalgnosis.dispatch.network.AudioStreamClient
import dev.digitalgnosis.dispatch.network.FileTransferClient
import dev.digitalgnosis.dispatch.tts.ModelManager
import dev.digitalgnosis.dispatch.tts.TtsEngine

@EntryPoint
@InstallIn(SingletonComponent::class)
interface FcmEntryPoint {
    fun ttsEngine(): TtsEngine
    fun messageRepository(): MessageRepository
    fun voiceNotificationRepository(): VoiceNotificationRepository
    fun cmailOutboxRepository(): CmailOutboxRepository
    fun cmailRepository(): CmailRepository
    fun voiceReplyCoordinator(): VoiceReplyCoordinator
    fun tokenManager(): TokenManager
    fun modelManager(): ModelManager
    fun audioStreamClient(): AudioStreamClient
    fun fileTransferClient(): FileTransferClient
    fun playbackStateHolder(): PlaybackStateHolder
}
