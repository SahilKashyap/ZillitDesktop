package com.zillit.desktop.feature.scriptsupervision.ui.screens.script

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitSectionLabel
import com.zillit.desktop.core.designsystem.component.ZillitSwitch
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.scriptsupervision.domain.Breakdown
import com.zillit.desktop.feature.scriptsupervision.domain.Rec
import com.zillit.desktop.feature.scriptsupervision.domain.Script
import com.zillit.desktop.feature.scriptsupervision.domain.TaggedElement
import com.zillit.desktop.feature.scriptsupervision.ui.Await
import com.zillit.desktop.feature.scriptsupervision.ui.LocalSs
import com.zillit.desktop.feature.scriptsupervision.ui.MutedText
import com.zillit.desktop.feature.scriptsupervision.ui.SsCtx
import com.zillit.desktop.feature.scriptsupervision.ui.SsPageHeader
import com.zillit.desktop.feature.scriptsupervision.ui.SsTag
import com.zillit.desktop.feature.scriptsupervision.ui.SsView
import com.zillit.desktop.feature.scriptsupervision.ui.TextInput
import com.zillit.desktop.feature.scriptsupervision.ui.body
import com.zillit.desktop.feature.scriptsupervision.ui.nav
import com.zillit.desktop.feature.scriptsupervision.ui.rememberResource
import com.zillit.desktop.feature.scriptsupervision.ui.scenes
import com.zillit.desktop.feature.scriptsupervision.ui.t
import kotlinx.coroutines.launch

/**
 * One scene's breakdown — the web's `BreakdownScene`.
 *
 * `GET /breakdown/sheet/{projectId}/{sceneId}` answers the scene, its
 * categories and every tag in one call, so the whole screen is one fetch.
 * The script is on the left and the sheet — synopsis and elements by
 * category — on the right, as the web lays it out.
 *
 * ## What the desktop does differently, and why
 *
 * The web tags by SELECTING text in the script and choosing a category from a
 * popover at the selection. That needs character offsets out of a live text
 * selection inside a scrolling pane, which Compose does not give for free, so
 * tagging here goes through the Add-tag dialog — which the web also has, and
 * which reaches the same endpoint with the same body. [Breakdown.locate]
 * finds the text's position in the script exactly as the web's does, so a tag
 * made here points where a tag made there would.
 *
 * The script pane is therefore read-only, and shows which lines carry tags
 * rather than letting you drag across them. Tagging by selection is the piece
 * left for whoever wants it.
 */
@Suppress("LongMethod") // The header, the script, the sheet and two dialogs.
@Composable
internal fun BreakdownSceneScreen(sceneId: String) {
    val ctx = LocalSs.current
    val navState = nav()
    val sceneState = scenes()
    val project = ctx.api.project().orEmpty()
    val sheet = rememberResource(sceneId, project) {
        api.get("/breakdown/sheet/$project/$sceneId")
    }
    var adding by remember(sceneId) { mutableStateOf(false) }
    var reviewing by remember(sceneId) { mutableStateOf(false) }

    val ordered = sceneState.scenes
    val at = ordered.indexOfFirst { it.id == sceneId }

    Await(sheet) { answer ->
        val record = answer.rec
        val scene = record?.rec("scene") ?: sceneState.scene(sceneId)
        val categories = record?.recs("categories").orEmpty()
        // Every tag of every category, and the suggestions among them.
        val allTags = categories.flatMap { it.recs("tags") }
        val suggested = allTags.filter { it.str("status") == Breakdown.SUGGESTED }
        val heading = "${t("scene")} ${scene?.str("scene_number").orEmpty()}"

        SsPageHeader(
            title = heading,
            sub = scene?.str("scene_name").orEmpty(),
            actions = {
                ZillitButton(
                    text = t("all_scenes"),
                    onClick = { navState.back() },
                    variant = ButtonVariant.Secondary,
                    leadingIcon = ZillitIcons.ArrowLeft,
                )
                ZillitIconButton(
                    icon = ZillitIcons.ArrowLeft,
                    contentDescription = t("previous_scene"),
                    onClick = { ordered.getOrNull(at - 1)?.let { navState.swap(SsView.BreakdownScene(it.id)) } },
                    enabled = at > 0,
                )
                ZillitIconButton(
                    icon = ZillitIcons.ArrowRight,
                    contentDescription = t("next_scene"),
                    onClick = { ordered.getOrNull(at + 1)?.let { navState.swap(SsView.BreakdownScene(it.id)) } },
                    enabled = at >= 0 && at < ordered.size - 1,
                )
                // Only when there is something to review: the button carries
                // the count, as the web's does.
                if (suggested.isNotEmpty()) {
                    ZillitButton(
                        text = "${t("review_suggestions")} ${suggested.size}",
                        onClick = { reviewing = true },
                        variant = ButtonVariant.Secondary,
                    )
                }
                ZillitButton(
                    text = t("add_tag"),
                    onClick = { ctx.whenPost { adding = true } },
                    leadingIcon = ZillitIcons.Add,
                )
            },
        )

        ScriptPane(scene, allTags)
        BreakdownSheetPane(
            scene = scene,
            categories = categories,
            onReload = { sheet.reload(silent = true) },
        )

        if (adding) {
            AddTagDialog(
                sceneId = sceneId,
                categories = categories.mapNotNull { it.rec("category") },
                scriptText = scene?.strings("script_text").orEmpty(),
                onClose = { adding = false },
                onSaved = { sheet.reload(silent = true) },
            )
        }
        if (reviewing) {
            ReviewSuggestionsDialog(
                sceneId = sceneId,
                suggestions = suggested,
                onClose = { reviewing = false },
                onSaved = { sheet.reload(silent = true) },
            )
        }
    }
}

