package dev.digitalgnosis.dispatch.playback

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.media3.common.Player
import dev.digitalgnosis.dispatch.data.VoiceNotificationRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Manages the full voice reply interaction loop:
 *   1. Play TTS audio cue ("Reply to boardroom. Go ahead.")
 *   2. Wait for cue to FINISH playing (ExoPlayer STATE_ENDED) — not a hardcoded delay
 *   3. Start speech recognition, play listening beep on mic-ready
 *   4. On transcription: send via [VoiceReplyCoordinator], play confirmation cue
 *   5. On error: play error status message
 *
 * This replaces the [DispatchPlaybackService] inner logic for voice reply and fixes
 * the VOICE_CUE_DELAY_MS timing hack by using an ExoPlayer STATE_ENDED listener.
 */
internal class VoiceReplySession(
    private val context: Context,
    private val player: Player,
    private val voiceNotificationRepository: VoiceNotificationRepository,
    private val voiceReplyCoordinator: VoiceReplyCoordinator,
    private val scope: CoroutineScope,
    private val callbacks: Callbacks,
) {

    interface Callbacks {
        /** Play a short TTS status message (e.g. the cue, error messages, confirmation). */
        fun playStatusMessage(text: String)
    }

    /**
     * Begin a voice reply session. Resolves the target department from the last notification,
     * plays the audio cue, and starts speech recognition once the cue finishes playing.
     *
     * Must be called on the main thread.
     */
    fun start() {
        // Use the playing message's context, not the newest message (cursor position 0).
        // The cursor resets to 0 on every add(), so without this, a queued message N arriving
        // while message M plays would cause the reply to target the wrong (newest) sender.
        val lastNotification = voiceNotificationRepository.getPlayingOrAtCursor()
        val rawSender = lastNotification?.sender
        val targetDepartment = if (rawSender.isNullOrBlank() || rawSender == "dispatch") {
            "boardroom"
        } else {
            rawSender
        }
        val threadId = lastNotification?.cmailThreadId

        Timber.i("VoiceReply: starting reply to %s (thread=%s)", targetDepartment, threadId ?: "none")

        // Play the audio cue. This queues a WAV media item on the ExoPlayer instance.
        callbacks.playStatusMessage("Reply to $targetDepartment. Go ahead.")

        // Wait for the cue to actually finish before opening the mic.
        // We register a one-shot listener that fires on the next STATE_ENDED — that is the cue item.
        // This replaces the old VOICE_CUE_DELAY_MS = 2500L hardcoded guess.
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    player.removeListener(this)
                    Timber.i("VoiceReply: cue finished (STATE_ENDED) — opening mic")
                    launchSpeechRecognition(targetDepartment, threadId)
                }
            }
        })
    }

    private fun launchSpeechRecognition(targetDepartment: String, threadId: String?) {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Timber.e("VoiceReply: SpeechRecognizer not available")
            callbacks.playStatusMessage("Voice recognition not available")
            return
        }

        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Timber.i("VoiceReply: mic is hot")
                playListeningBeep()
            }
            override fun onBeginningOfSpeech() { Timber.i("VoiceReply: speech detected") }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { Timber.i("VoiceReply: end of speech") }

            override fun onError(error: Int) {
                val msg = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH -> "Didn't catch that"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected"
                    SpeechRecognizer.ERROR_AUDIO -> "Audio error"
                    SpeechRecognizer.ERROR_NETWORK -> "Network error"
                    else -> "Recognition error $error"
                }
                Timber.w("VoiceReply: error %d — %s", error, msg)
                callbacks.playStatusMessage(msg)
                recognizer.destroy()
            }

            override fun onResults(results: Bundle?) {
                val transcription = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()

                if (transcription.isNullOrBlank()) {
                    Timber.w("VoiceReply: empty transcription")
                    callbacks.playStatusMessage("Didn't catch that")
                    recognizer.destroy()
                    return
                }

                Timber.i("VoiceReply: transcribed %d chars -> %s", transcription.length, targetDepartment)
                sendVoiceReply(targetDepartment, transcription, threadId)
                recognizer.destroy()
            }

            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
        recognizer.startListening(intent)
    }

    private fun sendVoiceReply(department: String, message: String, threadId: String?) {
        scope.launch(Dispatchers.IO) {
            val sessionId = voiceReplyCoordinator.sendVoiceReply(department, message, threadId)
            val statusMsg = if (sessionId != null) "Sent to $department" else "Failed to send reply"
            withContext(Dispatchers.Main) {
                callbacks.playStatusMessage(statusMsg)
            }
        }
    }

    private fun playListeningBeep() {
        try {
            val toneGen = ToneGenerator(AudioManager.STREAM_MUSIC, 80)
            toneGen.startTone(ToneGenerator.TONE_PROP_BEEP, 200)
            Handler(Looper.getMainLooper()).postDelayed({ toneGen.release() }, 300)
        } catch (e: Exception) {
            Timber.w(e, "VoiceReply: beep failed (non-fatal)")
        }
    }
}
