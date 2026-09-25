package com.zillit.desktop.feature.documentdistribution.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.common.EpochDate
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.documentdistribution.domain.WatermarkSettings
import com.zillit.desktop.feature.documentdistribution.domain.describe
import com.zillit.desktop.feature.documentdistribution.domain.isStandardAppearance
import com.zillit.desktop.feature.documentdistribution.domain.sameAppearanceAs
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.documentdistribution.domain.Contact
import com.zillit.desktop.feature.documentdistribution.domain.Recipient
import com.zillit.desktop.feature.documentdistribution.domain.WatermarkLine
import com.zillit.desktop.feature.documentdistribution.domain.WatermarkStyle
import com.zillit.desktop.core.designsystem.component.ZillitAvatar
import com.zillit.desktop.core.designsystem.component.ZillitChoiceChip
import com.zillit.desktop.feature.documentdistribution.domain.WATERMARK_SUPPORTED_LABEL
import com.zillit.desktop.feature.documentdistribution.domain.formatBytes
import com.zillit.desktop.feature.documentdistribution.ui.DocDistEvent
import com.zillit.desktop.feature.documentdistribution.ui.DocDistUiState
import com.zillit.desktop.feature.documentdistribution.ui.PickerPurpose

/** Single-document "Download with watermark" — the web's `WatermarkDownloadModal`. */
@Suppress("LongMethod") // One screen section; splitting it separates each control from its state.
@Composable
internal fun WatermarkDownloadDialog(state: DocDistUiState, onEvent: (DocDistEvent) -> Unit) {
    val open = state.watermarkDownload
    val supported = open?.document?.isWatermarkable == true
    ZillitDialogShell(
        title = "Download with watermark",
        subtitle = open?.document?.name,
        visible = open != null,
        onDismiss = { onEvent(DocDistEvent.CloseWatermarkDownload) },
        icon = ZillitIcons.Shield,
        width = if (supported) 880.dp else 520.dp,
        actions = {
            ZillitButton(
                text = "Cancel",
                onClick = { onEvent(DocDistEvent.CloseWatermarkDownload) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = "Download",
                onClick = { onEvent(DocDistEvent.ConfirmWatermarkDownload) },
                enabled = supported && open?.downloading == false,
                loading = open?.downloading == true,
                leadingIcon = ZillitIcons.Download,
            )
        },
    ) {
        if (open == null) return@ZillitDialogShell
        if (!supported) {
            ZillitNotice(
                text = "This file type can’t be watermarked. " +
                    "Watermarking is currently supported for $WATERMARK_SUPPORTED_LABEL.",
                tone = StatusTone.Pending,
                icon = ZillitIcons.Warning,
            )
            return@ZillitDialogShell
        }
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xl)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
                FieldLabel("Line 1")
                ZillitTextField(
                    value = open.line1,
                    onValueChange = { onEvent(DocDistEvent.EditWatermarkLine1(it)) },
                    placeholder = "CONFIDENTIAL",
                    maxLength = STAMP_MAX,
                    onImeAction = { onEvent(DocDistEvent.ConfirmWatermarkDownload) },
                )
                FieldLabel("Line 2 (optional)")
                ZillitTextField(
                    value = open.line2,
                    onValueChange = { onEvent(DocDistEvent.EditWatermarkLine2(it)) },
                    placeholder = "e.g. DO NOT DISTRIBUTE",
                    maxLength = STAMP_MAX,
                    onImeAction = { onEvent(DocDistEvent.ConfirmWatermarkDownload) },
                )
                ZillitText(
                    text = "Stamped diagonally on every page of PDFs, centred on images. " +
                        "Line 2 sits below line 1 in a smaller font.",
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textMuted,
                )
                ZillitButton(
                    text = "Use full confidential notice",
                    onClick = {
                        onEvent(DocDistEvent.EditWatermarkLine1("CONFIDENTIAL"))
                        onEvent(DocDistEvent.EditWatermarkLine2("DO NOT DISTRIBUTE"))
                    },
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                )
                FieldLabel("Appearance")
                WatermarkStyleControls(open.style, state.loadedWatermarkDefaults) {
                    onEvent(DocDistEvent.EditWatermarkDownloadStyle(it))
                }
            }
            WatermarkPreview(
                style = open.style,
                text = open.stampText,
                image = open.previewImage,
                loading = open.previewLoading,
                modifier = Modifier.width(PREVIEW_WIDTH.dp),
            )
        }
    }
}

/**
 * The three-step "watermark and download a zip": pick documents, configure
 * the two-line stamp, choose recipients; one folder per recipient comes back.
 */
