package com.zillit.desktop.feature.sides.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitPageHeader
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitTab
import com.zillit.desktop.core.designsystem.component.ZillitTabStrip
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.feature.sides.domain.Script
import com.zillit.desktop.feature.sides.domain.SidesRecord
import com.zillit.desktop.feature.sides.domain.SidesStatus
import com.zillit.desktop.feature.sides.ui.pages.GeneratePage
import com.zillit.desktop.feature.sides.ui.pages.SidesPdfOverlay

/** The sides tool: generated sides, their history, and the scripts manager. */
@Composable
fun SidesScreen(state: SidesUiState, onEvent: (SidesEvent) -> Unit) {
    val generate = state.generate
    if (generate != null) {
        GeneratePage(state = state, form = generate, onEvent = onEvent)
        return
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(ZillitTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            HubChrome(state = state, onEvent = onEvent)

            when {
                state.loading -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    ZillitSpinner()
                }
                state.destination == SidesDestination.Scripts -> ScriptList(state, onEvent)
                else -> SidesList(state, onEvent)
            }
        }

        state.pdf?.let { SidesPdfOverlay(view = it, onEvent = onEvent) }
    }
}

@Composable
@Suppress("LongMethod") // Chrome: header, notices and the tab strip in order.
private fun HubChrome(state: SidesUiState, onEvent: (SidesEvent) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
        ZillitPageHeader(
            title = "Sides",
            description = "Generate the day's script sides and manage the scripts behind them.",
            actions = {
                if (state.viewer.canGenerate && state.destination == SidesDestination.Scripts) {
                    ZillitButton(
                        text = "Upload script",
                        onClick = { onEvent(SidesEvent.UploadScript) },
                        variant = ButtonVariant.Secondary,
                        loading = state.busy,
                    )
                }
                if (state.viewer.canGenerate) {
                    ZillitButton(
                        text = "Generate sides",
                        onClick = { onEvent(SidesEvent.OpenGenerate) },
                        loading = state.busy,
                    )
                }
            },
        )

        if (state.viewer.isBlocked) {
            ZillitNotice(text = "You do not have access to the sides tool.")
            return@Column
        }
        state.error?.let { message ->
            ZillitNotice(
                text = message,
                tone = StatusTone.Rejected,
                action = {
                    ZillitButton(
                        text = "Dismiss",
                        onClick = { onEvent(SidesEvent.DismissError) },
                        variant = ButtonVariant.Tertiary,
                        size = ButtonSize.Small,
                    )
                },
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            ZillitTabStrip(
                tabs = SidesDestination.entries.map { ZillitTab(id = it.name, label = it.label) },
                activeId = state.destination.name,
                onSelect = { id -> onEvent(SidesEvent.Open(SidesDestination.valueOf(id))) },
                modifier = Modifier.weight(1f),
            )
            if (state.destination == SidesDestination.Sides) {
                ZillitCheckbox(
                    checked = state.showHistory,
                    onCheckedChange = { onEvent(SidesEvent.ShowHistory(it)) },
                    label = "History",
                )
            }
        }
    }
}

@Composable
private fun SidesList(state: SidesUiState, onEvent: (SidesEvent) -> Unit) {
    if (state.visibleSides.isEmpty()) {
        ZillitText(
            text = "No sides yet — generate the first set.",
            style = ZillitTheme.typography.bodyMedium,
            color = ZillitTheme.colors.textMuted,
        )
        return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
        items(state.visibleSides, key = { it.id }) { sides ->
            SidesRow(state = state, sides = sides, onEvent = onEvent)
        }
    }
}

@Composable
private fun SidesRow(
    state: SidesUiState,
    sides: SidesRecord,
    onEvent: (SidesEvent) -> Unit,
) {
    ZillitSectionCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            Column(Modifier.weight(1f)) {
                ZillitText(
                    text = sides.title.ifBlank { "Sides" },
                    style = ZillitTheme.typography.titleSmall,
                )
                ZillitText(
                    text = listOf(
                        sides.scriptTitle,
                        sides.versionLabel,
                        sides.sceneNumbers.take(SCENE_CHIP_CAP).joinToString(", ")
                            .let { if (sides.sceneNumbers.size > SCENE_CHIP_CAP) "$it…" else it },
                        sides.generatedByName,
                    ).filter { it.isNotBlank() }.joinToString("  ·  "),
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textMuted,
                )
                if (sides.status == SidesStatus.Error && sides.error.isNotBlank()) {
                    ZillitText(
                        text = sides.error,
                        style = ZillitTheme.typography.bodySmall,
                        color = ZillitTheme.colors.textMuted,
                    )
                }
            }
            ZillitStatusPill(
                label = if (sides.status == SidesStatus.Unknown && sides.rawStatus.isNotBlank()) {
                    sides.rawStatus
                } else {
                    sides.status.label
                },
                tone = sides.status.tone,
            )
            SidesRowActions(state = state, sides = sides, onEvent = onEvent)
        }
    }
}

@Composable
private fun SidesRowActions(
    state: SidesUiState,
    sides: SidesRecord,
    onEvent: (SidesEvent) -> Unit,
) {
    if (sides.status.viewable) {
        ZillitButton(
            text = "View",
            onClick = { onEvent(SidesEvent.ViewSides(sides.id, sides.title)) },
            variant = ButtonVariant.Tertiary,
            size = ButtonSize.Small,
        )
        ZillitButton(
            text = "Download",
            onClick = { onEvent(SidesEvent.DownloadSides(sides.id)) },
            variant = ButtonVariant.Secondary,
            size = ButtonSize.Small,
        )
    }
    if (state.viewer.canGenerate) {
        ZillitButton(
            text = "Delete",
            onClick = { onEvent(SidesEvent.DeleteSides(sides.id)) },
            variant = ButtonVariant.Danger,
            size = ButtonSize.Small,
        )
    }
}

@Composable
private fun ScriptList(state: SidesUiState, onEvent: (SidesEvent) -> Unit) {
    if (state.scripts.isEmpty()) {
        ZillitText(
            text = "No scripts yet — upload the shooting script to begin.",
            style = ZillitTheme.typography.bodyMedium,
            color = ZillitTheme.colors.textMuted,
        )
        return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
        items(state.scripts, key = { it.id }) { script ->
            ScriptRow(state = state, script = script, onEvent = onEvent)
        }
    }
}

@Composable
private fun ScriptRow(state: SidesUiState, script: Script, onEvent: (SidesEvent) -> Unit) {
    ZillitSectionCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            Column(Modifier.weight(1f)) {
                ZillitText(text = script.title, style = ZillitTheme.typography.titleSmall)
                ZillitText(
                    text = listOf(
                        script.currentVersionLabel,
                        script.pageCount.takeIf { it > 0 }?.let { "$it pages" }.orEmpty(),
                        script.format,
                    ).filter { it.isNotBlank() }.joinToString("  ·  "),
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textMuted,
                )
            }
            if (state.viewer.canGenerate) {
                ZillitButton(
                    text = "Delete",
                    onClick = { onEvent(SidesEvent.DeleteScript(script.id)) },
                    variant = ButtonVariant.Danger,
                    size = ButtonSize.Small,
                )
            }
        }
    }
}

internal val SidesStatus.tone: StatusTone
    get() = when (this) {
        SidesStatus.Ready -> StatusTone.Ready
        SidesStatus.Generating -> StatusTone.Progress
        SidesStatus.Error, SidesStatus.Failed -> StatusTone.Rejected
        SidesStatus.Archived -> StatusTone.Done
        SidesStatus.Unknown -> StatusTone.Neutral
    }

private const val SCENE_CHIP_CAP = 13
