@file:Suppress("LongMethod") // The form's sections read best in source order.

package com.zillit.desktop.feature.sides.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.component.ZillitTooltip
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.sides.domain.SceneDisplayMode
import com.zillit.desktop.feature.sides.domain.ScenePage
import com.zillit.desktop.feature.sides.domain.Script
import com.zillit.desktop.feature.sides.domain.ScriptVersion
import com.zillit.desktop.feature.sides.domain.SidesRules
import com.zillit.desktop.feature.sides.domain.SidesStatus
import com.zillit.desktop.feature.sides.ui.GenerateState
import com.zillit.desktop.feature.sides.ui.SidesEvent
import com.zillit.desktop.feature.sides.ui.components.Accordion
import com.zillit.desktop.feature.sides.ui.components.DraggableSceneOrder
import com.zillit.desktop.feature.sides.ui.components.FieldLabel
import com.zillit.desktop.feature.sides.ui.components.LinkButton
import com.zillit.desktop.feature.sides.ui.components.RadioCard
import com.zillit.desktop.feature.sides.ui.components.SceneChip
import com.zillit.desktop.feature.sides.ui.components.SceneNumberGrid
import com.zillit.desktop.feature.sides.ui.components.SidesBadge
import com.zillit.desktop.feature.sides.ui.components.SidesLoader
import com.zillit.desktop.feature.sides.ui.components.hand
import com.zillit.desktop.feature.sides.ui.components.hexColor

/**
 * Generate Sides — the web's single-column `GenerateSidesForm`: Scripts →
 * Pick scenes (versions) → Pages → Scene order → Selected scenes →
 * Unselected-scenes mode → Title → Cancel / Submit; then the result stage.
 */
@Composable
internal fun GeneratePage(form: GenerateState, canDownload: Boolean, onEvent: (SidesEvent) -> Unit) {
    val colors = ZillitTheme.colors
    Column(
        modifier = Modifier.fillMaxSize().padding(
            horizontal = ZillitTheme.spacing.xl,
            vertical = ZillitTheme.spacing.lg,
        ),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        LinkButton(
            "Back to Sides",
            icon = ZillitIcons.ArrowLeft,
            onClick = { onEvent(SidesEvent.GenClose) },
            muted = true,
        )
        ZillitScrollColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(end = RAIL_GUTTER, bottom = ZillitTheme.spacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 860.dp)
                    .fillMaxWidth()
                    .clip(ZillitTheme.shapes.large)
                    .background(colors.surface)
                    .border(1.dp, colors.border, ZillitTheme.shapes.large)
                    .padding(28.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                ZillitText("Generate Sides", style = ZillitTheme.typography.titleLarge)
                when {
                    form.loading -> SidesLoader("Loading scripts…")
                    form.result != null || form.running -> ResultStage(form, canDownload, onEvent)
                    else -> SetupForm(form, onEvent)
                }
            }
        }
    }
}

// ── Setup ─────────────────────────────────────────────────

@Composable
private fun SetupForm(form: GenerateState, onEvent: (SidesEvent) -> Unit) {
    ScriptsSection(form, onEvent)
    Section("Pick scenes (versions)") {
        form.selectedScript?.let { script -> SourceHead(script, active = form.isActiveScript) }
        VersionsBox(form, form.primaryVersions, form.isActiveScript, "No script versions — use Pages below.", onEvent)
    }
    form.extraScriptIds.forEach { id ->
        val script = form.scripts.firstOrNull { it.id == id } ?: return@forEach
        Section(null) {
            SourceHead(script, active = script.id == form.activeScriptId) {
                LinkButton("Remove", icon = ZillitIcons.Close, onClick = { onEvent(SidesEvent.GenRemoveScript(id)) })
            }
            VersionsBox(
                form = form,
                versions = form.versions[id].orEmpty(),
                isActiveScript = script.id == form.activeScriptId,
                emptyText = "No script versions.",
                onEvent = onEvent,
            )
        }
    }
    if (form.pages.isNotEmpty()) {
        Section("Pages (scene folders)") {
            SourceBox { form.pages.forEach { page -> PageAccordion(form, page, onEvent) } }
        }
    }
    if (form.allSelectedScenes.isNotEmpty()) OrderSection(form, onEvent)
    if (form.readyToSubmit) {
        Section("Selected scenes") { SelectedChips(form, onEvent) }
    }
    ModeSection(form.displayMode) { onEvent(SidesEvent.GenDisplayMode(it)) }
    Section("Title", optional = true) {
        ZillitTextField(
            value = form.title,
            onValueChange = { onEvent(SidesEvent.GenTitle(it)) },
            placeholder = "Auto-generated",
        )
    }
    FormFooter(form, onEvent)
}

