package dev.digitalgnosis.dispatch.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.speech.tts.TextToSpeech
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TtsEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val modelManager: ModelManager
) : TextToSpeech.OnInitListener {

    // Sherpa-ONNX types loaded lazily to avoid early native lib loading
    private var offlineTts: Any? = null
    private var audioTrack: AudioTrack? = null
    private var fallbackTts: TextToSpeech? = null
    private var fallbackReady = false
    private var piperReady = false

    private val pendingUtterances = mutableListOf<String>()
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val prefs = context.getSharedPreferences("tts_settings", Context.MODE_PRIVATE)

    // Piper libritts_r-medium has 904 speakers
    var speakerId: Int = prefs.getInt("speaker_id", 0)
        set(value) {
            field = value
            prefs.edit().putInt("speaker_id", value).apply()
        }
    var speed: Float = prefs.getFloat("speed", 1.0f)
        set(value) {
            field = value
            prefs.edit().putFloat("speed", value).apply()
        }

    init {
        Timber.i("TtsEngine init: starting")
        try {
            // Initialize system TTS as fallback
            fallbackTts = TextToSpeech(context, this)
            Timber.i("TtsEngine init: system TTS created")
        } catch (e: Throwable) {
            Timber.e(e, "TtsEngine init: system TTS creation failed")
        }

        // Watch model state and init Piper when ready
        initPiperWhenReady()
    }

    private fun initPiperWhenReady() {
        executor.execute {
            try {
                Timber.d("Piper watcher: polling model state")
                while (!Thread.currentThread().isInterrupted) {
                    val state = modelManager.state.value
                    if (state is ModelState.Ready) {
                        Timber.i("Piper watcher: model ready, initializing engine")
                        initPiper()
                        break
                    }
                    if (state is ModelState.Error) {
                        Timber.w("Piper watcher: model extraction failed, sticking with system TTS")
                        break
                    }
                    try {
                        Thread.sleep(500)
                    } catch (_: InterruptedException) {
                        Timber.d("Piper watcher: interrupted, stopping")
                        break
                    }
                }
            } catch (e: Throwable) {
                Timber.e(e, "Piper watcher: unexpected error")
            }
        }
    }

    private fun initPiper() {
        try {
            val initStart = System.currentTimeMillis()
            Timber.i("initPiper: loading native library and VITS model")
            Timber.i("initPiper: provider=cpu, numThreads=4, debug=true")

            val config = com.k2fsa.sherpa.onnx.OfflineTtsConfig(
                model = com.k2fsa.sherpa.onnx.OfflineTtsModelConfig(
                    vits = com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig(
                        model = modelManager.modelPath,
                        tokens = modelManager.tokensPath,
                        dataDir = modelManager.dataDir,
                    ),
                    numThreads = 4,
                    debug = true,
                    provider = "cpu",
                ),
                maxNumSentences = 2,
            )

            val configElapsed = System.currentTimeMillis() - initStart
            Timber.i("initPiper: config created in %dms, creating OfflineTts instance", configElapsed)

            val ttsCreateStart = System.currentTimeMillis()
            val tts = com.k2fsa.sherpa.onnx.OfflineTts(config = config)
            offlineTts = tts
            val ttsCreateElapsed = System.currentTimeMillis() - ttsCreateStart

            val sampleRate = tts.sampleRate()
            val totalElapsed = System.currentTimeMillis() - initStart
            Timber.i("initPiper: success! sampleRate=%d, speakers=%d", sampleRate, tts.numSpeakers())
            Timber.i("initPiper: OfflineTts created in %dms, total init: %dms", ttsCreateElapsed, totalElapsed)

            initAudioTrack(sampleRate)
            piperReady = true

            // Flush any queued utterances
            synchronized(pendingUtterances) {
                if (pendingUtterances.isNotEmpty()) {
                    Timber.d("initPiper: flushing %d pending utterances", pendingUtterances.size)
                    pendingUtterances.forEach { speakPiper(it) }
                    pendingUtterances.clear()
                }
            }

        } catch (e: Throwable) {
            Timber.e(e, "initPiper: FAILED — %s: %s", e.javaClass.simpleName, e.message)
            offlineTts = null
            piperReady = false
        }
    }

    private fun initAudioTrack(sampleRate: Int) {
        val bufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT
        )

        val attrs = AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .build()

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setSampleRate(sampleRate)
            .build()

        audioTrack = AudioTrack(
            attrs,
            format,
            bufferSize,
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE
        )

        Timber.d("AudioTrack initialized: sampleRate=%d, bufferSize=%d", sampleRate, bufferSize)
    }

    // System TTS fallback initialization
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            fallbackTts?.language = Locale.US
            fallbackReady = true
            Timber.d("System TTS fallback ready")
        } else {
            Timber.e("System TTS fallback FAILED: status=%d", status)
        }
    }

    fun speak(sender: String, message: String) {
        val text = "$sender says: $message"
        Timber.i("speak() called: piperReady=%b, fallbackReady=%b, speaker=#%d, speed=%.1f",
            piperReady, fallbackReady, speakerId, speed)

        if (piperReady) {
            speakPiper(text)
        } else if (fallbackReady) {
            speakFallback(text)
        } else {
            Timber.d("No TTS ready, queueing: %s", text)
            synchronized(pendingUtterances) {
                pendingUtterances.add(text)
            }
        }
    }

    /**
     * Blocking version of speak — runs on the CALLING thread.
     * Use this when the caller needs to serialize Piper audio on a dedicated thread.
     * Does NOT queue on TtsEngine's internal executor.
     */
    fun speakBlocking(sender: String, message: String) {
        val text = "$sender says: $message"
        Timber.i("speakBlocking() called: piperReady=%b, fallbackReady=%b", piperReady, fallbackReady)

        if (piperReady) {
            speakPiperBlocking(text)
        } else if (fallbackReady) {
            speakFallback(text)
        } else {
            Timber.w("speakBlocking: no TTS engine ready, dropping message")
        }
    }

    /**
     * Split text into sentences and generate/play each one sequentially.
     * Piper VITS is fast enough (~0.35 RTF on RPi4) that on Pixel 9
     * generation should be near-instant.
     */
    private fun speakPiper(text: String) {
        executor.execute {
            try {
                val totalStart = System.currentTimeMillis()
                val tts = offlineTts as? com.k2fsa.sherpa.onnx.OfflineTts ?: run {
                    Timber.w("speakPiper: offlineTts is null, falling back")
                    speakFallback(text)
                    return@execute
                }
                val track = audioTrack ?: run {
                    Timber.w("speakPiper: audioTrack is null, falling back")
                    speakFallback(text)
                    return@execute
                }

                // Split into sentences for faster time-to-first-audio
                val sentences = text.split(Regex("(?<=[.!?])\\s+"))
                    .filter { it.isNotBlank() }

                Timber.d("speakPiper: %d sentence(s) for: %s", sentences.size, text)

                track.play()
                var firstAudioLogged = false

                for ((index, sentence) in sentences.withIndex()) {
                    val genStart = System.currentTimeMillis()
                    Timber.d("speakPiper: [%d/%d] generating: %s", index + 1, sentences.size, sentence)
                    val audio = tts.generate(
                        text = sentence,
                        sid = speakerId,
                        speed = speed,
                    )
                    val genElapsed = System.currentTimeMillis() - genStart
                    val audioDurationMs = (audio.samples.size.toFloat() / tts.sampleRate() * 1000).toLong()
                    Timber.i("speakPiper: [%d/%d] generated in %dms, audio=%dms, samples=%d",
                        index + 1, sentences.size, genElapsed, audioDurationMs, audio.samples.size)

                    if (!firstAudioLogged) {
                        val timeToFirstAudio = System.currentTimeMillis() - totalStart
                        Timber.i("speakPiper: TIME-TO-FIRST-AUDIO: %dms", timeToFirstAudio)
                        firstAudioLogged = true
                    }

                    val writeStart = System.currentTimeMillis()
                    track.write(audio.samples, 0, audio.samples.size, AudioTrack.WRITE_BLOCKING)
                    val writeElapsed = System.currentTimeMillis() - writeStart
                    Timber.d("speakPiper: [%d/%d] AudioTrack.write took %dms", index + 1, sentences.size, writeElapsed)
                }

                track.stop()
                track.flush()
                val totalElapsed = System.currentTimeMillis() - totalStart
                Timber.i("speakPiper: COMPLETE in %dms for %d sentence(s)", totalElapsed, sentences.size)

            } catch (e: Throwable) {
                Timber.e(e, "speakPiper: FAILED, falling back to system TTS")
                try { audioTrack?.stop() } catch (_: Throwable) {}
                speakFallback(text)
            }
        }
    }

    /**
     * Blocking Piper speak — runs on the calling thread.
     * Same logic as speakPiper but without the executor wrapper,
     * so it blocks until all audio has finished playing.
     */
    private fun speakPiperBlocking(text: String) {
        try {
            val totalStart = System.currentTimeMillis()
            val tts = offlineTts as? com.k2fsa.sherpa.onnx.OfflineTts ?: run {
                Timber.w("speakPiperBlocking: offlineTts is null, falling back")
                speakFallback(text)
                return
            }

            // Create a dedicated AudioTrack for this call (avoids sharing with async path)
            val track = createAudioTrack(tts.sampleRate())

            val sentences = text.split(Regex("(?<=[.!?])\\s+"))
                .filter { it.isNotBlank() }

            Timber.d("speakPiperBlocking: %d sentence(s)", sentences.size)

            track.play()

            for ((index, sentence) in sentences.withIndex()) {
                val genStart = System.currentTimeMillis()
                val audio = tts.generate(text = sentence, sid = speakerId, speed = speed)
                val genElapsed = System.currentTimeMillis() - genStart
                Timber.d("speakPiperBlocking: [%d/%d] generated in %dms", index + 1, sentences.size, genElapsed)
                track.write(audio.samples, 0, audio.samples.size, AudioTrack.WRITE_BLOCKING)
            }

            track.stop()
            track.flush()
            track.release()

            val totalElapsed = System.currentTimeMillis() - totalStart
            Timber.i("speakPiperBlocking: COMPLETE in %dms for %d sentence(s)", totalElapsed, sentences.size)

        } catch (e: Throwable) {
            Timber.e(e, "speakPiperBlocking: FAILED, falling back to system TTS")
            speakFallback(text)
        }
    }

    private fun speakFallback(text: String) {
        try {
            fallbackTts?.speak(text, TextToSpeech.QUEUE_ADD, null, "dispatch_${System.currentTimeMillis()}")
        } catch (e: Throwable) {
            Timber.e(e, "speakFallback: even fallback TTS failed")
        }
    }

    /**
     * Play pre-generated audio samples directly via AudioTrack.
     * Used for server-side TTS audio downloaded from Oasis.
     *
     * Creates a temporary AudioTrack at the given sample rate if it differs
     * from the Piper model's rate (or if Piper hasn't loaded yet).
     */
    fun playAudio(samples: FloatArray, sampleRate: Int) {
        executor.execute {
            try {
                val start = System.currentTimeMillis()
                Timber.i("playAudio: %d samples at %dHz (%.1fs audio)",
                    samples.size, sampleRate, samples.size.toFloat() / sampleRate)

                // Use existing AudioTrack if sample rate matches, otherwise create temp one
                val track = if (audioTrack != null && piperReady &&
                    (offlineTts as? com.k2fsa.sherpa.onnx.OfflineTts)?.sampleRate() == sampleRate) {
                    audioTrack!!
                } else {
                    createAudioTrack(sampleRate)
                }

                track.play()

                val firstAudioTime = System.currentTimeMillis() - start
                Timber.i("playAudio: TIME-TO-FIRST-AUDIO: %dms (from playAudio call)", firstAudioTime)

                track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
                track.stop()
                track.flush()

                // Release temp track if we created one
                if (track !== audioTrack) {
                    track.release()
                }

                val totalMs = System.currentTimeMillis() - start
                Timber.i("playAudio: COMPLETE in %dms", totalMs)

            } catch (e: Throwable) {
                Timber.e(e, "playAudio: FAILED")
            }
        }
    }

    private fun createAudioTrack(sampleRate: Int): AudioTrack {
        val bufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT
        )
        val attrs = AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setSampleRate(sampleRate)
            .build()
        return AudioTrack(
            attrs, format, bufferSize,
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE
        )
    }

    fun shutdown() {
        try {
            (offlineTts as? com.k2fsa.sherpa.onnx.OfflineTts)?.release()
        } catch (_: Throwable) {}
        offlineTts = null
        piperReady = false

        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (_: Throwable) {}
        audioTrack = null

        try {
            fallbackTts?.stop()
            fallbackTts?.shutdown()
        } catch (_: Throwable) {}
        fallbackTts = null

        executor.shutdownNow()
    }
}
