package com.zillit.desktop.feature.scriptsupervision.ui.screens.characters

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.scriptsupervision.data.ContinuityCrud
import com.zillit.desktop.feature.scriptsupervision.domain.CharacterSection
import com.zillit.desktop.feature.scriptsupervision.domain.Characters
import com.zillit.desktop.feature.scriptsupervision.domain.Rec
import com.zillit.desktop.feature.scriptsupervision.domain.lookStatusTone
import com.zillit.desktop.feature.scriptsupervision.ui.Await
import com.zillit.desktop.feature.scriptsupervision.ui.CardGrid
import com.zillit.desktop.feature.scriptsupervision.ui.Field
import com.zillit.desktop.feature.scriptsupervision.ui.FormDialog
import com.zillit.desktop.feature.scriptsupervision.ui.LocalSs
import com.zillit.desktop.feature.scriptsupervision.ui.MutedText
import com.zillit.desktop.feature.scriptsupervision.ui.NumberChip
import com.zillit.desktop.feature.scriptsupervision.ui.SsPageHeader
import com.zillit.desktop.feature.scriptsupervision.ui.SsTag
import com.zillit.desktop.feature.scriptsupervision.ui.SsView
import com.zillit.desktop.feature.scriptsupervision.ui.body
import com.zillit.desktop.feature.scriptsupervision.ui.cast
import com.zillit.desktop.feature.scriptsupervision.ui.count
import com.zillit.desktop.feature.scriptsupervision.ui.nav
import com.zillit.desktop.feature.scriptsupervision.ui.rememberRows
import com.zillit.desktop.feature.scriptsupervision.ui.scenes
import com.zillit.desktop.feature.scriptsupervision.ui.t
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.height

/**
 * The scenes a character is in — the web's `CharacterScenesScreen`.
 *
 * Two kinds of scene meet here: the ones whose script NAMES the character, and
 * the ones linked by hand. Only the hand-made links can be changed, and
 * "Manage scenes" is where that happens — ticking a scene the script already
 * names does nothing it can undo, which is why the list says which is which.
 *
 * Each card also shows the looks that apply to that scene, so a reader can see
 * at a glance where a character has continuity recorded and where they do not.
 */
@Suppress("LongMethod") // Header, the looks join, the grid and the manage dialog.
@Composable
internal fun CharacterScenesScreen(id: String) {
    val ctx = LocalSs.current
    val navState = nav()
    val castState = cast()
    val sceneState = scenes()
    val character = castState.character(id)
    val name = character?.str("name").orEmpty().ifBlank { t("this_character") }
    var managing by remember { mutableStateOf(false) }
    val looks = rememberRows(id) { ContinuityCrud(ctx.api, "looks").list(mapOf("character_id" to id)) }
    val mine = Characters.scenesFor(character, sceneState.scenes)

    SsPageHeader(
        title = t("section_for", "section" to t(CharacterSection.Scenes.labelKey), "name" to name),
        sub = count("character_scenes_count", mine.size, name),
        actions = {
            ZillitButton(
                text = name,
                onClick = { navState.back() },
                variant = ButtonVariant.Secondary,
                leadingIcon = ZillitIcons.ArrowLeft,
            )
            ZillitButton(
                text = t("manage_scenes"),
                onClick = { ctx.whenPost { managing = true } },
                leadingIcon = ZillitIcons.Edit,
            )
        },
    )
    Await(looks) { lookRows ->
        // A look applies to a scene through its own `scene_ids`.
        val byScene = lookRows.flatMap { look -> look.strings("scene_ids").map { it to look } }
            .groupBy({ it.first }, { it.second })
        if (mine.isEmpty()) {
            ZillitEmptyState(
                title = t("no_character_scenes"),
                message = t("manage_scenes_help", "name" to name),
                action = {
                    ZillitButton(
                        text = t("manage_scenes"),
                        onClick = { ctx.whenPost { managing = true } },
                        variant = ButtonVariant.Secondary,
                    )
                },
            )
        } else {
            CardGrid(mine) { scene ->
                SceneCard(
                    scene = scene,
                    looks = byScene[scene.id].orEmpty(),
                    linkedByHand = scene.id in character?.strings("scene_ids").orEmpty().toSet(),
                    onOpen = { navState.go(SsView.CharacterScene(id, scene.id)) },
                )
            }
        }
    }

    if (managing) {
        ManageScenesDialog(
            character = character,
            named = Characters.namesOf(character),
            onClose = { managing = false },
            onSaved = { castState.reload() },
        )
    }
}

/**
 * One scene of a character's: its number, where it is, when it is, and the
 * looks recorded for it.
 */
