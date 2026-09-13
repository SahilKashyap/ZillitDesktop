package com.zillit.desktop.feature.documentdistribution.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitBadge
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitLazyColumn
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.component.ZillitTooltip
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.permissions.gatedClick
import com.zillit.desktop.feature.documentdistribution.domain.HtmlText
import com.zillit.desktop.feature.documentdistribution.ui.DocDistEvent
import com.zillit.desktop.feature.documentdistribution.ui.DocDistUiState

/** Reusable subject + body pairs — the web's `EmailTemplateManager`. */
@Suppress("LongMethod") // One screen section; splitting it separates each control from its state.
@Composable
fun TemplatesPage(state: DocDistUiState, onEvent: (DocDistEvent) -> Unit) {
    val c = ZillitTheme.colors
    val canPost = state.viewer.canPost
    FixedPage {
        ZillitSectionCard(
            modifier = Modifier.weight(1f),
            title = "Email templates",
            icon = ZillitIcons.File,
            padded = false,
            action = {
                ZillitBadge(count = state.templates.size, background = c.accent, cap = null)
                ZillitButton(
                    text = "New template",
                    onClick = gatedClick(canPost, { onEvent(askPost) }) { onEvent(DocDistEvent.OpenNewTemplate) },
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.Add,
                )
            },
        ) {
            when {
                state.loading && state.templates.isEmpty() ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { ZillitSpinner() }
                state.templates.isEmpty() -> ZillitEmptyState(
                    title = "No templates yet",
                    message = "Save your first email so you don’t have to retype it next time.",
                    icon = ZillitIcons.File,
                )
                else -> ZillitLazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(ZillitTheme.spacing.sm),
                ) {
                    items(state.templates, key = { it.id }) { template ->
                        HoverRow(
                            onClick = gatedClick(canPost, { onEvent(askPost) }) { onEvent(
                                DocDistEvent.OpenEditTemplate(template.id),
                            ) },
                        ) {
                            Column(
                                Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
                            ) {
                                ZillitText(
                                    text = template.name,
                                    style = ZillitTheme.typography.label.copy(fontWeight = FontWeight.SemiBold),
                                    maxLines = 1,
                                )
                                if (template.description.isNotBlank()) ZillitText(
                                    text = template.description,
                                    style = ZillitTheme.typography.bodySmall,
                                    color = c.textSecondary,
                                    maxLines = 1,
                                )
                                if (template.subject.isNotBlank()) ZillitText(
                                    text = "Subject: ${template.subject}",
                                    style = ZillitTheme.typography.bodySmall,
                                    maxLines = 1,
                                )
                                val preview = HtmlText.snippet(template.bodyHtml, PREVIEW_LENGTH)
                                if (preview.isNotBlank()) ZillitText(
                                    text = preview,
                                    style = ZillitTheme.typography.bodySmall,
                                    color = c.textMuted,
                                    maxLines = 1,
                                )
                            }
                            ZillitTooltip(if (canPost) "Edit" else "No posting rights") {
                                ZillitIconButton(
                                    ZillitIcons.Edit,
                                    "Edit ${template.name}",
                                    gatedClick(canPost, { onEvent(askPost) }) { onEvent(
                                        DocDistEvent.OpenEditTemplate(template.id),
                                    ) },
                                )
                            }
                            ZillitTooltip(if (canPost) "Delete" else "No posting rights") {
                                ZillitIconButton(
                                    ZillitIcons.Trash,
                                    "Delete ${template.name}",
                                    gatedClick(canPost, { onEvent(askPost) }) { onEvent(
                                        DocDistEvent.ConfirmDeleteTemplate(template.id),
                                    ) },
                                    tint = c.danger,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The editor — also opened by the composer's "Save current as template". */
@Composable
fun TemplateEditorDialog(state: DocDistUiState, onEvent: (DocDistEvent) -> Unit) {
    val editor = state.templateEditor
    ZillitDialogShell(
        title = if (editor?.isNew == false) "Edit template" else "Save email template",
        visible = editor != null,
        onDismiss = { onEvent(DocDistEvent.CloseTemplateEditor) },
        icon = ZillitIcons.File,
        width = 640.dp,
        actions = {
            ZillitButton(
                text = "Cancel",
                onClick = { onEvent(DocDistEvent.CloseTemplateEditor) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = if (editor?.isNew == false) "Save changes" else "Create template",
                onClick = { onEvent(DocDistEvent.SaveTemplateEditor) },
                enabled = editor?.name?.isNotBlank() == true && !editor.saving,
                loading = editor?.saving == true,
            )
        },
    ) {
        if (editor == null) return@ZillitDialogShell
        FieldLabel("Name *")
        ZillitTextField(value = editor.name, onValueChange = { onEvent(DocDistEvent.EditTemplate(name = it)) })
        FieldLabel("Description")
        ZillitTextField(
            value = editor.description,
            onValueChange = { onEvent(DocDistEvent.EditTemplate(description = it)) },
            placeholder = "Short note about when to use this template",
        )
        FieldLabel("Subject")
        ZillitTextField(
            value = editor.subject,
            onValueChange = { onEvent(DocDistEvent.EditTemplate(subject = it)) },
            placeholder = "Email subject line",
        )
        FieldLabel("Message body")
        MessageBodyField(
            value = editor.body,
            onValueChange = { onEvent(DocDistEvent.EditTemplate(body = it)) },
            placeholder = "Hi, Welcome to the team…",
            minHeight = BODY_MIN_HEIGHT.dp,
        )
    }
}

private const val PREVIEW_LENGTH = 120
private const val BODY_MIN_HEIGHT = 180
