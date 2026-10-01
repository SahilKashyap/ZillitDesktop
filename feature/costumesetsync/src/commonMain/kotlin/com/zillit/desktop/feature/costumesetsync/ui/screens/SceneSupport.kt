package com.zillit.desktop.feature.costumesetsync.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitProgressBar
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.localization.localisedMessage
import com.zillit.desktop.feature.costumesetsync.data.Answer
import com.zillit.desktop.feature.costumesetsync.data.ApiSceneWrites
import com.zillit.desktop.feature.costumesetsync.data.SceneWrites
import com.zillit.desktop.feature.costumesetsync.domain.CastProblem
import com.zillit.desktop.feature.costumesetsync.domain.Rec
import com.zillit.desktop.feature.costumesetsync.ui.FormDialog
import com.zillit.desktop.feature.costumesetsync.ui.LocalSync
import com.zillit.desktop.feature.costumesetsync.ui.MutedText
import com.zillit.desktop.feature.costumesetsync.ui.SyncCtx
import com.zillit.desktop.feature.costumesetsync.ui.mapRows
import com.zillit.desktop.feature.costumesetsync.ui.t
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Scene-breakdown plumbing shared by the Scenes, Scene detail and upload screens:
 * toasting a save's outcome, a confirm dialog, the project's documents list and
 * the costume-cue reader.
 */

/**
 * Toasts what a save reported — the server's own `message` on success, the error
 * on failure — and tells the shell the nav counts should catch up. Nothing for
 * `null` (a save where nothing changed writes nothing, so there is no envelope).
 */
internal fun SyncCtx.report(result: ZillitResult<Answer>?) {
    when (result) {
        is ZillitResult.Success -> {
            result.data.message?.takeIf { it.isNotBlank() }?.let { toast(it.localisedMessage(), true) }
            changed()
        }
        is ZillitResult.Failure -> toast(result.error.localised(), false)
        null -> Unit
    }
}

/** The words for a bad cast number. */
internal fun castProblemText(problem: CastProblem): String =
    t(if (problem == CastProblem.NotWhole) "csync_cast_number_invalid" else "csync_cast_number_too_big")

/** "1 scene" / "5 scenes": the web's `_one` key for exactly one. */
internal fun plural(key: String, n: Int, vararg values: Pair<String, Any?>): String =
    t(if (n == 1) "${key}_one" else key, *values)

/** Like [plural] for the keys whose many-form is spelled `_many` (drafts, discarded scenes, schedule counts). */
internal fun pluralMany(key: String, n: Int, vararg values: Pair<String, Any?>): String =
    t(if (n == 1) "${key}_one" else "${key}_many", *values)

/** A question the user must answer before a destructive step. */
internal class Confirm(
    val title: String,
    val body: String,
    val okLabel: String,
    val danger: Boolean = false,
    val onOk: () -> Unit,
)

/** Renders [confirm] when set; [onDone] clears it. */
@Composable
internal fun ConfirmDialog(confirm: Confirm?, onDone: () -> Unit) {
    var last by remember { mutableStateOf<Confirm?>(null) }
    if (confirm != null) last = confirm
    val shown = last ?: return
    FormDialog(
        open = confirm != null,
        title = shown.title,
        onDismiss = onDone,
        confirmLabel = shown.okLabel,
        onConfirm = {
            onDone()
            shown.onOk()
        },
        danger = shown.danger,
        width = CONFIRM_WIDTH,
    ) { ZillitText(shown.body) }
}

private val CONFIRM_WIDTH = 480.dp

// -- documents ---------------------------------------------------------------------

/**
 * The documents of one kind (`GET /documents?kind=`), fetched while [enabled] — the
 * web's `useProjectDocuments`. Listing is read-only; nothing imports from it.
 */
@Stable
internal class ProjectDocuments(private val ctx: SyncCtx, private val kind: String) {
    var docs by mutableStateOf<List<Rec>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set

    fun reload() {
        loading = true
        ctx.scope.launch {
            val answer = ctx.api.get("/documents", mapOf("kind" to kind)).mapRows()
            docs = (answer as? ZillitResult.Success)?.data.orEmpty()
            loading = false
        }
    }
}

