package com.zillit.desktop.core.appupdate

import java.io.File
import com.zillit.desktop.core.common.ZillitLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Download → verify → stage → (on request) restart into the new build.
 *
 * One instance for the app, holding one [InstallState] that the banner and
 * Settings both read, so a download started from either shows in both and a
 * second click does not start a second download.
 *
 * Nothing here ends the process. [launchInstaller] starts the helper and
 * reports whether it did; quitting is the caller's, because only the app knows
 * how to shut down tidily (Chromium first, window geometry saved).
 *
 * @param installer null where in-app install cannot work — see
 *   [PlatformInstaller.forCurrent]. The banner then keeps its download link.
 */
class InAppUpdater(
    private val downloader: UpdateDownloader,
    private val installer: PlatformInstaller?,
    private val scope: CoroutineScope,
    private val appPid: () -> Long = { ProcessHandle.current().pid() },
    /**
     * Downloads an installer and hands it to the person — see [downloadAndOpen].
     * Null where it is not wired; the banner then keeps its browser link.
     */
    private val handoff: InstallerHandoff? = null,
) {
    private val _state = MutableStateFlow<InstallState>(InstallState.Idle)
    val state: StateFlow<InstallState> = _state.asStateFlow()

    private var job: Job? = null
    private var prepared: Pair<String, PreparedUpdate>? = null
    private var downloaded: File? = null

    /** True when [ref] can be installed here without leaving the app. */
    fun canInstall(ref: InstallerRef?): Boolean = ref != null && installer?.accepts(ref.url) == true

    /** True when [url] can be downloaded inside the app and handed over — see [downloadAndOpen]. */
    fun canDownload(url: String?): Boolean = handoff?.accepts(url) == true

    /**
     * Downloads [url] into the Downloads folder with a progress bar, then
     * opens it for the person to install — the path the update button takes
     * whenever [start] cannot, instead of sending them to a browser.
     *
     * Installs nothing itself. Nothing here reaches [PlatformInstaller]: this
     * app is not replaced in place, no helper waits for it to quit, and
     * nothing asks for an administrator on its behalf. What it does is open
     * the file, having first marked it as coming from the internet, so the OS
     * checks it exactly as it would a browser download the person
     * double-clicked — on Windows that starts the installer, which elevates
     * itself and reports to them, not to us. That equivalence is what makes it
     * safe to offer without a checksum of our own.
     */
    fun downloadAndOpen(version: String, url: String) {
        val handoff = handoff ?: return
        if (!handoff.accepts(url)) return
        val current = _state.value
        if (current.version == version && current is InstallState.Downloading) return

        job?.cancel()
        prepared = null
        downloaded = null
        _state.value = InstallState.Downloading(version, fraction = null)
        job = scope.launch {
            try {
                val file = handoff.fetch(url) { copied, total -> if (isActive) report(version, copied, total) }
                withContext(Dispatchers.IO) { handoff.handOver(file) }
                downloaded = file
                _state.value = InstallState.Downloaded(version)
                ZillitLog.i(TAG) { "update $version downloaded and handed over" }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: UpdateFailure) {
                stillWanted()
                ZillitLog.w(TAG) { "update $version download stopped: ${failure.reason} — ${failure.message}" }
                _state.value = InstallState.Failed(version, failure.reason)
            } catch (@Suppress("TooGenericExceptionCaught") unexpected: Exception) {
                stillWanted()
                ZillitLog.w(TAG) { "update $version download stopped: ${unexpected::class.simpleName}" }
                _state.value = InstallState.Failed(version, UpdateFailure.Reason.Network)
            }
        }
    }

    /**
     * Stops a download in progress and forgets it. The partial file is removed
     * by the download itself as it unwinds; nothing is left looking finished.
     */
    fun cancel() {
        if (_state.value !is InstallState.Downloading) return
        job?.cancel()
        job = null
        _state.value = InstallState.Idle
    }

    /**
     * Opens the downloaded installer again — for someone who closed its window.
     *
     * A file they have since moved or deleted is forgotten, so the strip offers
     * the download again rather than a button that does nothing.
     */
    fun openDownloaded() {
        val version = (_state.value as? InstallState.Downloaded)?.version ?: return
        val file = downloaded?.takeIf(File::exists)
        if (file == null) {
            downloaded = null
            _state.value = InstallState.Idle
            return
        }
        scope.launch(Dispatchers.IO) {
            try {
                handoff?.handOver(file)
            } catch (failure: UpdateFailure) {
                // handOver removed the file: it could not be marked for checking.
                downloaded = null
                _state.compareAndSet(InstallState.Downloaded(version), InstallState.Failed(version, failure.reason))
            }
        }
    }

    /**
     * Fetches and prepares [version]. A no-op while that version is already
     * on its way or ready; a different version replaces whatever was running.
     */
    fun start(version: String, ref: InstallerRef) {
        val platform = installer ?: return
        if (!platform.accepts(ref.url)) return
        val current = _state.value
        if (current.version == version && current !is InstallState.Failed) return

        job?.cancel()
        prepared = null
        _state.value = InstallState.Downloading(version, fraction = null)
        job = scope.launch {
            try {
                val file = downloader.fetch(ref) { copied, total -> if (isActive) report(version, copied, total) }
                _state.value = InstallState.Preparing(version)
                val ready = withContext(Dispatchers.IO) { platform.prepare(file) }
                prepared = version to ready
                _state.value = InstallState.Ready(version)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: UpdateFailure) {
                stillWanted()
                ZillitLog.w(TAG) { "update $version stopped: ${failure.reason} — ${failure.message}" }
                _state.value = InstallState.Failed(version, failure.reason)
            } catch (@Suppress("TooGenericExceptionCaught") unexpected: Exception) {
                stillWanted()
                ZillitLog.w(TAG) { "update $version stopped: ${unexpected::class.simpleName}" }
                _state.value = InstallState.Failed(version, UpdateFailure.Reason.Install)
            }
        }
    }

    /**
     * Starts the helper for the prepared build.
     *
     * @return true when the helper is running and the app must now quit;
     *   false when nothing was ready or the helper would not start, in which
     *   case the state says why and the app stays open.
     */
    fun launchInstaller(): Boolean {
        val platform = installer ?: return false
        val (version, update) = prepared ?: return false
        if ((_state.value as? InstallState.Ready)?.version != version) return false
        return try {
            platform.launch(update, appPid())
            ZillitLog.i(TAG) { "update $version handed to the installer; quitting" }
            true
        } catch (failure: UpdateFailure) {
            ZillitLog.w(TAG) { "update $version could not start: ${failure.message}" }
            _state.value = InstallState.Failed(version, failure.reason)
            false
        }
    }

    /**
     * Publishes download progress in whole percents — only onto this
     * version's download still in progress.
     *
     * A cancelled job can report once more on its way out: the bytes it had
     * already read when Cancel landed. Written unconditionally, that would put
     * a frozen progress bar back over the Idle that Cancel set. Hence the
     * compare-and-set: Cancel wins however the two interleave.
     */
    internal fun report(version: String, copied: Long, total: Long?) {
        val fraction = total?.takeIf { it > 0 }?.let { (copied.toDouble() / it).toFloat().coerceIn(0f, 1f) }
        val shown = _state.value as? InstallState.Downloading ?: return
        if (shown.version != version) return
        // Whole percents only: a byte-level flow would recompose the frame
        // thousands of times for one visible change.
        if (shown.fraction?.percent() != fraction?.percent()) {
            _state.compareAndSet(shown, InstallState.Downloading(version, fraction))
        }
    }

    /**
     * Rethrows the cancellation when this job was cancelled, so a failure it
     * hits while unwinding — a socket that broke after Cancel — is not written
     * over whatever the person did next: Idle, or a new download.
     */
    private suspend fun stillWanted() = currentCoroutineContext().ensureActive()

    private companion object {
        const val TAG = "AppUpdate"
        const val PERCENT = 100

        fun Float.percent(): Int = (this * PERCENT).toInt()
    }
}

/** Where an in-app update has got to. */
sealed interface InstallState {
    val version: String?

    data object Idle : InstallState {
        override val version: String? = null
    }

    /** @param fraction 0..1, or null when the server sent no length. */
    data class Downloading(override val version: String, val fraction: Float?) : InstallState

    /** Signature check and staging — seconds, but long enough to need a word. */
    data class Preparing(override val version: String) : InstallState

    /** Staged; a restart installs it. */
    data class Ready(override val version: String) : InstallState

    /** Saved to Downloads and shown to the person to install. Nothing was installed. */
    data class Downloaded(override val version: String) : InstallState

    data class Failed(override val version: String, val reason: UpdateFailure.Reason) : InstallState
}