/**
 * The scene's script, with the lines that carry tags marked.
 *
 * Read-only: see the screen's own note on why tagging is a dialog here. The
 * marks still earn their place — they are how a reader sees which parts of
 * the scene have been broken down and which have not.
 */
@Composable
private fun ScriptPane(scene: Rec?, tags: List<Rec>) {
    val lines = scene?.strings("script_text").orEmpty()
    ZillitSectionLabel(t("script"))
    ZillitSectionCard(modifier = Modifier.fillMaxWidth()) {
        if (lines.isEmpty()) {
            MutedText(t("no_script_text"))
            return@ZillitSectionCard
        }
        val tagged = tags.map { it.int("line_index") }.toSet()
        ZillitScrollColumn(modifier = Modifier.fillMaxWidth().height(SCRIPT_HEIGHT)) {
            lines.forEachIndexed { index, line ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                ) {
                    // A dot rather than a highlight: the web colours the
                    // tagged span itself, which needs the character range,
                    // and this says the same thing at line level.
                    MutedText(if (index in tagged) "•" else " ")
                    ZillitText(
                        text = line,
                        style = ZillitTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/**
 * The sheet: the scene's heading, its synopsis, and its elements by category.
 *
 * Empty categories are hidden behind a switch, as the web hides them — a
 * production has a couple of dozen categories and a scene uses a handful.
 */
@Suppress("LongMethod") // The heading card, the synopsis and the category list.
@Composable
private fun BreakdownSheetPane(scene: Rec?, categories: List<Rec>, onReload: () -> Unit) {
    val ctx = LocalSs.current
    var showEmpty by remember { mutableStateOf(false) }
    var synopsis by remember(scene?.id) { mutableStateOf(scene?.str("scene_description").orEmpty()) }
    val total = categories.sumOf { it.recs("tags").size }

    ZillitSectionLabel(t("elements"))
    ZillitSectionCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitText(
                text = listOf(
                    scene?.str("int_ext").orEmpty(),
                    scene?.first("set_name", "scene_name").orEmpty(),
                    scene?.str("day_night").orEmpty(),
                ).filter { it.isNotBlank() }.joinToString(" — "),
                style = ZillitTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            MutedText(
                Script.formatEighths(scene?.int("page_count_eighths") ?: 0).ifBlank { "—" } + " " + t("pg"),
            )
        }
        TextInput(
            value = synopsis,
            onChange = { synopsis = it },
            label = t("synopsis"),
            modifier = Modifier.fillMaxWidth(),
            multiline = true,
            placeholder = t("synopsis_placeholder"),
        )
        ZillitButton(
            text = t("save"),
            onClick = {
                // Saved on demand rather than on every keystroke; the web
                // saves on blur, which a desktop form has no equivalent of.
                scene?.let { record ->
                    ctx.launchWrite(
                        { ctx.api.put("/scenes/${record.id}", body("scene_description" to synopsis)) },
                    ) { onReload() }
                }
            },
            variant = ButtonVariant.Secondary,
            enabled = scene != null && synopsis != scene.str("scene_description"),
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MutedText("$total ${t("elements_tagged")}")
            Row(
                Modifier.weight(1f),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MutedText(t("show_empty"))
                ZillitSwitch(checked = showEmpty, onCheckedChange = { showEmpty = it })
            }
        }
    }

    categories
        .filter { showEmpty || it.recs("tags").isNotEmpty() }
        .forEach { entry -> CategoryCard(entry, onReload) }
}

/** One category and the elements this scene needs from it. */
@Composable
private fun CategoryCard(entry: Rec, onReload: () -> Unit) {
    val ctx = LocalSs.current
    val category = entry.rec("category")
    val elements = Breakdown.byElement(entry.recs("tags"))
    ZillitSectionCard(modifier = Modifier.fillMaxWidth(), title = category?.str("name").orEmpty()) {
        if (elements.isEmpty()) {
            MutedText(t("no_items_matching"))
            return@ZillitSectionCard
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        ) {
            elements.forEach { element -> ElementChip(element) { removeElement(ctx, element, onReload) } }
        }
    }
}

/** One element, how many times the script mentions it, and the way to take it off. */
@Composable
private fun ElementChip(element: TaggedElement, onRemove: () -> Unit) {
    val ctx = LocalSs.current
    val suggested = element.tags.any { it.str("status") == Breakdown.SUGGESTED }
    Row(
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SsTag(
            label = element.name + if (element.tags.size > 1) " ×${element.tags.size}" else "",
            // A suggestion nobody has confirmed reads differently from a tag
            // somebody made.
            tone = if (suggested) StatusTone.Escalated else StatusTone.Neutral,
        )
        ZillitIconButton(
            icon = ZillitIcons.Close,
            contentDescription = t("remove"),
            onClick = { ctx.whenPost(onRemove) },
            size = CHIP_ACTION,
        )
    }
}

/**
 * Takes an element off the scene — which means deleting EVERY tag of it.
 *
 * One element can be tagged three times because the script mentions it three
 * times, and leaving one behind would leave the element on the sheet.
 */
private fun removeElement(ctx: SsCtx, element: TaggedElement, onReload: () -> Unit) {
    ctx.scope.launch {
        // Sequential, not parallel: these are deletes against one scene's
        // sheet, and the web's own batch reports the FIRST failure, so the
        // order has to be knowable.
        var failed = false
        element.tags.forEach { tag ->
            if (!failed && ctx.write { ctx.api.delete("/breakdown/tag/${tag.id}") } == null) failed = true
        }
        onReload()
    }
}

private val SCRIPT_HEIGHT = 320.dp
private val CHIP_ACTION = 20.dp
