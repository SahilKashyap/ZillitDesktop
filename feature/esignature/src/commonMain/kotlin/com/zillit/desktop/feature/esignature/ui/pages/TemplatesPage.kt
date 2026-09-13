@file:Suppress("LongMethod", "TooManyFunctions", "CyclomaticComplexMethod", "ComplexCondition")

package com.zillit.desktop.feature.esignature.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ColumnWidth
import com.zillit.desktop.core.designsystem.component.TableColumn
import com.zillit.desktop.core.designsystem.component.TagTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDataTable
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitLazyVerticalGrid
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitTag
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.textColumn
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.esignature.domain.EnvelopeTemplate
import com.zillit.desktop.feature.esignature.domain.EsignFormat
import com.zillit.desktop.feature.esignature.ui.EsignEvent
import com.zillit.desktop.feature.esignature.ui.EsignUiState
import com.zillit.desktop.feature.esignature.ui.ListLayout
import com.zillit.desktop.feature.esignature.ui.components.ConfirmDialog
import com.zillit.desktop.feature.esignature.ui.components.DocTile
import com.zillit.desktop.feature.esignature.ui.components.EsignCard
import com.zillit.desktop.feature.esignature.ui.components.KeyValue
import com.zillit.desktop.feature.esignature.ui.components.LayoutToggle
import com.zillit.desktop.feature.esignature.ui.components.color

/** The template library — the web's `TemplateLibrary`. */
@Composable
internal fun TemplatesPage(state: EsignUiState, onEvent: (EsignEvent) -> Unit) {
    val templates = state.templates
    val colors = ZillitTheme.colors
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ZillitSearchField(
                value = templates.search,
                onValueChange = { onEvent(EsignEvent.SearchTemplates(it)) },
                placeholder = "Search templates by name or description",
                modifier = Modifier.width(320.dp),
            )
            ZillitSelect(
                value = templates.category,
                options = listOf<String?>(null) + templates.categories,
                onSelect = { onEvent(EsignEvent.FilterTemplates(it)) },
                label = { it?.let(::prettyCategory) ?: "All categories" },
                modifier = Modifier.width(180.dp),
            )
            Spacer(Modifier.weight(1f))
            ZillitText(
                "${templates.visible.size} of ${templates.items.size}",
                style = ZillitTheme.typography.labelSmall,
                color = colors.textMuted,
            )
            ZillitButton(
                "New template",
                onClick = { onEvent(EsignEvent.StartTemplate) },
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Add,
            )
            LayoutToggle(isCard = templates.layout == ListLayout.Card) {
                onEvent(EsignEvent.SetTemplatesLayout(if (it) ListLayout.Card else ListLayout.List))
            }
        }
        Box(Modifier.fillMaxSize()) {
            when {
                templates.loading && !templates.loaded -> Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { ZillitSpinner() }
                templates.visible.isEmpty() -> Box(
                    Modifier.fillMaxWidth().padding(vertical = 64.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    ZillitEmptyState(
                        title = if (templates.items.isEmpty()) "No templates yet" else "No templates match",
                        message = if (templates.items.isEmpty()) {
                            "Save an envelope's design once and reuse it for every crew member."
                        } else {
                            "Try a different search or category."
                        },
                        icon = ZillitIcons.File,
                        action = if (templates.items.isEmpty()) {
                            {
                                ZillitButton(
                                    "New template",
                                    onClick = { onEvent(EsignEvent.StartTemplate) },
                                    size = ButtonSize.Small,
                                    leadingIcon = ZillitIcons.Add,
                                )
                            }
                        } else {
                            null
                        },
                    )
                }
                templates.layout == ListLayout.Card -> TemplateGrid(templates.visible, state, onEvent)
                else -> TemplateTable(templates.visible, state, onEvent)
            }
        }
    }
    TemplateDetailDialog(state, onEvent)
    ConfirmDialog(
        visible = templates.confirmDeleteId != null,
        title = "Delete this template?",
        body = "Envelopes already created from it are unaffected.",
        confirmLabel = "Delete",
        onConfirm = { onEvent(EsignEvent.ConfirmDeleteTemplate) },
        onDismiss = { onEvent(EsignEvent.AskDeleteTemplate(null)) },
    )
}

internal fun prettyCategory(raw: String): String = raw.replace('_', ' ').replace(
    '-',
    ' ',
).split(' ').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }

@Composable
private fun TemplateGrid(items: List<EnvelopeTemplate>, state: EsignUiState, onEvent: (EsignEvent) -> Unit) {
    val colors = ZillitTheme.colors
    ZillitLazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 290.dp),
        modifier = Modifier.fillMaxSize().clip(ZillitTheme.shapes.large).background(colors.surfaceSunken),
        contentPadding = PaddingValues(ZillitTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        items(items, key = { it.id }) { template -> TemplateCard(template, state, onEvent) }
    }
}

@Composable
private fun TemplateCard(template: EnvelopeTemplate, state: EsignUiState, onEvent: (EsignEvent) -> Unit) {
    val colors = ZillitTheme.colors
    val busy = state.templates.busyId == template.id
    EsignCard(onClick = { onEvent(EsignEvent.ShowTemplate(template)) }) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            DocTile()
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                ZillitText(
                    template.name.ifBlank { "Untitled template" },
                    style = ZillitTheme.typography.titleSmall,
                    maxLines = 2,
                )
                if (template.category.isNotBlank()) ZillitTag(prettyCategory(template.category), tone = TagTone.Accent)
            }
        }
        if (template.description.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            ZillitText(
                template.description,
                style = ZillitTheme.typography.bodySmall,
                color = colors.textSecondary,
                maxLines = 2,
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ZillitTag("${template.signerSlots} role${if (template.signerSlots == 1) "" else "s"}")
            ZillitTag("${template.fields.size} field${if (template.fields.size == 1) "" else "s"}")
            if (template.pageCount > 0) {
                ZillitTag("${template.pageCount} page${if (template.pageCount == 1) "" else "s"}")
            }
        }
        Spacer(Modifier.height(4.dp))
        ZillitText(
            "Updated ${EsignFormat.date(template.updated ?: template.created)}",
            style = ZillitTheme.typography.labelSmall,
            color = colors.textMuted,
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ZillitButton(
                "Use",
                onClick = { onEvent(EsignEvent.UseTemplate(template)) },
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Send,
                enabled = !busy,
            )
            ZillitButton(
                "Bulk send",
                onClick = { onEvent(EsignEvent.StartBulkSend(template)) },
                size = ButtonSize.Small,
                variant = ButtonVariant.Secondary,
                enabled = !busy,
            )
            Spacer(Modifier.weight(1f))
            ZillitButton(
                "Edit",
                onClick = { onEvent(EsignEvent.EditTemplate(template)) },
                size = ButtonSize.Small,
                variant = ButtonVariant.Tertiary,
                enabled = !busy,
            )
            ZillitButton(
                "⋯",
                onClick = { onEvent(EsignEvent.ShowTemplate(template)) },
                size = ButtonSize.Small,
                variant = ButtonVariant.Tertiary,
                loading = busy,
            )
        }
    }
}

