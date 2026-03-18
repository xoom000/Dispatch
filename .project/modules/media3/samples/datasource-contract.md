# BaseDataSource Contract Requirements

Source: OkHttpDataSource.java — https://github.com/androidx/media/blob/release/libraries/datasource_okhttp/src/main/java/androidx/media3/datasource/okhttp/OkHttpDataSource.java
Source: DefaultDataSource.java — https://github.com/androidx/media/blob/release/libraries/datasource/src/main/java/androidx/media3/datasource/DefaultDataSource.java

---

## Transfer Listener Lifecycle (Verified from OkHttpDataSource)

Call these methods IN ORDER. Skipping or reordering breaks bandwidth metering and cache stats.

```
open(dataSpec):
  1. transferInitializing(dataSpec)   <- before any I/O attempt
  2. [do your connection/open work]
  3. transferStarted(dataSpec)        <- after successful open, before first read
  4. return bytesAvailable (or C.LENGTH_UNSET)

read(buffer, offset, length):
  1. [read bytes from source]
  2. bytesTransferred(bytesRead)      <- after EVERY successful read, pass actual count
  3. return bytesRead                 <- NEVER return 0 (blocks forever); return C.RESULT_END_OF_INPUT (-1)

close():
  1. transferEnded()                  <- call even if read never happened (if opened=true)
  2. [close underlying resource]
  3. null out references
```

---

## open() Return Value

- Return `bytesRemaining` if known (e.g., Content-Length from HTTP header)
- Return `C.LENGTH_UNSET` (-1) if length unknown (streaming, chunked transfer)
- Throw `DataSourceException` or `IOException` on failure -- NOT return -1

## read() Return Value

- Return actual bytes read (> 0) on success
- Return `C.RESULT_END_OF_INPUT` (-1) when source is exhausted
- **NEVER return 0** -- this causes an infinite retry loop in ExoPlayer's loading thread
- `length == 0` is the only case where returning 0 is valid (guard at top of method)

## getUri() Contract

- Return the URI from the active/open response (not the request URI)
- For HTTP: return the URI after redirects (from response)
- Return null if not currently open

## DataSpec.position

- `dataSpec.position` is the byte offset to start reading from
- Must skip/seek to this position in open() before returning
- For streaming sources that don't support seeking, throw if position > 0

## DataSpec.length

- `C.LENGTH_UNSET` means read to end of source
- Any other value is a byte limit -- enforce it in read()

## isNetwork Parameter (BaseDataSource constructor)

- Pass `true` for network sources (HTTP, streaming)
- Pass `false` for local sources (file, asset, content resolver)
- Controls how bandwidth is reported to transfer listeners

## Thread Safety

- ExoPlayer calls DataSource from its loading thread only
- No synchronization needed inside DataSource methods
- Factory.createDataSource() may be called from multiple threads -- make it thread-safe

---

## CRITICAL: What My DataSource Was Missing

1. `transferInitializing(dataSpec)` in open() -- not called at all
2. `transferStarted(dataSpec)` in open() -- not called at all
3. `transferEnded()` in close() -- not called at all
4. `bytesTransferred(bytesRead)` in read() -- added late but may not be enough without the lifecycle calls

The FULL lifecycle (init -> started -> bytes -> ended) must be present for ExoPlayer's
loading infrastructure to track the DataSource state correctly.