@Composable
private fun ScriptsSection(form: GenerateState, onEvent: (SidesEvent) -> Unit) {
    Section("Scripts") {
        ZillitSelect(
            value = form.selectedScript,
            options = form.scripts,
            onSelect = { it?.let { script -> onEvent(SidesEvent.GenPickScript(script.id)) } },
            label = { script -> script?.let { scriptOption(it) } ?: "Select a script" },
        )
        if (form.addableScripts.isNotEmpty()) {
            ZillitSelect(
                value = null,
                options = form.addableScripts,
                onSelect = { it?.let { script -> onEvent(SidesEvent.GenAddScript(script.id)) } },
                label = { script -> script?.title ?: "+ Add another script…" },
            )
        }
    }
}

/** One script's versions as accordions; the active script's current version is badged. */
@Composable
private fun VersionsBox(
    form: GenerateState,
    versions: List<ScriptVersion>,
    isActiveScript: Boolean,
    emptyText: String,
    onEvent: (SidesEvent) -> Unit,
) {
    SourceBox {
        if (versions.isEmpty()) {
            ZillitText(emptyText, style = ZillitTheme.typography.bodySmall, color = ZillitTheme.colors.textMuted)
        }
        versions.forEach { version ->
            VersionAccordion(
                form = form,
                version = version,
                isCurrent = isActiveScript && version.id == form.activeVersionId,
                onEvent = onEvent,
            )
        }
    }
}

@Composable
private fun OrderSection(form: GenerateState, onEvent: (SidesEvent) -> Unit) {
    val colors = ZillitTheme.colors
    Section("Scene order") {
        ZillitCheckbox(
            checked = form.rearrange,
            onCheckedChange = { onEvent(SidesEvent.GenRearrange(it)) },
            label = "Rearrange scene order",
        )
        if (form.rearrange) {
            ZillitText(
                text = "Drag chips to reorder — sides render top-to-bottom in this order.",
                style = ZillitTheme.typography.bodySmall,
                color = colors.textMuted,
            )
            DraggableSceneOrder(
                order = form.order,
                available = form.allSelectedScenes,
                onChange = { onEvent(SidesEvent.GenOrder(it)) },
            )
            if (form.order.isNotEmpty()) {
                ZillitText(
                    text = "Sides will be ordered as: ${form.order.joinToString(", ")}",
                    style = ZillitTheme.typography.bodySmall,
                    color = colors.textMuted,
                )
            }
        }
    }
}

