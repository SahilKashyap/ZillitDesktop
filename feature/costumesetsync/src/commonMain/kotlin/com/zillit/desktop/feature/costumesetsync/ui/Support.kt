package com.zillit.desktop.feature.costumesetsync.ui

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
import com.zillit.desktop.core.localization.Labels
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.localization.localisedMessage
import com.zillit.desktop.core.permissions.RightsKind
import com.zillit.desktop.core.socket.SocketMessage
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.costumesetsync.data.Answer
import com.zillit.desktop.feature.costumesetsync.data.SyncOnsetApi
import com.zillit.desktop.feature.costumesetsync.domain.NoHost
import com.zillit.desktop.feature.costumesetsync.domain.Rec
import com.zillit.desktop.feature.costumesetsync.domain.SyncHost
import com.zillit.desktop.feature.costumesetsync.domain.SyncProject
import com.zillit.desktop.feature.costumesetsync.domain.SyncViewer
import com.zillit.desktop.feature.costumesetsync.domain.humanize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Everything a Costumes & Set Sync screen needs, handed down once.
 *
 * The web's `useSyncOnset()` context: the api, the rights gate, the service's
 * enum feed (`/meta`), the production record, navigation, toasts, and the
 * realtime frames. Screens read it with [LocalSync].
 */
