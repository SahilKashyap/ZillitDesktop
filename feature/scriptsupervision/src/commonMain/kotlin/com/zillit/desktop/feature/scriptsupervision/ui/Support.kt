package com.zillit.desktop.feature.scriptsupervision.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.localization.localisedMessage
import com.zillit.desktop.core.permissions.RightsKind
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.scriptsupervision.data.Answer
import com.zillit.desktop.feature.scriptsupervision.data.ScriptSupervisionApi
import com.zillit.desktop.feature.scriptsupervision.domain.Rec
import com.zillit.desktop.feature.scriptsupervision.domain.ScriptSupervisionViewer
import com.zillit.desktop.feature.scriptsupervision.domain.SsHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Everything a Continuity Script Supervision screen needs, handed down once —
 * the web's `useStrings()` plus its module context.
 *
 * Screens read it with [LocalSs]. There are no sockets in this module (the
 * web's wiki: "Sockets: none yet"), so a screen refreshes after its own
 * writes and nothing else pushes at it.
 */
@Stable
class SsCtx(
    val api: ScriptSupervisionApi,
    val viewer: ScriptSupervisionViewer,
    val scope: CoroutineScope,
    val projectId: String? = null,
    /** Toasts [text] (already localised). */
    val toast: (text: String, ok: Boolean) -> Unit = { _, _ -> },
    /** Opens the app's "ask an admin for this right" flow. */
    val askRights: (RightsKind) -> Unit = {},
    /** Opens the Film Tools window (the web's Tools button goes back to the grid). */
    val openTools: () -> Unit = {},
    /** The few things the JSON client cannot do — fetching a signed photo, opening a PDF. */
    val host: SsHost = SsHost.None,
) {
    val canPost: Boolean get() = viewer.canPost
    val canDownload: Boolean get() = viewer.canDownload

    /**
     * Runs a WRITE and toasts its outcome from the server's own `message` key.
     *
     * The copy is the backend's, never ours: the web is explicit that this
     * module adds no success or error strings of its own, because the
     * service's `public/<lang>/messages.json` already carries them. Returns
     * whether it succeeded.
     */
    suspend fun write(call: suspend () -> ZillitResult<Answer>): Answer? = when (val result = call()) {
        is ZillitResult.Success -> {
            result.data.message?.takeIf { it.isNotBlank() }?.let { toast(it.localisedMessage(), true) }
            result.data
        }
        is ZillitResult.Failure -> {
            toast(result.error.localised(), false)
            null
        }
    }

    /** [write] from a click handler. */
    fun launchWrite(call: suspend () -> ZillitResult<Answer>, onDone: (Answer) -> Unit = {}) {
        scope.launch { write(call)?.let(onDone) }
    }

    /** A press that needs posting rights: runs [block], or asks an admin for them. */
    fun whenPost(block: () -> Unit) {
        if (canPost) block() else askRights(RightsKind.Post)
    }
}

val LocalSs = compositionLocalOf<SsCtx> { error("No SsCtx") }

/**
 * The module's own text (the web's en.js `script_supervision_…` keys).
 *
 * Two namespaces meet in the web's dictionary and the collision is a real bug
 * it hit: a screen key named like the tool's own `script_supervision_label`
 * renamed the Film Tools tile to "Label". Nothing like that can happen here —
 * screen text is a `desktop_ss_…` key in this app's catalogue, and the tool's
 * name comes from the backend's label feed — but the field labels keep the
 * web's own corrected names so the two read side by side.
 */
fun t(key: String): String = SsText[key]?.let { str(it) } ?: key

/** [t] with the web's `{name}` placeholders filled. */
fun t(key: String, vararg values: Pair<String, Any?>): String =
    values.fold(t(key)) { text, (name, value) -> text.replace("{$name}", value?.toString().orEmpty()) }

/** Where a screen's data stands. */
sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Ready<T>(val value: T) : Load<T>
    data class Failed(val message: String) : Load<Nothing>
}

/**
 * One fetch, owned by a screen.
 *
 * [reload] with `silent = true` is what a finished write calls: it keeps what
 * is on screen instead of blanking it, and a failed silent refresh leaves the
 * rows already there.
 */
@Stable
class Resource<T>(private val scope: CoroutineScope, private val loader: suspend () -> ZillitResult<T>) {
    var state: Load<T> by mutableStateOf(Load.Loading)
        private set

    private var seq = 0

    fun reload(silent: Boolean = false) {
        val mine = ++seq
        if (!silent || state !is Load.Ready) state = Load.Loading
        scope.launch {
            val answer = loader()
            // A later load has started: this answer is stale.
            if (mine != seq) return@launch
            when (answer) {
                is ZillitResult.Success -> state = Load.Ready(answer.data)
                is ZillitResult.Failure -> if (!silent || state !is Load.Ready) {
                    state = Load.Failed(answer.error.localised())
                }
            }
        }
    }

    val value: T? get() = (state as? Load.Ready)?.value
}

/** Fetches when the screen opens and whenever [keys] change. */
@Composable
fun <T> rememberResource(vararg keys: Any?, loader: suspend SsCtx.() -> ZillitResult<T>): Resource<T> {
    val ctx = LocalSs.current
    val scope = rememberCoroutineScope()
    val latest = rememberUpdatedState(loader)
    val resource = remember(*keys) { Resource(scope) { latest.value(ctx) } }
    LaunchedEffect(resource) { resource.reload() }
    return resource
}

/** A list fetch: the rows of an `items`/bare-array answer. */
@Composable
fun rememberRows(vararg keys: Any?, load: suspend SsCtx.() -> ZillitResult<Answer>): Resource<List<Rec>> =
    rememberResource(*keys) { load().mapRows() }

fun ZillitResult<Answer>.mapRows(): ZillitResult<List<Rec>> = when (this) {
    is ZillitResult.Success -> ZillitResult.Success(data.rows)
    is ZillitResult.Failure -> this
}