@Composable
internal fun SceneCard(
    scene: Rec,
    looks: List<Rec> = emptyList(),
    linkedByHand: Boolean = false,
    onOpen: () -> Unit,
) {
    ZillitSectionCard(modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.Top,
        ) {
            NumberChip(caption = t("scene"), value = scene.str("scene_number"))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
                ZillitText(
                    text = scene.first("set_name", "scene_name", "location").ifBlank { t("unnamed") },
                    style = ZillitTheme.typography.titleSmall,
                    maxLines = 1,
                )
                SceneTags(scene)
                // The only link a reader can undo, so it is named.
                if (linkedByHand) SsTag(t("linked_character"), StatusTone.Progress)
                Field(t("description"), scene.str("scene_description"))
                if (looks.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                    ) {
                        looks.forEach { look ->
                            val status = look.str("status").ifBlank { "planned" }
                            SsTag(
                                label = listOf(t("dept_${look.str("department")}"), look.str("name"))
                                    .filter { it.isNotBlank() }
                                    .joinToString(" · "),
                                tone = lookStatusTone(status),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * A scene's story day, time of day and INT/EXT as tags — the web's
 * `SceneTags`.
 */
@Composable
internal fun SceneTags(scene: Rec) {
    val intExt = scene.str("int_ext").lowercase()
    val dayNight = scene.str("day_night")
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        scene.str("story_day").takeIf { it.isNotBlank() }?.let { SsTag(Characters.shortDay(it)) }
        if (dayNight.isNotBlank()) {
            // Night, dusk and evening all read as night on the web's palette:
            // what matters to continuity is whether it is dark.
            SsTag(dayNight, if (NIGHT.containsMatchIn(dayNight)) StatusTone.Neutral else StatusTone.Progress)
        }
        if (intExt.contains("int")) SsTag("int")
        if (intExt.contains("ext")) SsTag("ext")
    }
}

private val NIGHT = Regex("night|dusk|evening", RegexOption.IGNORE_CASE)

/**
 * Which scenes this character is in, by hand — the web's "Manage scenes".
 *
 * ⚠ It writes `scene_ids`, and ONLY the hand-made links live there. A scene
 * the script already names is ticked and cannot be unticked: unticking it
 * would write nothing, and the character would still be in that scene the
 * next time the list was read. The web says so in its intro line, and the
 * ticks for those scenes are disabled here for the same reason.
 */
@Composable
private fun ManageScenesDialog(character: Rec?, named: List<String>, onClose: () -> Unit, onSaved: () -> Unit) {
    val ctx = LocalSs.current
    val sceneState = scenes()
    var picked by remember(character) {
        mutableStateOf(character?.strings("scene_ids").orEmpty().toSet())
    }
    var saving by remember { mutableStateOf(false) }

    FormDialog(
        open = true,
        title = t("manage_scenes"),
        onDismiss = onClose,
        confirmLabel = t("save"),
        onConfirm = {
            saving = true
            ctx.scope.launch {
                val done = ctx.write {
                    ContinuityCrud(ctx.api, "characters")
                        .update(character?.id.orEmpty(), body("scene_ids" to picked.toList()))
                }
                saving = false
                if (done != null) {
                    onClose()
                    onSaved()
                }
            }
        },
        busy = saving,
        width = MANAGE_WIDTH,
    ) {
        MutedText(t("manage_scenes_intro"), maxLines = INTRO_LINES)
        ZillitScrollColumn(
            modifier = Modifier.fillMaxWidth().height(MANAGE_HEIGHT),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        ) {
            sceneState.scenes.forEach { scene ->
                ManageSceneRow(
                    scene = scene,
                    // Named by the script: already in, and not ours to remove.
                    byScript = scene.strings("characters").any { it in named },
                    picked = scene.id in picked,
                    onPick = { on -> picked = if (on) picked + scene.id else picked - scene.id },
                )
            }
        }
    }
}

private const val INTRO_LINES = 3
private val MANAGE_WIDTH = 640.dp
private val MANAGE_HEIGHT = 380.dp

/** One scene to tick, and whether the script already put the character in it. */
@Composable
private fun ManageSceneRow(scene: Rec, byScript: Boolean, picked: Boolean, onPick: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitCheckbox(
            checked = byScript || picked,
            onCheckedChange = onPick,
            enabled = !byScript,
        )
        ZillitText(
            text = "${t("scene")} ${scene.str("scene_number")} · " +
                scene.first("set_name", "scene_name", "location"),
            style = ZillitTheme.typography.bodySmall,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        if (byScript) SsTag(t("script"))
    }
}