@Suppress("LongMethod") // One screen section; splitting it separates each control from its state.
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WatermarkBatchDialog(state: DocDistUiState, onEvent: (DocDistEvent) -> Unit) {
    val batch = state.watermarkBatch
    val c = ZillitTheme.colors
    ZillitDialogShell(
        title = "Watermark & download",
        subtitle = "One personalised, stamped copy of each file per recipient, zipped",
        visible = batch != null,
        onDismiss = { onEvent(DocDistEvent.CloseWatermarkBatch) },
        icon = ZillitIcons.Shield,
        width = 920.dp,
        actions = {
            ZillitButton(
                text = "Cancel",
                onClick = { onEvent(DocDistEvent.CloseWatermarkBatch) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = "Download zip",
                onClick = { onEvent(DocDistEvent.ConfirmWatermarkBatch) },
                enabled = batch?.canDownload == true,
                loading = batch?.downloading == true,
                leadingIcon = ZillitIcons.Download,
            )
        },
    ) {
        if (batch == null) return@ZillitDialogShell

        StepHeading(1, "Documents", "${batch.documents.size} selected") {
            ZillitButton(
                text = "Choose from library",
                onClick = { onEvent(DocDistEvent.OpenPicker(PickerPurpose.Batch)) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Paperclip,
            )
        }
        if (batch.documents.isEmpty()) {
            ZillitText(
                text = "No documents yet — only $WATERMARK_SUPPORTED_LABEL can be stamped.",
                color = c.textMuted,
            )
        } else {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            ) {
                batch.documents.forEach { document ->
                    Chip(label = "${document.name} · ${formatBytes(document.sizeBytes)}") { onEvent(
                        DocDistEvent.RemoveBatchDocument(document.id),
                    ) }
                }
            }
        }

        StepHeading(2, "Watermark", batch.style.summary())
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xl)) {
            Column(Modifier.weight(1f)) {
                WatermarkLinesEditor(batch.style) { onEvent(DocDistEvent.EditBatchStyle(it)) }
                WatermarkStyleControls(batch.style, state.loadedWatermarkDefaults) {
                    onEvent(DocDistEvent.EditBatchStyle(it))
                }
            }
            WatermarkPreview(
                style = batch.style,
                text = batch.style.render(listOf(Recipient(email = "", name = "Recipient Name"))),
                modifier = Modifier.width(PREVIEW_WIDTH.dp),
            )
        }

        StepHeading(3, "Recipients", "${batch.recipients.size} added") {
            Box {
                ZillitButton(
                    text = "Add a distribution list",
                    onClick = { onEvent(DocDistEvent.BatchListMenu(true)) },
                    variant = ButtonVariant.Secondary,
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.Users,
                    enabled = state.lists.isNotEmpty(),
                )
                MenuPopup(
                    expanded = batch.listMenuOpen,
                    onDismiss = { onEvent(DocDistEvent.BatchListMenu(false)) },
                    entries = state.lists.map { list ->
                        MenuEntry(
                            "${list.name} (${list.recipients.size})",
                            { onEvent(DocDistEvent.AddBatchList(list.id)) },
                            ZillitIcons.Users,
                        )
                    },
                )
            }
        }
        RecipientTypeahead(
            input = batch.recipientInput,
            contacts = state.contacts.notIn(batch.recipients),
            onInput = { onEvent(DocDistEvent.EditBatchRecipientInput(it)) },
            onAddTyped = { onEvent(DocDistEvent.AddBatchRecipient) },
            onPick = { onEvent(DocDistEvent.AddBatchRecipientFromContact(it)) },
            placeholder = "Name <email> or email — press Enter to add",
        )
        if (batch.recipients.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            ) {
                batch.recipients.forEach { r ->
                    Chip(label = if (r.name.isBlank()) r.email else "${r.name} · ${r.email}") { onEvent(
                        DocDistEvent.RemoveBatchRecipient(r.email),
                    ) }
                }
            }
        }
        ZillitNotice(
            text = "The zip holds one folder per recipient, each file stamped with that person’s name. " +
                "Each recipient’s copy is traceable to them.",
            tone = StatusTone.Neutral,
            icon = ZillitIcons.Info,
        )
    }
}

/** "① Documents · 3 selected" with an optional trailing control. */
/**
 * The project's Watermark settings — the web's `WatermarkSettingsModal`, and
 * the only place the project's Size / Colour / Opacity are saved. Every
 * watermark starts from them; a change made while sending stays with that send.
 */
