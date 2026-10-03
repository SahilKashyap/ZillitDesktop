package com.zillit.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import com.zillit.desktop.core.common.ZillitLog
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import com.zillit.desktop.core.designsystem.component.FullScreen
import com.zillit.desktop.core.designsystem.component.LocalFullScreen
import com.zillit.desktop.core.designsystem.component.LocalVideoEngine
import com.zillit.desktop.core.designsystem.component.VideoEngine
import com.zillit.desktop.core.designsystem.component.VideoPlayback
import com.zillit.desktop.core.designsystem.component.VideoPlaybackState
import com.zillit.desktop.core.media.Mp4Rotation
import javafx.animation.PauseTransition
import javafx.application.Platform
import javafx.embed.swing.JFXPanel
import javafx.scene.Scene
import javafx.scene.layout.StackPane
import javafx.scene.media.Media
import javafx.scene.media.MediaPlayer
import javafx.scene.media.MediaView
import javafx.scene.paint.Color
import javafx.util.Duration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.net.HttpURLConnection
import java.net.URI
import javax.swing.SwingUtilities

/**
 * The app's [VideoEngine]: a clip playing inside the window, decoded by
 * JavaFX.
 *
 * ## Why not the Chromium that is already here
 *
 * The obvious player was a `<video>` in the embedded browser the app ships for
 * calls and hosted pages — no new dependency, and the control bar for free.
 * It cannot do the job. JetBrains builds JCEF **without the proprietary
 * codecs**, which was measured on this runtime rather than assumed:
 *
 *     canPlayType('video/mp4; codecs="avc1.42E01E")  →  ""
 *     canPlayType('video/webm; codecs="vp9")         →  "probably"
 *
 * H.264 and AAC are exactly what a camera, a phone and every other Zillit
 * client produce, so that browser can play almost nothing a production posts.
 * (Calls are unaffected: WebRTC reaches VideoToolbox directly, which is a
 * different path from `<video>` and is why the call window works.)
 *
 * ## One surface, for the life of the process
 *
 * [FxSurface] holds **one** `JFXPanel`, one `Scene` and one `MediaView`,
 * made on first use and never replaced. Each clip is a new `MediaPlayer`
 * handed to that view.
 *
 * This is not tidiness, it is the fix for a real defect found on the dev app
 * 2026-10-01: **constructing a second `JFXPanel` stalls the FX event queue.**
 * The first clip played; every clip after it sat on a blank panel at 0:00
 * with no error, because the `Platform.runLater` that would have built its
 * player never ran. Isolated in a harness — a second round with a fresh panel
 * never executes its FX block (`nativeStatus=null`), the identical round
 * reusing the panel reaches `PLAYING` — so the panel is created once here and
 * nothing is allowed to make another.
 *
 * ## The picture, and why the controls are not in it
 *
 * The surface is a `JFXPanel` in a `SwingPanel` — a platform component, which
 * Compose composes *over* its own canvas. Anything drawn on top of it is
 * painted over, so the transport cannot float on the picture the way a web
 * player's does. The viewer draws it underneath instead, from this app's
 * design system, which is why [VideoPlayback] hands playback state back out.
 *
 * ## Content types
 *
 * JavaFX picks its demuxer from the **HTTP `Content-Type`**, not from the
 * URL's path — also measured: an object served as `video/mp4` plays whether
 * its key ends in `.mov`, `.mp4` or nothing at all. That is what lets an
 * iPhone's `.mov` play here, and it is why the signing side asks S3 to answer
 * with `response-content-type=video/mp4` (see `NoticeMediaSource.streamUrl`).
 */
internal object FxVideoEngine : VideoEngine {

    override fun open(url: String): VideoPlayback = FxPlayback(url).also { it.start() }

