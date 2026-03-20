package dev.digitalgnosis.dispatch.tts

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Adaptive token-batch buffer for the on-device TTS path (Piper/Sherpa-ONNX).
 *
 * Pattern: NekoSpeak KokoroEngine.generate() — adaptive batching + immediate first-sentence flush.
 *
 * Problem: [TtsEngine.speakBlocking] synthesizes the entire text as one call, which means
 * the user waits for the full ONNX inference before hearing the first word. For short status
 * messages this is acceptable (~200ms). For longer fallback text (multi-sentence), this causes
 * a noticeable pause.
 *
 * Solution: Split text at sentence boundaries, flush the first sentence immediately to achieve
 * ~200ms time-to-first-audio, accumulate subsequent sentences up to [MAX_TOKENS_PER_BATCH]
 * before running inference. Never split mid-sentence to avoid audio artifacts.
 *
 * Integration: Used by [TtsEngine] for the on-device synthesis path. The GPU streaming path
 * ([TtsStreamDataSource]) does not use this class — the GPU handles batching server-side.
 *
 * Thread safety: [synthMutex] serializes synthesis. Only one generation runs at a time.
 * [stopRequested] is volatile so it is visible across threads immediately.
 */
@Singleton
class DispatchAdaptiveTtsBuffer @Inject constructor() {

    companion object {
        /**
         * Default max tokens per batch for the Piper/Sherpa-ONNX on-device path.
         * Piper processes whole sentences, so this is a sentence-count heuristic:
         * accumulate sentences until their combined estimated token count exceeds this limit.
         * ~150 matches Kokoro's MAX_TOKENS from NekoSpeak (conservative for on-device).
         */
        private const val MAX_TOKENS_PER_BATCH = 150

        /**
         * Rough chars-per-token estimate for English text. Used to decide when to flush
         * the accumulated batch without a real tokenizer.
         * English averages ~4 chars/token for subword tokenizers.
         */
        private const val CHARS_PER_TOKEN = 4

        private const val TAG = "DispatchAdaptiveTtsBuffer"
    }

    /** Prevents concurrent synthesis. One generation at a time. */
    private val synthMutex = Mutex()

    /** Set true to abort the current generation between sentence batches. */
    @Volatile
    var stopRequested = false
        private set

