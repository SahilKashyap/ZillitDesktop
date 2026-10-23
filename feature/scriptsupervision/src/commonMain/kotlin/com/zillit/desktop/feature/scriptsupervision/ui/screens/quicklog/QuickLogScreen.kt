package com.zillit.desktop.feature.scriptsupervision.ui.screens.quicklog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitSearchSelect
import com.zillit.desktop.feature.scriptsupervision.domain.Rec
import com.zillit.desktop.feature.scriptsupervision.domain.ScriptSupervision
import com.zillit.desktop.feature.scriptsupervision.ui.LocalSs
import com.zillit.desktop.feature.scriptsupervision.ui.MutedText
import com.zillit.desktop.feature.scriptsupervision.ui.SsCtx
import com.zillit.desktop.feature.scriptsupervision.ui.SsPageHeader
import com.zillit.desktop.feature.scriptsupervision.ui.scenes
import com.zillit.desktop.feature.scriptsupervision.ui.t
import kotlinx.coroutines.launch

/**
 * The quick log — the web's `QuickLogScreen`.
 *
 * The screen a script supervisor has open all day: pick the scene once, then
 * log take after take with one key. Everything else on the screen exists to
 * keep that loop short, which is why the form is only built once a scene has
 * been picked AND its last take has answered — the last take is what fills
 * the slate, the camera settings and the take number.
 *
 * Only the picked DAY's scenes are offered, as the web does: the side bar's
 * day is the working set, and picking another day drops a scene that is not
 * on it.
 *
 * What the web has here and this does not: when the production has a DEFAULT
 * TEMPLATE, the web's quick log hands over to `TemplateLogScreen` and logs
 * through that template instead. The template editor is not ported yet, so
 * this screen says so rather than silently logging the wrong shape.
 */
@Composable
internal fun QuickLogScreen() {
    val ctx = LocalSs.current
    val sceneState = scenes()
    var sceneId by remember { mutableStateOf("") }
    // null while the last take is still being asked for; the form waits for it
    // because the take number and the carried settings come out of it.
    var lastTake by remember { mutableStateOf<LastTake?>(null) }
    val templates = rememberDefaultTemplate()

    // The side bar's day changed under us and dropped the picked scene.
    val picked = sceneState.dayScenes.firstOrNull { it.id == sceneId }
    LaunchedEffect(sceneState.day) {
        if (sceneId.isNotBlank() && picked == null) {
            sceneId = ""
            lastTake = null
        }
    }

    SsPageHeader(title = t("quick_log"), sub = t("quick_log_subtitle"))

    when {
        templates.loading -> MutedText(t("loading"))
        // The production logs through a template, which is not ported yet.
        templates.defaultId != null -> ZillitEmptyState(
            title = t("templates"),
            message = t("not_ported_yet"),
        )

        else -> {
            ScenePicker(
                sceneId = sceneId,
                scenes = sceneState.dayScenes,
                onPick = { id ->
                    sceneId = id
                    lastTake = null
                    if (id.isNotBlank()) {
                        ctx.scope.launch { lastTake = ctx.lastTakeOf(id) }
                    }
                },
            )
            when {
                picked == null -> ZillitEmptyState(
                    title = t("select_scene_to_start"),
                    message = t("select_scene_to_start_hint"),
                )
                lastTake == null -> MutedText(t("loading"))
                else -> QuickLogForm(scene = picked, last = lastTake!!)
            }
        }
    }
}

/**
 * The scene's last take, and whether the service answered at all.
 *
 * [known] matters: a scene whose last take could NOT be read must not start
 * at take 1, because that would overwrite the numbering of takes already
 * logged. The web leaves the take number empty in that case and the form asks
 * for it.
 */
internal data class LastTake(val known: Boolean, val take: Rec?)

/**
 * `GET /quick-log/last-take/{projectId}/{sceneId}` — the scene's highest take.
 *
 * A failure is toasted in the SERVICE's own words and answers
 * `known = false`, which is what stops the form starting at take 1 over
 * takes that are already logged.
 */
private suspend fun SsCtx.lastTakeOf(sceneId: String): LastTake {
    val project = api.project() ?: return LastTake(known = false, take = null)
    return when (val answer = api.get("/quick-log/last-take/$project/$sceneId")) {
        is ZillitResult.Success -> LastTake(known = true, take = answer.data.rec)
        is ZillitResult.Failure -> {
            toast(answer.error.localised(), false)
            LastTake(known = false, take = null)
        }
    }
}

@Composable
private fun ScenePicker(sceneId: String, scenes: List<Rec>, onPick: (String) -> Unit) {
    val options = scenes.map { it.id to ScriptSupervision.sceneLabel(it) }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitSearchSelect(
            value = options.firstOrNull { it.first == sceneId },
            options = options,
            onSelect = { onPick(it.first) },
            label = { it.second },
            placeholder = t("select_a_scene"),
            onClear = { onPick("") }.takeIf { sceneId.isNotBlank() },
            modifier = Modifier.width(PICKER_WIDTH),
        )
        MutedText(t(if (scenes.size == 1) "record_count_one" else "record_count_other", "n" to scenes.size))
    }
}

/** Whether the production logs through a template, and whether we know yet. */
internal class DefaultTemplate(val loading: Boolean, val defaultId: String?)

/**
 * The production's default template, if it has one.
 *
 * Asked once, before anything else is drawn: with a default template set, the
 * whole screen is a different one on the web, so drawing the plain form first
 * and replacing it would be a flash of the wrong thing.
 */
@Composable
private fun rememberDefaultTemplate(): DefaultTemplate {
    val ctx = LocalSs.current
    var state by remember(ctx) { mutableStateOf(DefaultTemplate(loading = true, defaultId = null)) }
    LaunchedEffect(ctx) {
        val project = ctx.api.project()
        val answer = project?.let { ctx.api.get("/continuity/templates/$it") }
        val rows = (answer as? ZillitResult.Success)?.data?.rows.orEmpty()
        state = DefaultTemplate(
            loading = false,
            defaultId = rows.firstOrNull { it.bool("is_default") }?.id,
        )
    }
    return state
}

private val PICKER_WIDTH = 360.dp