@Suppress("LongMethod") // One dialog; splitting it separates each control from its state.
@Composable
internal fun WatermarkSettingsDialog(state: DocDistUiState, onEvent: (DocDistEvent) -> Unit) {
    val open = state.watermarkSettings
    val c = ZillitTheme.colors
    val canPost = state.viewer.canPost
    val loaded = state.watermarkDefaultsLoaded
    val unchanged = open?.draft?.sameAppearanceAs(state.watermarkDefaults) != false
    ZillitDialogShell(
        title = str(S.dd_action_watermark_settings),
        visible = open != null,
        onDismiss = { onEvent(DocDistEvent.CloseWatermarkSettings) },
        icon = ZillitIcons.Shield,
        width = 860.dp,
        actions = {
            ZillitButton(
                text = str(S.dd_watermark_settings_reset),
                onClick = { onEvent(DocDistEvent.ResetWatermarkSettings) },
                variant = ButtonVariant.Tertiary,
                enabled = canPost && open?.draft?.isStandardAppearance == false,
            )
            Box(Modifier.weight(1f))
            ZillitButton(
                text = str(S.cancel),
                onClick = { onEvent(DocDistEvent.CloseWatermarkSettings) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = str(S.dd_watermark_settings_save),
                onClick = { onEvent(DocDistEvent.SaveWatermarkSettings) },
                enabled = canPost && loaded && !unchanged && open?.saving == false,
                loading = open?.saving == true,
            )
        },
    ) {
        if (open == null) return@ZillitDialogShell
        ZillitText(
            text = str(S.dd_watermark_settings_explanation),
            style = ZillitTheme.typography.bodyMedium,
            color = c.textSecondary,
        )
        if (!loaded) {
            Box(Modifier.fillMaxWidth().heightIn(min = 240.dp), contentAlignment = Alignment.Center) {
                ZillitSpinner()
            }
            return@ZillitDialogShell
        }
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xl)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
                FieldLabel("Appearance")
                WatermarkStyleControls(open.draft) { onEvent(DocDistEvent.EditWatermarkSettings(it)) }
                ZillitText(
                    text = settingsStatus(state),
                    style = ZillitTheme.typography.bodySmall,
                    color = c.textSecondary,
                )
                if (!unchanged) ZillitText(
                    text = str(S.dd_watermark_settings_unsaved),
                    style = ZillitTheme.typography.bodySmall,
                    color = c.warning,
                )
                if (!canPost) ZillitText(
                    text = str(S.dd_watermark_settings_no_rights),
                    style = ZillitTheme.typography.bodySmall,
                    color = c.warning,
                )
            }
            WatermarkPreview(
                style = open.draft,
                text = str(S.dd_watermark_settings_preview_text),
                modifier = Modifier.width(PREVIEW_WIDTH.dp),
            )
        }
    }
}

/** "Saved for this project: Large · #6b7280 · 40%. Last changed by … on …." */
private fun settingsStatus(state: DocDistUiState): String {
    val saved = state.watermarkDefaults
    if (saved.isDefault) {
        return str(S.dd_watermark_settings_status_standard, WatermarkSettings.BuiltIn.describe())
    }
    val status = str(S.dd_watermark_settings_status_saved, saved.describe())
    if (saved.updated <= 0) return status
    val at = EpochDate.dateTime(saved.updated)
    val changed = state.crewName(saved.updatedBy)
        ?.let { name -> str(S.dd_watermark_settings_last_changed_by, name, at) }
        ?: str(S.dd_watermark_settings_last_changed, at)
    return "$status $changed"
}

@Composable
internal fun StepHeading(n: Int, title: String, meta: String, trailing: (@Composable () -> Unit)? = null) {
    val c = ZillitTheme.colors
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        Box(Modifier.size(24.dp).clip(CircleShape).background(c.accent), contentAlignment = Alignment.Center) {
            ZillitText(
                text = n.toString(),
                style = ZillitTheme.typography.label.copy(fontWeight = FontWeight.Bold),
                color = c.textOnAccent,
            )
        }
        ZillitText(text = title, style = ZillitTheme.typography.titleSmall)
        ZillitText(
            text = "· $meta",
            style = ZillitTheme.typography.bodySmall,
            color = c.textMuted,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}

/** A removable chip. */
@Composable
internal fun Chip(label: String, tone: StatusTone = StatusTone.Neutral, onRemove: (() -> Unit)?) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
    ) {
        ZillitStatusPill(label = label, tone = tone)
        if (onRemove != null) ZillitIconButton(
            icon = ZillitIcons.Close,
            contentDescription = "Remove $label",
            onClick = onRemove,
            size = 20.dp,
        )
    }
}

