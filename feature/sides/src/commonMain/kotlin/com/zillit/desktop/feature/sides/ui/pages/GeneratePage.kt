package com.zillit.desktop.feature.sides.ui.pages

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitPageHeader
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.feature.sides.domain.GeneratePlan
import com.zillit.desktop.feature.sides.domain.SidesStatus
import com.zillit.desktop.feature.sides.ui.GenerateState
import com.zillit.desktop.feature.sides.ui.SidesEvent
import com.zillit.desktop.feature.sides.ui.SidesPdfView
import com.zillit.desktop.feature.sides.ui.SidesUiState
import com.zillit.desktop.feature.sides.ui.decodeImageBitmap
import com.zillit.desktop.feature.sides.ui.tone

/**
 * Manual generation: script, version, scenes, then the poll-and-review run.
 *
 * The scene list is the version's parsed scenes with checkboxes; the optional
 * order box takes the web's comma/space-separated syntax and switches the run
 * to `orderedScenes`.
 */
@Composable
internal fun GeneratePage(
    state: SidesUiState,
    form: GenerateState,
    onEvent: (SidesEvent) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(ZillitTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        ZillitPageHeader(
            title = "Generate sides",
            eyebrow = "Sides",
            actions = {
                ZillitButton(
                    text = "Back",
                    onClick = { onEvent(SidesEvent.CloseGenerate) },
                    variant = ButtonVariant.Tertiary,
                )
                ZillitButton(
                    text = if (form.running) "Generating…" else "Generate",
                    onClick = { onEvent(SidesEvent.StartGenerate) },
                    enabled = form.canStart,
                    loading = form.running,
                )
            },
        )

        state.error?.let { ZillitNotice(text = it, tone = StatusTone.Rejected) }
        form.result?.let { result ->
            ResultCard(form = form, onEvent = onEvent)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
            SetupCard(form = form, onEvent = onEvent, modifier = Modifier.width(SETUP_WIDTH))
            SceneCard(form = form, onEvent = onEvent, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun SetupCard(
    form: GenerateState,
    onEvent: (SidesEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    ZillitSectionCard(modifier = modifier, title = "Setup") {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitText(
                text = "Script",
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
            )
            ZillitSelect(
                value = form.scripts.firstOrNull { it.id == form.scriptId },
                options = form.scripts,
                onSelect = { it?.let { script -> onEvent(SidesEvent.PickScript(script.id)) } },
                label = { it?.title ?: "Pick a script" },
            )
            ZillitText(
                text = "Version",
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
            )
            ZillitSelect(
                value = form.versions.firstOrNull { it.id == form.versionId },
                options = form.versions,
                onSelect = { it?.let { version -> onEvent(SidesEvent.PickVersion(version.id)) } },
                label = { it?.versionLabel ?: "Pick a version" },
            )
            ZillitTextField(
                value = form.title,
                onValueChange = { onEvent(SidesEvent.TitleChanged(it)) },
                label = "Title (optional)",
            )
            ZillitCheckbox(
                checked = form.displayMode == GeneratePlan.DISPLAY_CROSSOUT,
                onCheckedChange = {
                    onEvent(SidesEvent.DisplayModeChanged(GeneratePlan.DISPLAY_CROSSOUT))
                },
                label = "Cross out skipped scenes",
            )
            ZillitCheckbox(
                checked = form.displayMode == GeneratePlan.DISPLAY_HIDE,
                onCheckedChange = {
                    onEvent(SidesEvent.DisplayModeChanged(GeneratePlan.DISPLAY_HIDE))
                },
                label = "Hide skipped scenes",
            )
            ZillitTextField(
                value = form.orderText,
                onValueChange = { onEvent(SidesEvent.OrderChanged(it)) },
                label = "Scene order (optional)",
                placeholder = "12, 9, 14A",
                helperText = "Rearranges the sides in this order",
            )
        }
    }
}

@Composable
private fun SceneCard(
    form: GenerateState,
    onEvent: (SidesEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    ZillitSectionCard(
        modifier = modifier,
        title = "Scenes",
        meta = "${form.selected.size} of ${form.scenes.size} selected",
    ) {
        when {
            form.scenesLoading -> Box(
                Modifier.fillMaxWidth().padding(ZillitTheme.spacing.lg),
                contentAlignment = Alignment.Center,
            ) { ZillitSpinner() }
            form.scenes.isEmpty() -> ZillitText(
                text = "This version has no parsed scenes.",
                style = ZillitTheme.typography.bodyMedium,
                color = ZillitTheme.colors.textMuted,
            )
            else -> LazyColumn(
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
            ) {
                items(form.scenes, key = { it.sceneNumber }) { scene ->
                    ZillitCheckbox(
                        checked = scene.sceneNumber in form.selected,
                        onCheckedChange = { onEvent(SidesEvent.ToggleScene(scene.sceneNumber)) },
                        label = listOf(
                            scene.sceneNumber,
                            scene.intExt,
                            scene.heading,
                            scene.timeOfDay,
                        ).filter { it.isNotBlank() }.joinToString("  ·  "),
                    )
                }
            }
        }
    }
}

/** The run's live status and, once ready, the review verbs. */
@Composable
private fun ResultCard(form: GenerateState, onEvent: (SidesEvent) -> Unit) {
    val result = form.result ?: return
    ZillitSectionCard(title = result.title.ifBlank { "Sides" }) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitStatusPill(label = result.status.label, tone = result.status.tone)
            if (result.status == SidesStatus.Error && result.error.isNotBlank()) {
                ZillitText(
                    text = result.error,
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textMuted,
                    modifier = Modifier.weight(1f),
                )
            } else {
                ZillitText(
                    text = "${result.totalScenes.takeIf { it > 0 } ?: result.sceneNumbers.size} scenes",
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textMuted,
                    modifier = Modifier.weight(1f),
                )
            }
            if (result.status == SidesStatus.Ready) {
                ZillitButton(
                    text = "View sides",
                    onClick = { onEvent(SidesEvent.ViewSides(result.id, result.title)) },
                    variant = ButtonVariant.Secondary,
                )
                ZillitButton(
                    text = if (form.publishing) "Publishing…" else "Publish",
                    onClick = { onEvent(SidesEvent.PublishResult) },
                    loading = form.publishing,
                )
            }
        }
    }
}

/** The finished PDF, page images stacked in a scrolling overlay. */
@Composable
internal fun SidesPdfOverlay(view: SidesPdfView, onEvent: (SidesEvent) -> Unit) {
    ZillitDialogShell(
        title = view.title.ifBlank { "Sides" },
        onDismiss = { onEvent(SidesEvent.ClosePdf) },
        visible = true,
        scrollable = false,
        actions = {
            ZillitButton(
                text = "Close",
                onClick = { onEvent(SidesEvent.ClosePdf) },
                variant = ButtonVariant.Tertiary,
            )
        },
    ) {
        if (view.loading) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(ZillitTheme.spacing.xl),
                contentAlignment = Alignment.Center,
            ) { ZillitSpinner() }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                view.pages.forEach { page ->
                    val bitmap = remember(page.page) { decodeImageBitmap(page.imageBytes) }
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap,
                            contentDescription = "Page ${page.page + 1}",
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

private val SETUP_WIDTH = 320.dp
