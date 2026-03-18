// SOURCE: https://github.com/google/ExoPlayer/issues/7566
// SOURCE: OkHttpDataSource.Factory pattern from androidx/media release branch
// Wires a custom DataSource.Factory into ExoPlayer via ProgressiveMediaSource

package com.example.dispatch.media

import android.content.Context
import android.net.Uri
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.datasource.DataSource
import androidx.media3.common.MediaItem
import java.io.InputStream

/**
 * Wire a custom DataSource.Factory into ExoPlayer for streaming audio.
 *
 * Key pattern from ExoPlayer #7566:
 *   1. Implement DataSource (or extend BaseDataSource)
 *   2. Wrap it in DataSource.Factory { return MyDataSource() }
 *   3. Pass factory to ProgressiveMediaSource.Factory(factory)
 *   4. createMediaSource(MediaItem) -- use addMediaSource() on player
 */
object ProgressiveStreamingExample {

    fun buildPlayerWithCustomSource(
        context: Context,
        streamUri: Uri,
        streamProvider: () -> InputStream
    ): ExoPlayer {
        // Step 1: DataSource.Factory backed by your InputStream provider
        val dataSourceFactory = DataSource.Factory {
            InputStreamDataSource(streamProvider)
        }

        // Step 2: ProgressiveMediaSource.Factory -- pass your factory here
        val mediaSourceFactory = ProgressiveMediaSource.Factory(dataSourceFactory)
            // Optionally supply custom extractors:
            // .setExtractorsFactory { arrayOf(RawPcmExtractor()) }

        // Step 3: Build player
        val player = ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()

        // Step 4: Set media item and prepare
        // The URI here is passed to your DataSource.open(dataSpec) as dataSpec.uri
        val mediaItem = MediaItem.fromUri(streamUri)
        player.setMediaItem(mediaItem)
        player.prepare()

        return player
    }

    /**
     * Streaming TTS variant: write audio bytes to PipedOutputStream,
     * ExoPlayer reads from connected PipedInputStream via custom DataSource.
     *
     * Pattern derived from ExoPlayer #7566 discussion.
     * PipedOutputStream blocks when buffer full (~1KB default -- increase it).
     */
    fun buildStreamingTtsPlayer(context: Context): Pair<ExoPlayer, java.io.PipedOutputStream> {
        val pipedInput = java.io.PipedInputStream(65536)  // 64KB buffer, not default 1KB
        val pipedOutput = java.io.PipedOutputStream(pipedInput)

        // URI scheme is arbitrary for custom DataSource -- it never makes a network call
        val fakeUri = Uri.parse("tts://stream")

        val player = buildPlayerWithCustomSource(context, fakeUri) { pipedInput }

        // Caller writes TTS audio chunks to pipedOutput on a background thread.
        // DataSource.read() blocks waiting for data -- this is the correct behavior.
        return Pair(player, pipedOutput)
    }
}