/** The two unselected-scenes cards, shared with the Autogenerate dialog. */
@Composable
internal fun ModeSection(mode: String, onChange: (String) -> Unit) {
    Section("Unselected scenes") {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RadioCard(
                title = "Cross out unselected scenes",
                subtitle = "Keep full pages; strike through scenes not selected (default).",
                selected = mode == SceneDisplayMode.CROSSOUT,
                onClick = { onChange(SceneDisplayMode.CROSSOUT) },
                modifier = Modifier.weight(1f),
            )
            RadioCard(
                title = "Hide unselected scenes",
                subtitle = "Only the selected scenes appear.",
                selected = mode == SceneDisplayMode.HIDE,
                onClick = { onChange(SceneDisplayMode.HIDE) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun FormFooter(form: GenerateState, onEvent: (SidesEvent) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        ZillitText(
            text = if (form.readyToSubmit) {
                "${form.sceneCount} scene${if (form.sceneCount == 1) "" else "s"} selected"
            } else {
                "No scenes selected yet"
            },
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textMuted,
            modifier = Modifier.weight(1f),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ZillitButton("Cancel", onClick = { onEvent(SidesEvent.GenClose) }, variant = ButtonVariant.Secondary)
            ZillitButton(
                text = if (form.running) "Submitting…" else "Submit",
                onClick = { onEvent(SidesEvent.GenSubmit) },
                enabled = !form.running && form.scriptId.isNotBlank() && form.readyToSubmit,
                loading = form.running,
                leadingIcon = ZillitIcons.Send,
            )
        }
    }
}

/** The web's option label: a file-less script says so. */
private fun scriptOption(script: Script): String =
    if (script.hasFile) script.title else "${script.title} (no file — pages only)"

@Composable
internal fun Section(label: String?, optional: Boolean = false, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        label?.let { FieldLabel(it, optional) }
        content()
    }
}

@Composable
private fun SourceHead(script: Script, active: Boolean, trailing: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ZillitText(script.title, style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
        if (active) SidesBadge("ACTIVE", tint = ZillitTheme.colors.success)
        Spacer(Modifier.weight(1f))
        trailing?.invoke()
    }
}

@Composable
private fun SourceBox(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.medium)
            .background(ZillitTheme.colors.surfaceSunken)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

/** One version: scenes load on first expand and are held thereafter. */
@Composable
private fun VersionAccordion(
    form: GenerateState,
    version: ScriptVersion,
    isCurrent: Boolean,
    onEvent: (SidesEvent) -> Unit,
) {
    val picked = form.versionPicks[version.id].orEmpty()
    val open = version.id in form.openVersions
    Accordion(
        open = open,
        onToggle = { onEvent(SidesEvent.GenToggleVersionOpen(version.id)) },
        head = {
            ZillitText(version.label, style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
            if (isCurrent) SidesBadge("CURRENT")
            Spacer(Modifier.weight(1f))
            if (!open && picked.isNotEmpty()) PickCount(picked.size.toString())
        },
    ) {
        if (version.id in form.scenesLoading) {
            ZillitText(
                "Loading scenes…",
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
            )
        } else {
            SceneNumberGrid(
                scenes = form.scenesByVersion[version.id].orEmpty(),
                picked = picked.toSet(),
                onToggle = { onEvent(SidesEvent.GenToggleScene(version.id, it)) },
                onSelectAll = { onEvent(SidesEvent.GenSetScenes(version.id, it)) },
                onClear = { onEvent(SidesEvent.GenSetScenes(version.id, emptyList())) },
            )
        }
    }
}

/** One page folder; a whole-PDF checkbox stands in when no scenes were detected. */
@Composable
private fun PageAccordion(form: GenerateState, page: ScenePage, onEvent: (SidesEvent) -> Unit) {
    val colors = ZillitTheme.colors
    val picked = form.pagePicks[page.id].orEmpty()
    val whole = page.id in form.wholePages
    val open = page.id in form.openPages
    Accordion(
        open = open,
        onToggle = { onEvent(SidesEvent.GenTogglePageOpen(page.id)) },
        head = {
            Box(Modifier.size(10.dp).clip(CircleShape).background(hexColor(page.color) ?: colors.textMuted))
            ZillitText(
                page.sceneNumber.ifBlank { "Page" },
                style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            )
            if (page.description.isNotBlank()) {
                ZillitText(
                    text = page.description,
                    style = ZillitTheme.typography.bodySmall,
                    color = colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            ZillitText("${page.pageCount} pg", style = ZillitTheme.typography.labelSmall, color = colors.textMuted)
            if (!open && (picked.isNotEmpty() || whole)) {
                PickCount(if (whole && picked.isEmpty()) "PDF" else picked.size.toString())
            }
        },
    ) {
        val scenes = form.scenesByPage[page.id].orEmpty()
        when {
            page.id in form.scenesLoading -> ZillitText(
                "Loading scenes…",
                style = ZillitTheme.typography.bodySmall,
                color = colors.textMuted,
            )
            scenes.isEmpty() -> ZillitCheckbox(
                checked = whole,
                onCheckedChange = { onEvent(SidesEvent.GenToggleWholePage(page.id)) },
                label = "No scenes detected — include the entire PDF",
            )
            else -> SceneNumberGrid(
                scenes = scenes,
                picked = picked.toSet(),
                onToggle = { onEvent(SidesEvent.GenTogglePageScene(page.id, it)) },
                onSelectAll = { onEvent(SidesEvent.GenSetPageScenes(page.id, it)) },
                onClear = { onEvent(SidesEvent.GenSetPageScenes(page.id, emptyList())) },
            )
        }
    }
}

@Composable
private fun PickCount(text: String) {
    Box(
        modifier = Modifier.clip(ZillitTheme.shapes.pill).background(ZillitTheme.colors.accent).padding(
            horizontal = 8.dp,
            vertical = 2.dp,
        ),
    ) {
        ZillitText(text, style = ZillitTheme.typography.labelSmall, color = androidx.compose.ui.graphics.Color.White)
    }
}

/** Every picked scene as a removable chip; clicking one deselects it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SelectedChips(form: GenerateState, onEvent: (SidesEvent) -> Unit) {
    val colors = ZillitTheme.colors
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val chips = form.versionPicks.flatMap { (versionId, scenes) ->
            scenes.map { scene -> scene to { onEvent(SidesEvent.GenToggleScene(versionId, scene)) } }
        } + form.pagePicks.flatMap { (pageId, scenes) ->
            scenes.map { scene -> scene to { onEvent(SidesEvent.GenTogglePageScene(pageId, scene)) } }
        }
        chips.forEach { (scene, remove) ->
            ZillitTooltip("Remove scene") {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(colors.accentSoft)
                        .border(1.dp, colors.accent.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
                        .clickable(onClick = remove)
                        .hand()
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    ZillitText(
                        scene,
                        style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = colors.accentText,
                    )
                    ZillitIcon(icon = ZillitIcons.Close, tint = colors.accentText, size = 9.dp)
                }
            }
        }
    }
}

// ── Result ─────────────────────────────────────────────────────────────────

/** Generating → failed → success with the selection summary and review verbs. */
@Composable
private fun ResultStage(form: GenerateState, canDownload: Boolean, onEvent: (SidesEvent) -> Unit) {
    val colors = ZillitTheme.colors
    val result = form.result
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when {
            result == null || !result.status.terminal -> {
                SidesLoader("Generating sides…")
                ZillitText(
                    "This usually takes under a minute.",
                    style = ZillitTheme.typography.bodySmall,
                    color = colors.textMuted,
                )
            }
            result.status == SidesStatus.Error -> {
                ResultIcon(ZillitIcons.Close, colors.danger)
                ZillitText("Generation failed", style = ZillitTheme.typography.titleLarge)
                ZillitText(
                    text = result.error.ifBlank {
                        "Something went wrong while generating your sides. Please try again."
                    },
                    style = ZillitTheme.typography.bodySmall,
                    color = colors.textMuted,
                )
                ZillitButton(
                    "Back to setup",
                    onClick = { onEvent(SidesEvent.GenBackToForm) },
                    variant = ButtonVariant.Secondary,
                )
            }
            else -> {
                ResultIcon(ZillitIcons.Check, colors.success)
                ZillitText("Sides generated successfully", style = ZillitTheme.typography.titleLarge)
                ZillitText(
                    text = "Review your sides, then publish them to the Sides module.",
                    style = ZillitTheme.typography.bodySmall,
                    color = colors.textMuted,
                )
                if (result.title.isNotBlank()) {
                    Row(
                        modifier = Modifier.clip(ZillitTheme.shapes.pill).background(colors.surfaceSunken).padding(
                            horizontal = 12.dp,
                            vertical = 6.dp,
                        ),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        ZillitIcon(icon = ZillitIcons.File, tint = colors.textSecondary, size = 14.dp)
                        ZillitText(result.title, style = ZillitTheme.typography.label)
                    }
                }
                ResultSelection(form)
                ReviewActions(form, canDownload, onEvent)
            }
        }
    }
}

@Composable
private fun ResultIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: androidx.compose.ui.graphics.Color,
) {
    Box(
        modifier = Modifier.size(64.dp).clip(CircleShape).background(tint.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        ZillitIcon(icon = icon, tint = tint, size = 30.dp)
    }
}

/** The scenes + pages the run was built from — read from the form, never the poll. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ResultSelection(form: GenerateState) {
    val colors = ZillitTheme.colors
    val scenes = form.resultScenes
    val pages = form.resultPages
    if (scenes.isEmpty() && pages.isEmpty()) return
    Column(
        modifier = Modifier
            .widthIn(max = 620.dp)
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.medium)
            .background(colors.surfaceSunken)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (scenes.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ZillitText(
                    "Scenes",
                    style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = colors.textMuted,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    scenes.take(SidesRules.RESULT_SCENE_CAP).forEach { SceneChip(it) }
                    val overflow = scenes.size - SidesRules.RESULT_SCENE_CAP
                    if (overflow > 0) {
                        SceneChip(
                            "+$overflow",
                            more = true,
                            tip = scenes.drop(SidesRules.RESULT_SCENE_CAP).joinToString(", "),
                        )
                    }
                }
            }
        }
        if (pages.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ZillitText(
                    "Pages",
                    style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = colors.textMuted,
                )
                pages.forEach { page ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SceneChip(page.number, tint = hexColor(page.color))
                        if (page.scenes.isEmpty()) {
                            ZillitText("Whole PDF", style = ZillitTheme.typography.bodySmall, color = colors.textMuted)
                        } else {
                            val shown = page.scenes.take(SidesRules.RESULT_PAGE_SCENE_CAP)
                            ZillitText(shown.joinToString(", "), style = ZillitTheme.typography.bodySmall)
                            val more = page.scenes.size - shown.size
                            if (more > 0) {
                                ZillitTooltip(page.scenes.joinToString(", ")) {
                                    ZillitText(
                                        "+$more more",
                                        style = ZillitTheme.typography.labelSmall,
                                        color = colors.accentText,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The review verbs: View first; once viewed, View again / Download /
 * Publish; Back returns to the form with the picks intact (ZL-19729).
 */
@Composable
internal fun ReviewActions(form: GenerateState, canDownload: Boolean, onEvent: (SidesEvent) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        ZillitButton("Back", onClick = { onEvent(SidesEvent.GenBackToForm) }, variant = ButtonVariant.Secondary)
        if (!form.viewed) {
            ZillitButton("View sides", onClick = { onEvent(SidesEvent.GenView) }, leadingIcon = ZillitIcons.Eye)
        } else {
            ZillitButton(
                "View again",
                onClick = { onEvent(SidesEvent.GenView) },
                variant = ButtonVariant.Secondary,
                leadingIcon = ZillitIcons.Eye,
            )
            ZillitButton(
                text = if (canDownload) "Download" else "Download (request access)",
                onClick = { onEvent(SidesEvent.GenDownload) },
                variant = ButtonVariant.Secondary,
                leadingIcon = ZillitIcons.Download,
            )
            ZillitButton(
                text = if (form.publishing) "Publishing…" else "Publish",
                onClick = { onEvent(SidesEvent.GenPublish) },
                loading = form.publishing,
                enabled = !form.publishing,
                leadingIcon = ZillitIcons.Check,
            )
        }
    }
}

/** Keeps the card clear of the scroll rail. */
private val RAIL_GUTTER = 14.dp
