# Streaming TTS Audio to ExoPlayer -- Patterns Found

## Sources Searched
- GitHub: "ExoPlayer TTS streaming" / "ExoPlayer chunked audio DataSource"
- GitHub: "ExoPlayer PipedOutputStream"
- ExoPlayer issue tracker: https://github.com/google/ExoPlayer/issues
- Media3 issue tracker: https://github.com/androidx/media/issues

## Search Result: No dedicated public repo found

No popular public repository specifically implements TTS-to-ExoPlayer streaming via custom
DataSource. The pattern must be assembled from parts.

---

## Pattern 1: PipedInputStream/PipedOutputStream Bridge
Source: https://github.com/google/ExoPlayer/issues/7566

Most viable approach for streaming TTS. Confirmed working via issue discussion:

```
TTS thread                        ExoPlayer loading thread
--------------------              --------------------------
tts.synthesize(text)  --bytes-->  PipedInputStream.read()
    |                                     |
PipedOutputStream                  InputStreamDataSource.read()
                                          |
                                   ProgressiveMediaSource
                                          |
                                      ExoPlayer
```

**Critical detail:** Default PipedInputStream buffer = 1024 bytes. This causes constant
blocking on the TTS thread. Use `PipedInputStream(65536)` (64KB) minimum.

**Known issue:** ExoPlayer probes the DataSource for length in open(). Return
`C.LENGTH_UNSET` -- do not block waiting for total length. ExoPlayer handles unknown-length
streams fine via ProgressiveMediaSource.

---

## Pattern 2: ByteArrayDataSource for Completed Audio
Source: https://developer.android.com/reference/androidx/media3/datasource/ByteArrayDataSource

For non-streaming use (full audio in memory before playback):
- Use `ByteArrayDataSource(byteArray)`
- Wrap in `DataSource.Factory { ByteArrayDataSource(audio) }`
- No custom DataSource needed

Limitation: Must buffer entire audio before starting playback. Not suitable for long TTS.

---

## Pattern 3: HTTP POST DataSource (Our Current Approach)
Assembled from OkHttpDataSource patterns and issue #7566.

Make the TTS POST request inside DataSource.open(), then stream the response body
bytes through DataSource.read(). ExoPlayer's loading thread handles the blocking.

```
ExoPlayer loading thread:
  1. DataSource.open()  -> POST to TTS server, get response
  2. DataSource.read()  -> read chunked response body bytes
  3. WavExtractor       -> parse WAV header, extract PCM
  4. AudioRenderer      -> play PCM as it arrives
```

**CRITICAL contract requirements (verified from OkHttpDataSource):**
- open(): call transferInitializing(dataSpec) THEN transferStarted(dataSpec)
- read(): call bytesTransferred(count) after EVERY successful read
- read(): return C.RESULT_END_OF_INPUT when stream.read() returns -1
- read(): NEVER return 0 -- block instead
- close(): call transferEnded()

**What breaks it:**
1. Missing transferInitializing/transferStarted/transferEnded lifecycle calls
2. Returning 0 from read() instead of blocking -- causes infinite loop
3. Not calling bytesTransferred() -- breaks bandwidth accounting
4. WAV header with oversized data_size -- extractor waits for claimed bytes
   BUT: WavExtractor's PassthroughOutputWriter DOES handle END_OF_INPUT
   from the inner trackOutput.sampleData() call when DataSource returns -1.
   The DataSource must actually return -1 for this to work.

---

## Our Specific Setup

Server: Kokoro TTS at oasis:8400/api/tts/stream
- POST with JSON body: {text, voice, speed}
- Returns: chunked WAV (44-byte header with placeholder size + PCM chunks per sentence)
- First chunk: ~750ms (one sentence of audio)
- Stream closes when all sentences generated

Client: DispatchPlaybackService on Android
- Custom TtsStreamDataSource extends BaseDataSource
- POSTs to Kokoro in open()
- Streams response body bytes in read()
- ExoPlayer's WavExtractor parses the WAV, plays PCM progressively

The WavExtractor source (wav-extractor-source.java in this directory) confirms that
END_OF_INPUT propagation works through PassthroughOutputWriter.sampleData().
The DataSource MUST return C.RESULT_END_OF_INPUT (-1) for this chain to fire.