/** Line 1 / line 2 of the stamp: recipient name or custom text — the wizard's top half. */
@Suppress("LongMethod") // One screen section; splitting it separates each control from its state.
@Composable
internal fun WatermarkLinesEditor(
    value: WatermarkStyle,
    onChange: (WatermarkStyle) -> Unit,
) {
    val c = ZillitTheme.colors
    Column(
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        modifier = Modifier.padding(bottom = ZillitTheme.spacing.md),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
            FieldLabel("Line 1")
            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
                ZillitChoiceChip(
                    label = "Recipient name",
                    selected = value.line1 == WatermarkLine.RecipientName,
                    onClick = { onChange(value.copy(line1 = WatermarkLine.RecipientName)) },
                )
                ZillitChoiceChip(
                    label = "Custom",
                    selected = value.line1 == WatermarkLine.Custom,
                    onClick = { onChange(value.copy(line1 = WatermarkLine.Custom)) },
                )
            }
            if (value.line1 == WatermarkLine.Custom) {
                ZillitTextField(
                    value = value.line1Custom,
                    onValueChange = { onChange(value.copy(line1Custom = it)) },
                    placeholder = "Type the line 1 text",
                    maxLength = STAMP_MAX,
                )
            } else {
                ZillitText(
                    text = "Shows the recipient’s name when sending to one person, " +
                        "or a count (e.g. \"3 RECIPIENTS\") when sending to several.",
                    style = ZillitTheme.typography.bodySmall,
                    color = c.textMuted,
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
            FieldLabel("Line 2")
            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
                ZillitChoiceChip(
                    label = "None",
                    selected = value.line2 == WatermarkLine.None,
                    onClick = { onChange(value.copy(line2 = WatermarkLine.None)) },
                )
                ZillitChoiceChip(
                    label = "Custom",
                    selected = value.line2 == WatermarkLine.Custom,
                    onClick = { onChange(value.copy(line2 = WatermarkLine.Custom)) },
                )
            }
            if (value.line2 == WatermarkLine.Custom) {
                ZillitTextField(
                    value = value.line2Custom,
                    onValueChange = { onChange(value.copy(line2Custom = it)) },
                    placeholder = "e.g. CONFIDENTIAL — DO NOT DISTRIBUTE",
                    maxLength = STAMP_MAX,
                )
            }
        }
    }
}

/**
 * An address box with the address book beneath it as you type: pick a
 * contact, or press Enter to add what was typed.
 */
@Composable
internal fun RecipientTypeahead(
    input: String,
    contacts: List<Contact>,
    onInput: (String) -> Unit,
    onAddTyped: () -> Unit,
    onPick: (String) -> Unit,
    placeholder: String,
) {
    val c = ZillitTheme.colors
    val q = input.trim().lowercase()
    val matches = if (q.isEmpty()) emptyList() else contacts
        .filter { it.email.lowercase().contains(q) || it.name.lowercase().contains(q) }
        .sortedBy { it.displayName.lowercase() }
        .take(TYPEAHEAD_LIMIT)
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitTextField(
                value = input,
                onValueChange = onInput,
                placeholder = placeholder,
                leadingIcon = ZillitIcons.User,
                onImeAction = onAddTyped,
                modifier = Modifier.weight(1f),
            )
            ZillitButton(
                text = "Add",
                onClick = onAddTyped,
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Add,
            )
        }
        if (matches.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = TYPEAHEAD_HEIGHT.dp)
                    .clip(ZillitTheme.shapes.medium)
                    .background(c.surfaceRaised)
                    .border(0.5.dp, c.border, ZillitTheme.shapes.medium)
                    .padding(ZillitTheme.spacing.xs),
            ) {
                matches.forEach { contact ->
                    HoverRow(onClick = { onPick(contact.email) }, padding = ZillitTheme.spacing.sm) {
                        ZillitAvatar(name = contact.displayName, size = 24.dp)
                        Column {
                            ZillitText(text = contact.displayName, style = ZillitTheme.typography.label, maxLines = 1)
                            if (contact.name.isNotBlank()) {
                                ZillitText(
                                    text = contact.email,
                                    style = ZillitTheme.typography.bodySmall,
                                    color = c.textMuted,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private const val STAMP_MAX = 60
private const val PREVIEW_WIDTH = 340
private const val TYPEAHEAD_LIMIT = 8
private const val TYPEAHEAD_HEIGHT = 240