@Suppress("LongParameterList")
@Stable
class SyncCtx(
    val api: SyncOnsetApi,
    val viewer: SyncViewer,
    val project: SyncProject,
    /** `GET /meta`: every enum the service knows — `costume_statuses`, `cleaning_types`, … */
    val meta: Rec?,
    val nav: SyncNav,
    val scope: CoroutineScope,
    val frames: Flow<SocketMessage> = emptyFlow(),
    val projectId: String? = null,
    val currentUserId: String = "",
    /** Toasts [text] (already localised). */
    val toast: (text: String, ok: Boolean) -> Unit = { _, _ -> },
    /** Opens the app's "ask an admin for this right" flow. */
    val askRights: (RightsKind) -> Unit = {},
    /** A write succeeded: the nav counts should catch up at once (the web's `csync:changed`). */
    val changed: () -> Unit = {},
    val now: () -> Long = { 0L },
    /** File chooser, storage upload, browser and save dialog — the things only the host app can do. */
    val host: SyncHost = NoHost,
) {
    val canPost: Boolean get() = viewer.canPost
    val canDownload: Boolean get() = viewer.canDownload
    val isFinance: Boolean get() = project.isFinance
    val currency: String get() = project.currency

    /** One enum list from `/meta` (`costume_statuses`), empty until it lands. */
    fun metaList(key: String): List<String> = meta?.strings(key).orEmpty()

    /**
     * A write that needs download rights (Print / PDF, CSV, opening an
     * attachment): runs [block], or opens "request download access" without them.
     */
    fun whenDownload(block: () -> Unit) {
        if (canDownload) block() else askRights(RightsKind.Download)
    }

    /**
     * Runs a WRITE and toasts its outcome from the server's own `message` key —
     * the copy is the backend's, never ours. Returns whether it succeeded.
     * A failure toasts the error; a success with no message stays silent.
     */
    suspend fun write(call: suspend () -> ZillitResult<Answer>): Answer? = when (val result = call()) {
        is ZillitResult.Success -> {
            result.data.message?.takeIf { it.isNotBlank() }?.let { toast(it.localisedMessage(), true) }
            changed()
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
}

val LocalSync = compositionLocalOf<SyncCtx> { error("SyncCtx is provided by SyncOnsetShell") }

/** The service's own words for an enum value (`DRY_CLEANING` → "Dry Cleaning"): the label set first, else humanised. */
fun tEnum(value: String?): String {
    if (value.isNullOrBlank()) return ""
    val dictionary = Labels.current
    // A SELF-MAPPING entry is not a translation: the backend label set carries
    // keys like PRODUCTION whose value is "PRODUCTION", which would print shouty.
    val hit = dictionary.exact(value) ?: dictionary.exact(value.lowercase())
    // A few shared labels are sentences ("Completed."): a status is a word.
    return if (hit != null && hit != value) hit.trimEnd('.') else humanize(value)
}

/** The screen copy for web key [key] (`csync_nav_costumes`): desktop strings are `desktop_<key>`. */
fun t(key: String): String = str("desktop_$key")

/** [t] with the web's `{name}` placeholders filled: `t("csync_page_of", "page" to 1, "pages" to 3)`. */
fun t(key: String, vararg values: Pair<String, Any?>): String =
    values.fold(t(key)) { text, (name, value) -> text.replace("{$name}", value?.toString().orEmpty()) }

/** Where a screen's data stands. */
sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Ready<T>(val value: T) : Load<T>
    data class Failed(val message: String) : Load<Nothing>
}

/**
 * One fetch, owned by a screen. [reload] with `silent = true` is what a
 * socket frame or a finished write calls: it keeps what is on screen instead
 * of repainting the skeleton, so a colleague's save never flashes the list.
 * Only the newest request's answer is kept (a slow old one can't overwrite a
 * fresh one).
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
            if (mine != seq) return@launch
            when (answer) {
                is ZillitResult.Success -> state = Load.Ready(answer.data)
                // A failed background refresh keeps the figures already on screen.
                is ZillitResult.Failure -> if (
                    !silent || state !is Load.Ready
                ) state = Load.Failed(answer.error.localised())
            }
        }
    }

    val value: T? get() = (state as? Load.Ready)?.value
}

/**
 * Fetches when the screen opens and whenever [keys] change — never before
 * [SyncViewer.canCall] (the screens sit behind the gate, so by here it holds).
 */
@Composable
fun <T> rememberResource(vararg keys: Any?, loader: suspend SyncCtx.() -> ZillitResult<T>): Resource<T> {
    val ctx = LocalSync.current
    val scope = rememberCoroutineScope()
    val latest = rememberUpdatedState(loader)
    val resource = remember(*keys) { Resource(scope) { latest.value(ctx) } }
    LaunchedEffect(resource) { resource.reload() }
    return resource
}

/** A list fetch: the rows of an `items`/bare-array answer. */
@Composable
fun rememberRows(vararg keys: Any?, load: suspend SyncCtx.() -> ZillitResult<Answer>): Resource<List<Rec>> =
    rememberResource(*keys) { load().mapRows() }

fun ZillitResult<Answer>.mapRows(): ZillitResult<List<Rec>> = when (this) {
    is ZillitResult.Success -> ZillitResult.Success(data.rows)
    is ZillitResult.Failure -> this
}

/**
 * Live refresh for a screen — the web's `SyncOnsetSocketRefresh`. Renders
 * nothing. A frame for another production is ignored; a burst (300 ms) is one
 * refetch, and the refetch must be silent.
 *
 * [events] are the `costume_set_sync:<entity>:<verb>` names (see [SyncEvents]);
 * [predicate] narrows a detail screen to its own record (the frame's
 * `entity_id`, and `data` for related ids).
 */
@Composable
fun SocketRefresh(events: Set<String>, predicate: (Rec) -> Boolean = { true }, onRefresh: () -> Unit) {
    val ctx = LocalSync.current
    val latest = rememberUpdatedState(onRefresh)
    val narrow = rememberUpdatedState(predicate)
    LaunchedEffect(events, ctx.projectId) {
        var pending: kotlinx.coroutines.Job? = null
        ctx.frames
            .filter { it.event.value in events }
            .collect { message ->
                val frame = (message.payload as? JsonObject)?.let(::Rec) ?: return@collect
                if (frame.str("project_id") != ctx.projectId.orEmpty()) return@collect
                if (!narrow.value(frame)) return@collect
                pending?.cancel()
                pending = launch {
                    delay(SOCKET_DEBOUNCE_MS)
                    latest.value()
                }
            }
    }
}

private const val SOCKET_DEBOUNCE_MS = 300L

/** The text of a JSON primitive, for the odd place a frame's `data` is read raw. */
internal fun JsonObject.text(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
