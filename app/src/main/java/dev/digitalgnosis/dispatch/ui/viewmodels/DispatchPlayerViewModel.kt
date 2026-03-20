package dev.digitalgnosis.dispatch.ui.viewmodels

import android.app.Application
import android.content.ComponentName
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.media3.common.Player
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionToken
import androidx.core.content.ContextCompat
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.digitalgnosis.dispatch.playback.DispatchPlaybackService
import dev.digitalgnosis.dispatch.playback.DispatchPlayerEventBus
import dev.digitalgnosis.dispatch.playback.DispatchPlaybackState
import dev.digitalgnosis.dispatch.playback.NowPlayingInfo
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber
import java.util.concurrent.ExecutionException
import javax.inject.Inject

/**
 * AndroidViewModel owning the MediaBrowser connection to [DispatchPlaybackService].
 *
 * Pattern: Gramophone MediaControllerViewModel.kt
 * — lifecycle-safe connect in onStart(), disconnect in onStop()
 * — SecurityException handling for session-rejected connections
 * — no direct ExoPlayer access from the UI layer
 *
 * The UI observes [DispatchPlayerEventBus] for playback state. This ViewModel
 * owns the MediaBrowser handle needed to send playback commands (play/pause/skip)
 * through the MediaSession protocol.
 *
 * Usage in a composable host:
 * ```kotlin
 * val viewModel: DispatchPlayerViewModel = hiltViewModel()
 * LaunchedEffect(lifecycle) {
 *     lifecycle.addObserver(viewModel)
 * }
 * val controller by viewModel.controller.collectAsState()
 * ```
 */
@HiltViewModel
class DispatchPlayerViewModel @Inject constructor(
    application: Application,
    val eventBus: DispatchPlayerEventBus,
) : AndroidViewModel(application), DefaultLifecycleObserver {

    private var controllerFuture: ListenableFuture<MediaBrowser>? = null

    /** The connected MediaBrowser, or null while connecting or after disconnect. */
    private var _controller: MediaBrowser? = null

    /** Expose playback state from the event bus for convenient UI observation. */
    val playbackState: kotlinx.coroutines.flow.SharedFlow<DispatchPlaybackState> =
        eventBus.stateChange

    val nowPlaying: kotlinx.coroutines.flow.SharedFlow<NowPlayingInfo?> =
        eventBus.nowPlayingChange

    val queueCount: kotlinx.coroutines.flow.SharedFlow<Int> =
        eventBus.queueCountChange

    // ── Lifecycle ────────────────────────────────────────────────────

    override fun onStart(owner: LifecycleOwner) {
        val sessionToken = SessionToken(
            getApplication(),
            ComponentName(getApplication(), DispatchPlaybackService::class.java)
        )

        controllerFuture = MediaBrowser
            .Builder(getApplication(), sessionToken)
            .buildAsync()
            .apply {
                addListener(
                    {
                        if (isCancelled) return@addListener
                        val instance = try {
                            get()
                        } catch (e: ExecutionException) {
                            if (e.cause is SecurityException &&
                                e.cause?.message == "Session rejected the connection request."
                            ) {
                                // Session was released before connection completed — not an error.
                                Timber.w("DispatchPlayerViewModel: session rejected connection " +
                                        "(likely released during connect)")
                                null
                            } else {
                                throw e
                            }
                        }
                        if (instance != null) {
                            _controller = instance
                            Timber.d("DispatchPlayerViewModel: MediaBrowser connected")
                        } else {
                            controllerFuture = null
                        }
                    },
                    ContextCompat.getMainExecutor(getApplication())
                )
            }
    }

    override fun onStop(owner: LifecycleOwner) {
        val future = controllerFuture ?: return
        if (future.isDone && !future.isCancelled) {
            try {
                future.get().release()
            } catch (_: Exception) {}
        } else {
            future.cancel(true)
        }
        _controller = null
        controllerFuture = null
        Timber.d("DispatchPlayerViewModel: MediaBrowser released")
    }

    // ── Command delegation ───────────────────────────────────────────

    /** Toggle play/pause via MediaSession. Safe to call when controller is null (no-op). */
    fun togglePlayPause() {
        val c = _controller ?: return
        if (c.isPlaying) c.pause() else c.play()
    }

    /** Skip to next queued message via MediaSession. */
    fun skipNext() {
        _controller?.seekToNextMediaItem()
    }

    /** Seek to position in current item. */
    fun seekTo(positionMs: Long) {
        _controller?.seekTo(positionMs)
    }

    /** Stop playback entirely. */
    fun stop() {
        _controller?.stop()
    }

    /**
     * Register a Player.Listener on the MediaBrowser connection.
     * The listener is automatically removed when [owner] is destroyed.
     */
    fun addPlayerListener(owner: LifecycleOwner, listener: Player.Listener) {
        val c = _controller ?: return
        c.addListener(listener)
        owner.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                c.removeListener(listener)
            }
        })
    }

    override fun onCleared() {
        // Ensure we don't leak if the ViewModel is cleared without onStop firing
        val future = controllerFuture
        if (future != null && future.isDone && !future.isCancelled) {
            try { future.get().release() } catch (_: Exception) {}
        }
    }
}