    @Composable
    override fun Picture(playback: VideoPlayback, modifier: Modifier) {
        Box(modifier.fillMaxSize()) {
            SwingPanel(
                background = Color.BLACK.toCompose(),
                factory = { FxSurface.panel() },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * Hands the viewer this window's full-screen switch.
 *
 * Window chrome belongs to the host — a viewer composed three tools deep has
 * no `WindowState` to reach — so each window that can show a clip provides its
 * own. A window that cannot go full screen provides nothing and the button is
 * not drawn.
 */
@Composable
internal fun FullScreenMount(state: WindowState, content: @Composable () -> Unit) {
    val toggle = remember(state) {
        object : FullScreen {
            override val isFullScreen: Boolean
                get() = state.placement == WindowPlacement.Fullscreen

            override fun toggle() {
                state.placement = if (isFullScreen) WindowPlacement.Floating else WindowPlacement.Fullscreen
            }
        }
    }
    CompositionLocalProvider(LocalFullScreen provides toggle, content = content)
}

/** Puts the player in reach of every viewer in [content]. */
@Composable
internal fun VideoPlayerMount(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalVideoEngine provides ZillitVideoEngine, content = content)
}

/**
 * Picks the engine that can actually decode this clip.
 *
 * Neither of the two can do the other's formats — JavaFX has H.264/AAC and no
 * WebM, the runtime's Chromium has VP8/VP9/AV1 and no H.264 — so the choice is
 * made per clip rather than once.
 *
 * It is read from the URL, because the signing side has already decided: the
 * presigned address carries `response-content-type`, chosen there from the
 * object's own name (`NoticeMediaSource.streamUrl`). Reading that back is
 * cheaper than fetching bytes to sniff, and keeps one answer to "what is this
 * file" rather than two that can disagree.
 */
internal object ZillitVideoEngine : VideoEngine {

    override fun open(url: String): VideoPlayback = engineFor(url).open(url)

    @Composable
    override fun Picture(playback: VideoPlayback, modifier: Modifier) {
        // By the playback's own type, not by the URL again: the engine that
        // built it is the only one that can draw it.
        when (playback) {
            is KcefPlayback -> KcefVideoEngine.Picture(playback, modifier)
            else -> FxVideoEngine.Picture(playback, modifier)
        }
    }

    private fun engineFor(url: String): VideoEngine {
        val chromium = CHROMIUM_TYPES.firstOrNull { type ->
            url.contains("response-content-type=${type.replace("/", "%2F")}")
        }
        ZillitLog.i("ZillitVideoEngine") {
            "clip routed to ${if (chromium == null) "javafx" else "chromium ($chromium)"}"
        }
        return if (chromium == null) FxVideoEngine else KcefVideoEngine
    }

    /** What JavaFX refuses and Chromium takes. */
    private val CHROMIUM_TYPES = listOf("video/webm", "video/ogg")
}

/**
 * The one FX surface the whole app shares.
 *
 * One viewer is open at a time, so one surface is enough — and, per the class
 * note above, one is all JavaFX will tolerate: a second `JFXPanel` stops the
 * FX queue dead.
 */
private object FxSurface {

    @Volatile
    private var made: JFXPanel? = null

    /**
     * The Swing component, made once on the EDT.
     *
     * Constructing the `JFXPanel` is what starts the FX toolkit, so it happens
     * here rather than at app start: a session that never opens a clip never
     * pays for it. `SwingPanel`'s factory already runs on the EDT.
     */
    @Synchronized
    fun panel(): JFXPanel {
        made?.let { return it }
        val panel = JFXPanel()
        made = panel
        Platform.runLater {
            // **Without this, video works exactly once per session.** JavaFX
            // shuts its toolkit down when its last window goes away, and the
            // viewer takes the picture out of the tree every time it closes.
            // Everything after that posts work to a dead toolkit: no `ready`,
            // no error, a spinner that turns for ever — and `AWT-AppKit`
            // NullPointerExceptions out of `Application.staticScreen_getScreens`
            // from the orphaned panel, which is what finally named this.
            //
            // The toolkit cannot be restarted once it has gone, so it is kept
            // for the life of the process. It costs one idle thread.
            Platform.setImplicitExit(false)
            buildScene(panel)
        }
        return panel
    }

    /**
     * Builds the scene if it has none yet. Call on the FX thread.
     *
     * Called from [panel] *and* from each clip attaching, because the two
     * arrive by different routes — the composition mounting the picture, and
     * the viewer opening a clip — and nothing orders them. Assuming the scene
     * was already there is what left the player attached to nothing: audio
     * with a black picture.
     */
    fun ensureScene() {
        made?.let { buildScene(it) }
    }

    private fun buildScene(panel: JFXPanel) {
        // Already built: `panel()` is reached from the composition and from the
        // viewer opening a clip, and building twice is how the picture went
        // missing — see [show].
        if (panel.scene != null) return
        val mediaView = MediaView().apply { isPreserveRatio = true }
        // A StackPane rather than a Group: as the scene's root it is resized to
        // the scene by the layout, so the picture's size comes from the thing
        // that actually has one. A Group has no size of its own, which left the
        // view's binding reading a scene that was never laid out.
        val root = StackPane(mediaView)
        mediaView.fitWidthProperty().bind(root.widthProperty())
        mediaView.fitHeightProperty().bind(root.heightProperty())
        panel.scene = Scene(root, Color.BLACK)
    }

    /**
     * Turns the picture to the upright the file asked for. Call on the FX
     * thread.
     *
     * A quarter turn also swaps what "fits": the view is rotated about its own
     * centre *after* it is sized, so a sideways picture has to be fitted to the
     * pane's height by its width and the other way round, or it is letterboxed
     * into a box of the wrong shape.
     */
    fun orient(owner: MediaPlayer?, degrees: Int) {
        val mediaView = liveView() ?: return
        // Only the clip currently on screen may turn it. A rotation is read in
        // the background, so a slow answer for a clip the viewer has already
        // left would otherwise arrive and turn whatever replaced it.
        if (mediaView.mediaPlayer !== owner) return
        val root = made?.scene?.root as? StackPane ?: return
        mediaView.rotate = degrees.toDouble()
        mediaView.fitWidthProperty().unbind()
        mediaView.fitHeightProperty().unbind()
        val quarterTurn = degrees == QUARTER || degrees == THREE_QUARTERS
        mediaView.fitWidthProperty().bind(if (quarterTurn) root.heightProperty() else root.widthProperty())
        mediaView.fitHeightProperty().bind(if (quarterTurn) root.widthProperty() else root.heightProperty())
    }

    /**
     * Shows [player] in whatever view is on screen. Call on the FX thread.
     *
     * Reached through the live panel's own scene rather than a remembered
     * `MediaView`, because a remembered one is exactly what broke this: the
     * field ended up holding a view from a scene nobody was looking at, so the
     * player was attached to a 1×1 node while the visible view had none. Audio
     * played and the picture stayed black — the hardest shape of this bug to
     * read, because everything except the picture looked right.
     */
    fun show(player: MediaPlayer?) {
        liveView()?.mediaPlayer = player
    }

    /**
     * Takes [player] off the shared view — but only if it is still the one on
     * it.
     *
     * This conditional is the whole point. The viewer builds the next clip's
     * playback *before* it disposes the last one (Compose remembers the new
     * value, then runs the old `onDispose`), so an unconditional clear runs
     * **after** the new clip has already attached and silently removes it:
     * fixing one clip broke the next, which is exactly how this was found.
     */
    fun hide(player: MediaPlayer) {
        val mediaView = liveView() ?: return
        if (mediaView.mediaPlayer !== player) return
        mediaView.mediaPlayer = null
        orient(null, 0)
    }

    private fun liveView(): MediaView? =
        (made?.scene?.root as? StackPane)?.children?.firstOrNull() as? MediaView

    private const val QUARTER = 90
    private const val THREE_QUARTERS = 270

    fun started(): Boolean = made != null
}

/**
 * One clip, from the viewer opening to the viewer closing.
 *
 * Everything that touches JavaFX runs on the FX application thread, which is
 * not the EDT and is not the composition's. The state flow is the one thing
 * that crosses back.
 */
private class FxPlayback(private val url: String) : VideoPlayback {

    private val _state = MutableStateFlow(VideoPlaybackState())
    override val state: StateFlow<VideoPlaybackState> = _state.asStateFlow()

    @Volatile
    private var player: MediaPlayer? = null

    @Volatile
    private var closed = false

    /** The file's own upright, once read. Null until the header answers. */
    @Volatile
    private var rotation: Int? = null

    /**
     * Builds the player and puts it on the shared surface.
     *
     * Waits for the surface rather than assuming it: the viewer composes the
     * picture and opens the clip in the same frame, and on the very first clip
     * of a session the FX toolkit is still starting when this runs.
     */
    fun start() {
        SwingUtilities.invokeLater {
            // Creates the panel (and starts FX) if the picture has not yet
            // composed; it is the same panel either way.
            FxSurface.panel()
            Platform.runLater { attach() }
        }
        // Off any UI thread: this is two HTTP range requests in the worst case,
        // and the clip starts without waiting for them — an upright picture is
        // the common case and the right one to show first.
        Thread({ applyRotation() }, "zillit-video-rotation").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * Reads the file's own idea of upright and turns the picture to match.
     *
     * JavaFX ignores the track matrix (JDK-8091132), so a clip shot on a phone
     * held upright plays on its side here while the web and both phones show it
     * the right way up. The matrix is a few bytes near `moov`, so it is fetched
     * by range rather than by downloading the clip.
     */
    private fun applyRotation() {
        val degrees = runCatching { read() }
            .onFailure { thrown -> ZillitLog.w(TAG) { "rotation unread: ${thrown::class.simpleName}" } }
            .getOrNull() ?: 0
        if (closed) return
        rotation = degrees
        // The header read and the player's arrival race each other, so neither
        // one applies the turn on its own: both ask, and whichever is second
        // finds the other half waiting.
        Platform.runLater { orientIfMine() }
    }

    /**
     * The head first, then the tail — a clip never prepared for streaming keeps
     * its `moov` at the end of the file, which is most of what a phone writes.
     */
    private fun read(): Int {
        probe()
        val head = range(HEAD_BYTES)
        val fromHead = Mp4Rotation.find(head)
        if (fromHead != null) {
            ZillitLog.i(TAG) { "rotation $fromHead from the first ${head.size} bytes" }
            return fromHead
        }
        val tail = suffix(TAIL_BYTES)
        val fromTail = Mp4Rotation.find(tail)
        ZillitLog.i(TAG) { "rotation ${fromTail ?: "unknown"} from the last ${tail.size} bytes" }
        return fromTail ?: 0
    }

    private fun range(bytes: Int): ByteArray = fetch("bytes=0-${bytes - 1}")

    private fun suffix(bytes: Int): ByteArray = fetch("bytes=-$bytes")

    private fun fetch(range: String): ByteArray {
        val connection = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Range", range)
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
        }
        return try {
            connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * What the object answers to a **plain** GET — the request the player makes
     * before any range.
     *
     * Logged without the URL: a presigned address is a four-hour credential for
     * the object and does not belong in a file on disk. The status, the type
     * and the length are what a stall needs explaining, and none of them name
     * anything.
     */
    private fun probe() {
        runCatching {
            val connection = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                instanceFollowRedirects = true
            }
            try {
                val code = connection.responseCode
                ZillitLog.i(TAG) {
                    "plain GET: $code type=${connection.contentType} " +
                        "length=${connection.contentLengthLong} " +
                        "ranges=${connection.getHeaderField("Accept-Ranges")}"
                }
            } finally {
                connection.disconnect()
            }
        }.onFailure { thrown ->
            ZillitLog.w(TAG) { "plain GET failed: ${thrown::class.simpleName} ${thrown.message.orEmpty()}" }
        }
    }

    private fun attach() {
        if (closed) return
        runCatching {
            val media = Media(url)
            val made = MediaPlayer(media)
            player = made
            wire(made, media)
            FxSurface.ensureScene()
            FxSurface.show(made)
            orientIfMine()
            made.play()
            watchForStall(made)
        }.onFailure { thrown ->
            // A container JavaFX will not take arrives here, not as an error
            // callback — `Media`'s constructor throws it.
            ZillitLog.w(TAG) { "video refused: ${thrown::class.simpleName} ${thrown.message.orEmpty()}" }
            _state.update { it.copy(failed = true) }
        }
    }

    /** Turns the picture, once both the player and the rotation exist. */
    private fun orientIfMine() {
        if (closed) return
        val made = player ?: return
        val degrees = rotation ?: return
        FxSurface.orient(made, degrees)
    }

    private fun wire(made: MediaPlayer, media: Media) {
        // Every write goes through update rather than copying `value`: the
        // time listener fires continuously while these callbacks land, and a
        // read-modify-write between the two drops whichever arrived second —
        // most visibly the duration, without which the bar cannot be dragged.
        made.setOnError { fail(made.error?.type?.toString()) }
        media.setOnError { fail(media.error?.type?.toString()) }
        made.setOnReady {
            ZillitLog.i(TAG) { "video ready: ${media.duration.millis()} ms" }
            _state.update { was -> was.copy(durationMillis = media.duration.millis()) }
        }
        // `started` latches here: the first frame is on screen, so the viewer
        // can put its spinner away and never bring it back.
        made.setOnPlaying { _state.update { was -> was.copy(isPlaying = true, started = true) } }
        made.setOnPaused { _state.update { was -> was.copy(isPlaying = false) } }
        made.setOnStopped { _state.update { was -> was.copy(isPlaying = false) } }
        // The clip runs out: the transport must show a play button again, not
        // a pause button over a frozen last frame.
        made.setOnEndOfMedia { _state.update { was -> was.copy(isPlaying = false) } }
        made.currentTimeProperty().addListener { _, _, now ->
            _state.update { was -> was.copy(positionMillis = now.millis()) }
        }
    }

    /**
     * Gives up on a clip that never starts.
     *
     * JavaFX can sit in `UNKNOWN` indefinitely on an object it cannot make
     * sense of — no error callback, no `ready`, nothing — and the viewer's
     * spinner then turns forever. Seen on the dev app 2026-10-01. A clip that
     * has not produced a frame by now is reported, with the player's own status
     * in the log so the next one of these is diagnosable.
     */
    private fun watchForStall(made: MediaPlayer) {
        PauseTransition(Duration.millis(STALL_MS.toDouble())).apply {
            setOnFinished {
                if (closed || _state.value.started) return@setOnFinished
                ZillitLog.w(TAG) { "video never started: status=${made.status} error=${made.error}" }
                fail("stalled at ${made.status}")
            }
            play()
        }
    }

    private fun fail(reason: String?) {
        ZillitLog.w(TAG) { "video failed: ${reason.orEmpty()}" }
        _state.update { it.copy(failed = true, isPlaying = false) }
    }

    override fun toggleMute() {
        val made = player ?: return
        Platform.runLater { if (!closed) made.isMute = !made.isMute }
        // Answered from the flow rather than read back: `isMute` is an FX
        // property and the transport is drawn on the composition's thread.
        _state.update { it.copy(isMuted = !it.isMuted) }
    }

    override fun toggle() {
        val made = player ?: return
        Platform.runLater {
            when {
                closed -> Unit
                made.status == MediaPlayer.Status.PLAYING -> made.pause()
                // Sitting on the last frame: play from there would do nothing.
                made.media.duration.usable() && made.currentTime >= made.media.duration -> {
                    made.seek(Duration.ZERO)
                    made.play()
                }
                else -> made.play()
            }
        }
    }

    override fun seek(fraction: Float) {
        val made = player ?: return
        Platform.runLater {
            if (closed) return@runLater
            val total = made.media.duration
            if (!total.usable()) return@runLater
            made.seek(total.multiply(fraction.coerceIn(0f, 1f).toDouble()))
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        val made = player
        player = null
        // Nothing was ever started: a viewer can open and shut again before the
        // toolkit is up, and `Platform.runLater` against one that has not
        // started throws rather than queuing.
        if (!FxSurface.started()) return
        Platform.runLater {
            // Only if this clip is still the one being shown — see [FxSurface.hide].
            made?.let { FxSurface.hide(it) }
            runCatching { made?.dispose() }
        }
    }

    private companion object {
        const val TAG = "FxVideoEngine"

        /**
         * Enough of the front of the file to hold `moov` when the upload put it
         * there — a track header is a few hundred bytes, and what precedes it
         * is `ftyp` and, on a streaming-ready clip, nothing else.
         */
        const val HEAD_BYTES = 256 * 1024

        /** And the end, for a clip written straight off a camera. */
        const val TAIL_BYTES = 512 * 1024

        const val TIMEOUT_MS = 10_000

        /**
         * How long a clip may take to show its first frame before the viewer
         * calls it dead. Generous: a large object over a slow line genuinely
         * takes a while, and a false refusal is worse than a slow one.
         */
        const val STALL_MS = 30_000
    }
}

/** The FX black, as Compose spells it — the surface behind an unfilled picture. */
private fun Color.toCompose(): androidx.compose.ui.graphics.Color =
    androidx.compose.ui.graphics.Color(red.toFloat(), green.toFloat(), blue.toFloat(), opacity.toFloat())

/**
 * A length to reckon with.
 *
 * JavaFX reports a length it does not know yet as `UNKNOWN` (a NaN) and a live
 * stream as `INDEFINITE` (an infinity); arithmetic on either produces a seek
 * target that is not a time.
 */
private fun Duration.usable(): Boolean = !isUnknown && !isIndefinite

/** An unknown or indefinite JavaFX duration is no duration, not a NaN. */
private fun Duration.millis(): Long =
    toMillis().takeIf { it.isFinite() && it >= 0 }?.toLong() ?: 0L