@Composable
internal fun rememberProjectDocuments(kind: String, enabled: Boolean = true): ProjectDocuments {
    val ctx = LocalSync.current
    val documents = remember(kind, ctx) { ProjectDocuments(ctx, kind) }
    LaunchedEffect(documents, enabled) { if (enabled) documents.reload() }
    return documents
}

// -- cue extraction ------------------------------------------------------------------

/** Where a cue-reading run stands — what the web's `CueProgress` draws. */
data class CueProgress(
    val done: Int = 0,
    val total: Int = 0,
    val cues: Int = 0,
    val running: Boolean = false,
    val failure: Boolean = false,
    val engine: String? = null,
    val model: String? = null,
)

/** How many scenes one extract-cues call reads, as the reference batches them. */
private const val CUE_CHUNK = 6

/** The engine the project reads cues with: the service's choice, else AI when it is switched on. */
internal fun cueEngineOf(meta: Rec?): String = meta?.str("cue_engine")?.ifEmpty { null } ?: if (meta?.bool("ai_enabled") == true) "ai" else "rules"

/** "Built-in reader" or "AI · <model>", for the checkbox and the progress line. */
internal fun engineLabel(meta: Rec?): String =
    if (cueEngineOf(meta) == "ai") t("csync_engine_ai", "model" to (meta?.str("ai_model")?.ifEmpty { null } ?: "model")) else t("csync_engine_rules")

/**
 * Reads costume cues out of the script text of a list of scenes, a few at a time,
 * reporting progress as it goes. A failed batch stops the run and keeps what was
 * already read; [run] answers null when every batch went through, else the error.
 */
@Stable
internal class CueExtraction(private val ctx: SyncCtx) {
    var progress by mutableStateOf(CueProgress())
        private set

    suspend fun run(sceneIds: List<String>, engine: String): ZillitError? {
        val total = sceneIds.size
        var state = CueProgress(total = total, running = true)
        progress = state
        for (chunk in sceneIds.chunked(CUE_CHUNK)) {
            val body = buildJsonObject {
                put("scene_ids", JsonArray(chunk.map { JsonPrimitive(it) }))
                put("engine", engine)
            }
            when (val res = ctx.api.post("/scenes/extract-cues", body)) {
                is ZillitResult.Failure -> {
                    progress = state.copy(running = false, failure = true)
                    return res.error
                }
                is ZillitResult.Success -> {
                    val data = res.data.rec
                    state = state.copy(
                        done = state.done + chunk.size,
                        cues = state.cues + (data?.int("cues_created") ?: 0),
                        engine = data?.str("engine")?.ifEmpty { null } ?: state.engine,
                        model = data?.str("model")?.ifEmpty { null } ?: state.model,
                    )
                    progress = state
                }
            }
        }
        progress = state.copy(running = false)
        return null
    }
}

/** "Reading scenes… 3/12 scenes · 8 cues · built-in reader" over a progress bar. */
@Composable
internal fun CueProgressView(progress: CueProgress) {
    if (progress.total == 0 && !progress.failure) return
    val engine = if (progress.engine == "rules") t("csync_engine_rules").lowercase() else progress.model.orEmpty()
    val fraction = if (progress.total > 0) progress.done.toFloat() / progress.total else 0f
    val head = when {
        progress.running -> t("csync_cues_reading")
        progress.failure -> t("csync_cues_stopped")
        else -> t("csync_done")
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            ZillitText(head)
            MutedText(
                t("csync_cues_progress", "done" to progress.done, "total" to progress.total, "cues" to progress.cues) +
                    if (engine.isNotEmpty()) " · $engine" else "",
            )
        }
        ZillitProgressBar(fraction, Modifier.fillMaxWidth())
    }
}

/** The scene writes over this screen's client — one per tool open. */
@Composable
internal fun rememberSceneWrites(): SceneWrites {
    val ctx = LocalSync.current
    return remember(ctx.api) { ApiSceneWrites(ctx.api) }
}
