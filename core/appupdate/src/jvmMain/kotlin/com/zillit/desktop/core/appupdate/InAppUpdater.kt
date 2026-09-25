package com.zillit.desktop.core.appupdate

import com.zillit.desktop.core.common.ZillitLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
) {
    private val _state = MutableStateFlow<InstallState>(InstallState.Idle)
    val state: StateFlow<InstallState> = _state.asStateFlow()

    private var job: Job? = null
    private var prepared: Pair<String, PreparedUpdate>? = null

    /** True when [ref] can be installed here without leaving the app. */
    fun canInstall(ref: InstallerRef?): Boolean = ref != null && installer?.accepts(ref.url) == true

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
                val file = downloader.fetch(ref) { copied, total ->
                    val fraction = total?.takeIf { it > 0 }?.let { (copied.toDouble() / it).toFloat().coerceIn(0f, 1f) }
                    val shown = _state.value as? InstallState.Downloading
                    // Whole percents only: a byte-level flow would recompose
                    // the frame thousands of times for one visible change.
                    if (shown?.fraction?.percent() != fraction?.percent()) {
                        _state.value = InstallState.Downloading(version, fraction)
                    }
                }
                _state.value = InstallState.Preparing(version)
                val ready = withContext(Dispatchers.IO) { platform.prepare(file) }
                prepared = version to ready
                _state.value = InstallState.Ready(version)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: UpdateFailure) {
                ZillitLog.w(TAG) { "update $version stopped: ${failure.reason} — ${failure.message}" }
                _state.value = InstallState.Failed(version, failure.reason)
            } catch (@Suppress("TooGenericExceptionCaught") unexpected: Exception) {
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

    data class Failed(override val version: String, val reason: UpdateFailure.Reason) : InstallState
}
