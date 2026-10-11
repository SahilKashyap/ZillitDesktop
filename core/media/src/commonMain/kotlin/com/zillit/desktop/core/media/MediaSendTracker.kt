package com.zillit.desktop.core.media

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Where one outgoing file stands, as the Uploads tray lists it. */
enum class SendStatus { Running, Failed, Done }

/**
 * One file on its way out of this machine — a chat attachment or a board
 * post — wherever it was sent from.
 *
 * [projectName] and [destination] say where it is going, because the tray is
 * app-wide: a video sent in one production keeps uploading, and stays in the
 * list, after the user has switched to another.
 */
data class TrackedSend(
    val id: String,
    val projectId: String,
    val projectName: String,
    /** The person, group or board it is going to — "Aisha", "Camera unit". */
    val destination: String,
    val fileName: String,
    /** A small JPEG of the picture or the video's first frame, when there is one. */
    val preview: ByteArray? = null,
    /** [UploadProgress]-encoded: preparing, compressing n%, uploading n%. */
    val progress: Int = UploadProgress.PREPARING,
    val status: SendStatus = SendStatus.Running,
) {
    override fun toString(): String = "TrackedSend(id=$id, project=$projectId, status=$status, progress=$progress)"

    /** Identity, not content: the preview's bytes must not decide equality. */
    override fun equals(other: Any?): Boolean =
        other is TrackedSend && other.id == id && other.progress == progress && other.status == status &&
            other.preview === preview && other.destination == destination && other.fileName == fileName

    override fun hashCode(): Int = id.hashCode()
}

/**
 * Every outgoing file in the app, for the one place that shows them all.
 *
 * The senders (chat, the boards) own the work and report to this; the tray
 * reads [sends] and hands Cancel and Retry back through the controls each
 * sender registered. App-lived: it outlasts project switches on purpose.
 */
class MediaSendTracker {

    private val state = MutableStateFlow<List<TrackedSend>>(emptyList())

    /** Newest first. */
    val sends: StateFlow<List<TrackedSend>> = state.asStateFlow()

    private class Controls(val cancel: () -> Unit, val retry: (() -> Unit)?)

    private val controls = MutableStateFlow<Map<String, Controls>>(emptyMap())

    /** A send starting (or starting again): listed, with what Cancel and Retry do. */
    fun begin(send: TrackedSend, cancel: () -> Unit, retry: (() -> Unit)? = null) {
        controls.update { it + (send.id to Controls(cancel, retry)) }
        state.update { list -> listOf(send.copy(status = SendStatus.Running)) + list.filterNot { it.id == send.id } }
    }

    fun progress(id: String, progress: Int) = change(id) { it.copy(progress = progress, status = SendStatus.Running) }

    fun preview(id: String, preview: ByteArray) = change(id) { it.copy(preview = preview) }

    fun finished(id: String) = change(id) { it.copy(status = SendStatus.Done) }

    fun failed(id: String) = change(id) { it.copy(status = SendStatus.Failed) }

    /** Stops the send and takes it off the list. */
    fun cancel(id: String) {
        val control = controls.value[id]
        controls.update { it - id }
        control?.cancel?.invoke()
        dismiss(id)
    }

    /** Sends a failed file again, through whichever sender started it. */
    fun retry(id: String) {
        controls.value[id]?.retry?.invoke()
    }

    fun canRetry(id: String): Boolean = controls.value[id]?.retry != null

    /** Takes a finished or failed row off the list; the send itself is untouched. */
    fun dismiss(id: String) {
        state.update { list -> list.filterNot { it.id == id } }
        controls.update { it - id }
    }

    /** Every row that is done. */
    fun clearFinished() {
        val done = state.value.filter { it.status == SendStatus.Done }.map { it.id }.toSet()
        state.update { list -> list.filterNot { it.id in done } }
        controls.update { it - done }
    }

    private fun change(id: String, transform: (TrackedSend) -> TrackedSend) {
        state.update { list -> list.map { if (it.id == id) transform(it) else it } }
    }
}
