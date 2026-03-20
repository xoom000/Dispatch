package dev.digitalgnosis.dispatch.di

import android.content.Context
import android.os.HandlerThread
import android.os.Process
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt DI module for the playback chain.
 *
 * Wires the core ExoPlayer dependency chain:
 *   AudioAttributes → ExoPlayer (with HandlerThread looper) → DispatchQueueManager
 *
 * Classes with @Inject constructors are auto-provided by Hilt and not listed here:
 *   - DispatchPlayerEventBus (@Singleton, @Inject)
 *   - DispatchSessionCallback (@Singleton, @Inject)
 *   - DispatchLastPlayedManager (@Singleton, @Inject)
 *   - DispatchAdaptiveTtsBuffer (@Singleton, @Inject)
 *
 * MediaSession is NOT in this module — it requires a Context from the service
 * and must be created/released within the service lifecycle (onCreate/onDestroy).
 * The service uses the injected ExoPlayer and DispatchSessionCallback to build it.
 *
 * Pattern: SimpleMediaPlayer SimpleMediaModule.kt
 */
@Module
@InstallIn(SingletonComponent::class)
object DispatchServiceModule {

    @Provides
    @Singleton
    fun providePlaybackAudioAttributes(): AudioAttributes =
        AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
            .setUsage(C.USAGE_MEDIA)
            .build()

    /**
     * Dedicated HandlerThread for ExoPlayer's playback looper.
     * Prevents audio glitches when the main thread is busy.
     * Pattern: Gramophone GramophonePlaybackService.internalPlaybackThread
     */
    @Provides
    @Singleton
    fun providePlaybackHandlerThread(): HandlerThread {
        val thread = HandlerThread("ExoPlayer:Playback", Process.THREAD_PRIORITY_AUDIO)
        thread.start()
        return thread
    }

    @OptIn(UnstableApi::class)
    @Provides
    @Singleton
    fun provideExoPlayer(
        @ApplicationContext context: Context,
        audioAttributes: AudioAttributes,
        playbackThread: HandlerThread,
    ): ExoPlayer =
        ExoPlayer.Builder(context)
            .setAudioAttributes(audioAttributes, /* handleAudioFocus= */ true)
            .setHandleAudioBecomingNoisy(true)
            .setPlaybackLooper(playbackThread.looper)
            .build()

}
