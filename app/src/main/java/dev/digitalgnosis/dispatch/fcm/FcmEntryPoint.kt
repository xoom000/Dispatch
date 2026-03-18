package dev.digitalgnosis.dispatch.fcm

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.digitalgnosis.dispatch.config.TokenManager
import dev.digitalgnosis.dispatch.data.CmailRepository
import dev.digitalgnosis.dispatch.data.MessageRepository
import dev.digitalgnosis.dispatch.network.AudioStreamClient
import dev.digitalgnosis.dispatch.network.EventStreamClient
import dev.digitalgnosis.dispatch.network.FileTransferClient
import dev.digitalgnosis.dispatch.tts.ModelManager
import dev.digitalgnosis.dispatch.tts.TtsEngine

@EntryPoint
@InstallIn(SingletonComponent::class)
interface FcmEntryPoint {
    fun ttsEngine(): TtsEngine
    fun messageRepository(): MessageRepository
    fun cmailRepository(): CmailRepository
    fun tokenManager(): TokenManager
    fun modelManager(): ModelManager
    fun audioStreamClient(): AudioStreamClient
    fun eventStreamClient(): EventStreamClient
    fun fileTransferClient(): FileTransferClient
}