    /**
     * Split [text] into sentences, synthesize with adaptive batching, invoke [onChunk] for each
     * float audio chunk. Returns when all chunks have been emitted or [stopRequested] is set.
     *
     * @param text The full text to synthesize.
     * @param synthesize Suspend function that synthesizes a batch of text and returns float PCM.
     *                   Receives a single string (one or more sentences joined with space).
     * @param onChunk Callback invoked for each synthesized float array chunk.
     */
    suspend fun generateAdaptive(
        text: String,
        synthesize: suspend (String) -> FloatArray,
        onChunk: (FloatArray) -> Unit,
    ) = withContext(Dispatchers.IO) {
        synthMutex.withLock {
            stopRequested = false
            val startTotal = System.currentTimeMillis()

            val sentences = splitSentences(text)
            if (sentences.isEmpty()) {
                Timber.d("$TAG: no sentences to synthesize")
                return@withLock
            }

            Timber.d("$TAG: synthesizing %d sentences, first immediate flush", sentences.size)

            // ── FIRST SENTENCE: flush immediately for low latency ──────────────────
            val firstSentence = sentences[0]
            if (firstSentence.isNotBlank() && isActive && !stopRequested) {
                val firstStart = System.currentTimeMillis()
                val firstChunk = synthesize(firstSentence)
                val firstMs = System.currentTimeMillis() - firstStart
                Timber.d("$TAG: first sentence synthesized in %dms (%d samples)", firstMs, firstChunk.size)
                onChunk(firstChunk)
            }

            if (sentences.size == 1) {
                Timber.d("$TAG: single sentence, done in %dms total", System.currentTimeMillis() - startTotal)
                return@withLock
            }

            // ── SUBSEQUENT SENTENCES: accumulate up to MAX_TOKENS_PER_BATCH ────────
            val batchSentences = mutableListOf<String>()
            var batchTokenEstimate = 0

            for (i in 1 until sentences.size) {
                if (!isActive || stopRequested) {
                    Timber.d("$TAG: stop requested at sentence %d/%d", i, sentences.size)
                    break
                }

                val sentence = sentences[i]
                if (sentence.isBlank()) continue

                val tokenEstimate = estimateTokens(sentence)

                // If this single sentence would exceed the limit on its own, process it alone
                // to avoid splitting mid-sentence (which causes audio artifacts).
                if (tokenEstimate > MAX_TOKENS_PER_BATCH) {
                    // First flush any accumulated batch
                    if (batchSentences.isNotEmpty()) {
                        flushBatch(batchSentences, synthesize, onChunk)
                        batchSentences.clear()
                        batchTokenEstimate = 0
                    }
                    Timber.w("$TAG: sentence %d exceeds token limit (%d tokens), processing as single batch",
                        i, tokenEstimate)
                    if (isActive && !stopRequested) {
                        flushBatch(listOf(sentence), synthesize, onChunk)
                    }
                    continue
                }

                // If adding this sentence would overflow the batch, flush first
                if (batchTokenEstimate + tokenEstimate > MAX_TOKENS_PER_BATCH && batchSentences.isNotEmpty()) {
                    flushBatch(batchSentences, synthesize, onChunk)
                    batchSentences.clear()
                    batchTokenEstimate = 0
                }

                batchSentences.add(sentence)
                batchTokenEstimate += tokenEstimate
            }

            // Flush remaining sentences
            if (batchSentences.isNotEmpty() && isActive && !stopRequested) {
                flushBatch(batchSentences, synthesize, onChunk)
            }

            Timber.d("$TAG: generation complete in %dms total", System.currentTimeMillis() - startTotal)
        }
    }

    /** Request the current generation to stop between batches. Does not interrupt in-progress ONNX inference. */
    fun stop() {
        stopRequested = true
        Timber.d("$TAG: stop requested")
    }

    private suspend fun flushBatch(
        sentences: List<String>,
        synthesize: suspend (String) -> FloatArray,
        onChunk: (FloatArray) -> Unit,
    ) {
        val batchText = sentences.joinToString(" ")
        val batchStart = System.currentTimeMillis()
        val chunk = synthesize(batchText)
        Timber.d("$TAG: batch (%d sentences) synthesized in %dms (%d samples)",
            sentences.size, System.currentTimeMillis() - batchStart, chunk.size)
        onChunk(chunk)
    }

    /**
     * Split text into sentences at [.!?] terminators, semicolons, and newlines.
     * If the first sentence is very long (>75 chars), attempt to split at commas
     * to reduce time-to-first-audio.
     *
     * Pattern: NekoSpeak KokoroEngine.generate() — verbatim sentence splitting logic.
     */
    private fun splitSentences(text: String): List<String> {
        val rawSentences = text.split(Regex("(?<=[.!?])\\s+|\\n+|(?<=;)\\s+"))
            .map { it.trim() }
            .filter { it.isNotBlank() }

        if (rawSentences.isEmpty()) return emptyList()

        // If first sentence is long, try to split at commas for faster first-audio
        return if (rawSentences[0].length > 75) {
            val first = rawSentences[0]
            val rest = rawSentences.drop(1)
            val subParts = first.split(Regex("(?<=,)\\s+"))
            if (subParts.size > 1) {
                subParts + rest
            } else {
                // Hard split at word boundary around 60 chars
                val splitPoint = first.lastIndexOf(' ', 60).takeIf { it > 0 } ?: 60
                listOf(first.substring(0, splitPoint), first.substring(splitPoint).trim()) + rest
            }
        } else {
            rawSentences
        }
    }

    /**
     * Rough token count estimate using character count / [CHARS_PER_TOKEN].
     * Not a real tokenizer — conservative enough to avoid overshooting the batch limit.
     */
    private fun estimateTokens(sentence: String): Int =
        (sentence.length / CHARS_PER_TOKEN).coerceAtLeast(1)
}
