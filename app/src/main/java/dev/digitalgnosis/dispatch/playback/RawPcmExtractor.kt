package dev.digitalgnosis.dispatch.playback

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput

/**
 * Custom extractor for raw PCM audio streams from Kokoro TTS.
 *
 * The TtsStreamDataSource strips the WAV header and feeds raw PCM bytes.
 * This extractor knows the format (24kHz, mono, 16-bit) and reads until
 * END_OF_INPUT — no container size field to confuse ExoPlayer.
 *
 * Modeled after WavExtractor's PassthroughOutputWriter pattern:
 * - Produces ~10 samples/second (100ms each) for smooth playback
 * - Handles END_OF_INPUT cleanly (flushes partial final sample)
 *
 * Reference: .project/modules/media3/samples/wav-extractor-source.java
 */
@UnstableApi
class RawPcmExtractor(
    private val sampleRate: Int = 24000,
    private val channelCount: Int = 1,
    private val pcmEncoding: Int = C.ENCODING_PCM_16BIT,
) : Extractor {

    companion object {
        /** ~10 samples per second, matching WavExtractor's TARGET_SAMPLES_PER_SECOND. */
        private const val TARGET_SAMPLES_PER_SECOND = 10

        fun factory(sampleRate: Int = 24000): ExtractorsFactory =
            ExtractorsFactory { arrayOf(RawPcmExtractor(sampleRate)) }
    }

    private var trackOutput: TrackOutput? = null
    private var outputFrameCount = 0L
    private var pendingOutputBytes = 0
    private var targetSampleSizeBytes = 0
    private val bytesPerFrame get() = channelCount * 2  // 16-bit = 2 bytes per sample per channel

    override fun sniff(input: ExtractorInput): Boolean {
        // Always accept — we're the sole extractor and the DataSource already stripped the header
        return true
    }

    override fun init(output: ExtractorOutput) {
        trackOutput = output.track(0, C.TRACK_TYPE_AUDIO)

        val constantBitrate = sampleRate * bytesPerFrame * 8
        targetSampleSizeBytes = maxOf(bytesPerFrame, sampleRate * bytesPerFrame / TARGET_SAMPLES_PER_SECOND)

        trackOutput!!.format(
            Format.Builder()
                .setSampleMimeType(MimeTypes.AUDIO_RAW)
                .setAverageBitrate(constantBitrate)
                .setPeakBitrate(constantBitrate)
                .setMaxInputSize(targetSampleSizeBytes)
                .setChannelCount(channelCount)
                .setSampleRate(sampleRate)
                .setPcmEncoding(pcmEncoding)
                .build()
        )

        output.endTracks()
        output.seekMap(SeekMap.Unseekable(C.TIME_UNSET))
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        val output = trackOutput ?: return Extractor.RESULT_END_OF_INPUT

        // Read PCM data up to target sample size, matching WavExtractor's PassthroughOutputWriter
        var bytesLeft = (targetSampleSizeBytes - pendingOutputBytes).toLong()
        while (bytesLeft > 0) {
            val bytesToRead = minOf(bytesLeft, (targetSampleSizeBytes - pendingOutputBytes).toLong()).toInt()
            val bytesAppended = output.sampleData(input, bytesToRead, /* allowEndOfInput= */ true)
            if (bytesAppended == C.RESULT_END_OF_INPUT) {
                // Stream ended — flush any partial sample
                val pendingFrames = pendingOutputBytes / bytesPerFrame
                if (pendingFrames > 0) {
                    val timeUs = Util.scaleLargeTimestamp(
                        outputFrameCount, C.MICROS_PER_SECOND, sampleRate.toLong()
                    )
                    val size = pendingFrames * bytesPerFrame
                    val offset = pendingOutputBytes - size
                    output.sampleMetadata(timeUs, C.BUFFER_FLAG_KEY_FRAME, size, offset, null)
                    outputFrameCount += pendingFrames
                    pendingOutputBytes = 0
                }
                return Extractor.RESULT_END_OF_INPUT
            }
            pendingOutputBytes += bytesAppended
            bytesLeft -= bytesAppended
        }

        // Output complete sample
        val pendingFrames = pendingOutputBytes / bytesPerFrame
        if (pendingFrames > 0) {
            val timeUs = Util.scaleLargeTimestamp(
                outputFrameCount, C.MICROS_PER_SECOND, sampleRate.toLong()
            )
            val size = pendingFrames * bytesPerFrame
            val offset = pendingOutputBytes - size
            output.sampleMetadata(timeUs, C.BUFFER_FLAG_KEY_FRAME, size, offset, null)
            outputFrameCount += pendingFrames
            pendingOutputBytes = offset
        }

        return Extractor.RESULT_CONTINUE
    }

    override fun seek(position: Long, timeUs: Long) {
        outputFrameCount = 0
        pendingOutputBytes = 0
    }

    override fun release() {
        // No resources to release
    }
}