@Composable
private fun TemplateTable(items: List<EnvelopeTemplate>, state: EsignUiState, onEvent: (EsignEvent) -> Unit) {
    val colors = ZillitTheme.colors
    Box(
        Modifier.fillMaxSize().clip(ZillitTheme.shapes.large).background(colors.surface)
            .border(1.dp, colors.border, ZillitTheme.shapes.large),
    ) {
        ZillitDataTable(
            rows = items,
            key = { it.id },
            onRowClick = { onEvent(EsignEvent.ShowTemplate(it)) },
            emptyTitle = "No templates",
            columns = listOf(
                TableColumn(header = "Template", width = ColumnWidth.Weight(2f)) { template ->
                    Column {
                        ZillitText(
                            template.name.ifBlank { "Untitled" },
                            style = ZillitTheme.typography.bodyMedium,
                            maxLines = 1,
                        )
                        if (template.description.isNotBlank()) ZillitText(
                            template.description,
                            style = ZillitTheme.typography.labelSmall,
                            color = colors.textMuted,
                            maxLines = 1,
                        )
                    }
                },
                TableColumn(header = "Category", width = ColumnWidth.Fixed(150.dp)) { template ->
                    if (template.category.isNotBlank()) {
                        ZillitTag(prettyCategory(template.category), tone = TagTone.Accent)
                    } else ZillitText(
                        "—",
                        color = colors.textMuted,
                    )
                },
                textColumn(
                    header = "Roles",
                    width = ColumnWidth.Fixed(70.dp),
                    numeric = true,
                ) { it.signerSlots.toString() },
                textColumn(
                    header = "Fields",
                    width = ColumnWidth.Fixed(70.dp),
                    numeric = true,
                ) { it.fields.size.toString() },
                textColumn(
                    header = "Updated",
                    width = ColumnWidth.Fixed(110.dp),
                    muted = true,
                ) { EsignFormat.date(it.updated ?: it.created) },
                TableColumn(header = "", width = ColumnWidth.Fixed(300.dp)) { template ->
                    val busy = state.templates.busyId == template.id
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End)) {
                        ZillitButton(
                            "Use",
                            onClick = { onEvent(EsignEvent.UseTemplate(template)) },
                            size = ButtonSize.Small,
                            enabled = !busy,
                        )
                        ZillitButton(
                            "Bulk send",
                            onClick = { onEvent(EsignEvent.StartBulkSend(template)) },
                            size = ButtonSize.Small,
                            variant = ButtonVariant.Secondary,
                            enabled = !busy,
                        )
                        ZillitButton(
                            "Edit",
                            onClick = { onEvent(EsignEvent.EditTemplate(template)) },
                            size = ButtonSize.Small,
                            variant = ButtonVariant.Tertiary,
                            enabled = !busy,
                        )
                        ZillitButton(
                            "Delete",
                            onClick = { onEvent(EsignEvent.AskDeleteTemplate(template.id)) },
                            size = ButtonSize.Small,
                            variant = ButtonVariant.Tertiary,
                            loading = busy,
                        )
                    }
                },
            ),
        )
    }
}

@Composable
private fun TemplateDetailDialog(state: EsignUiState, onEvent: (EsignEvent) -> Unit) {
    val colors = ZillitTheme.colors
    val template = state.templates.detail
    ZillitDialogShell(
        title = template?.name ?: "Template",
        subtitle = template?.category?.takeIf { it.isNotBlank() }?.let(::prettyCategory),
        visible = template != null,
        onDismiss = { onEvent(EsignEvent.ShowTemplate(null)) },
        scrollable = false,
        icon = ZillitIcons.File,
        width = 520.dp,
        actions = {
            if (template != null) {
                ZillitButton(
                    "Delete",
                    onClick = { onEvent(EsignEvent.AskDeleteTemplate(template.id)) },
                    variant = ButtonVariant.Danger,
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.Trash,
                )
                ZillitButton(
                    "Duplicate",
                    onClick = {
                        onEvent(EsignEvent.DuplicateTemplate(template))
                        onEvent(EsignEvent.ShowTemplate(null))
                    },
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                )
                ZillitButton(
                    "Edit",
                    onClick = { onEvent(EsignEvent.ShowTemplate(null)); onEvent(EsignEvent.EditTemplate(template)) },
                    variant = ButtonVariant.Secondary,
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.Edit,
                )
                ZillitButton(
                    "Use template",
                    onClick = { onEvent(EsignEvent.ShowTemplate(null)); onEvent(EsignEvent.UseTemplate(template)) },
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.Send,
                )
            }
        },
    ) {
        if (template == null) return@ZillitDialogShell
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (template.description.isNotBlank()) ZillitText(
                template.description,
                style = ZillitTheme.typography.bodyMedium,
                color = colors.textSecondary,
            )
            KeyValue("Document", template.document?.name ?: "—")
            KeyValue("Pages", template.pageCount.takeIf { it > 0 }?.toString() ?: "—")
            KeyValue(
                "Roles",
                template.recipients
                    .joinToString { it.placeholderLabel.ifBlank { it.name.ifBlank { it.role } } }
                    .ifBlank { "—" },
            )
            KeyValue("Created", EsignFormat.dateTime(template.created))
            KeyValue("Updated", EsignFormat.dateTime(template.updated))
            if (template.settings.emailSubject.isNotBlank()) KeyValue("Email subject", template.settings.emailSubject)
            if (template.fields.isNotEmpty()) {
                ZillitText("FIELDS", style = ZillitTheme.typography.labelSmall, color = colors.textMuted)
                template.fields.groupBy { it.type }.forEach { (type, list) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(Modifier.width(10.dp).height(10.dp).clip(ZillitTheme.shapes.small).background(type.color()))
                        ZillitText("${list.size} × ${type.label}", style = ZillitTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
