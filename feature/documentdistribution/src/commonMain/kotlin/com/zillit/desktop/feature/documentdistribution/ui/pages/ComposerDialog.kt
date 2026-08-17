package com.zillit.desktop.feature.documentdistribution.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitFileBadge
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitSectionLabel
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.documentdistribution.domain.DistributionList
import com.zillit.desktop.feature.documentdistribution.domain.EmailTemplate
import com.zillit.desktop.feature.documentdistribution.domain.NewDistribution
import com.zillit.desktop.feature.documentdistribution.domain.WatermarkLine
import com.zillit.desktop.feature.documentdistribution.domain.WatermarkSize
import com.zillit.desktop.feature.documentdistribution.ui.ComposerState
import com.zillit.desktop.feature.documentdistribution.ui.DocDistEvent
import com.zillit.desktop.feature.documentdistribution.ui.DocDistUiState

/**
 * The send dialog.
 *
 * ## Watermarking is the point
 *
 * Every watermark-capable attachment arrives here already ticked, and the
 * checkbox is an *opt out*. That is the web's behaviour (ZL-19547) and the
 * reason this tool exists rather than people mailing files from Outlook: a
 * script that leaves the production unstamped cannot be traced when it leaks.
 *
 * ## Validation is the domain's, not the dialog's
 *
 * The Send button's enabled state and the message under it both come from
 * [NewDistribution.validationError], so what the dialog refuses and what the
 * view model refuses cannot drift apart.
 */
@Composable
fun ComposerDialog(state: DocDistUiState, onEvent: (DocDistEvent) -> Unit) {
    val composer = state.composer

    // Built from what is on screen rather than mirroring the view model's own
    // check: one rule set, evaluated in both places, cannot disagree.
    val problem = NewDistribution(
        subject = composer.subject,
        bodyHtml = composer.bodyHtml,
        to = composer.to,
        attachmentIds = composer.attachments.map { it.id },
    ).validationError()

    ZillitDialogShell(
        title = "Distribute documents",
        subtitle = "${composer.attachments.size} attached · " +
            "${composer.to.size} recipient" + if (composer.to.size == 1) "" else "s",
        visible = composer.open,
        onDismiss = { onEvent(DocDistEvent.CloseComposer) },
        icon = ZillitIcons.Send,
        width = DIALOG_WIDTH.dp,
        // Pinned rather than in the body: this dialog grows with every
        // attachment, and buttons that scroll out of a dialog are buttons
        // nobody finds. The reason `actions` exists on the shell at all.
        actions = {
            ZillitButton(
                text = "Cancel",
                onClick = { onEvent(DocDistEvent.CloseComposer) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = "Send",
                onClick = { onEvent(DocDistEvent.Send) },
                leadingIcon = ZillitIcons.Send,
                enabled = problem == null && !composer.sending,
                loading = composer.sending,
            )
        },
    ) {
        RecipientSection(state, onEvent)
        MessageSection(state, onEvent)
        AttachmentSection(state, onEvent)

        problem?.let { ZillitNotice(text = it, tone = StatusTone.Pending, icon = ZillitIcons.Info) }
    }
}

/** Who this is going to: typed addresses, the chips they became, and the lists. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColumnScope.RecipientSection(
    state: DocDistUiState,
    onEvent: (DocDistEvent) -> Unit,
) {
    val composer = state.composer

    ZillitSectionLabel("To")
    ZillitTextField(
        value = composer.recipientDraft,
        onValueChange = { onEvent(DocDistEvent.ComposeRecipientDraft(it)) },
        placeholder = "Type an address and press Enter, or paste several",
        leadingIcon = ZillitIcons.User,
        onImeAction = { onEvent(DocDistEvent.CommitRecipientDraft) },
        imeAction = ImeAction.Done,
    )

    if (composer.to.isNotEmpty()) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        ) {
            composer.to.forEach { recipient ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ZillitStatusPill(
                        label = recipient.name.ifBlank { recipient.email },
                        tone = StatusTone.Neutral,
                    )
                    ZillitIconButton(
                        icon = ZillitIcons.Close,
                        contentDescription = "Remove ${recipient.email}",
                        onClick = { onEvent(DocDistEvent.RemoveRecipient(recipient.email)) },
                    )
                }
            }
        }
    }

    if (state.lists.isNotEmpty()) {
        ZillitSelect(
            value = NO_LIST,
            options = listOf(NO_LIST) + state.lists,
            onSelect = { chosen ->
                if (chosen.id.isNotBlank()) onEvent(DocDistEvent.AddList(chosen.id))
            },
            label = { it.name },
        )
    }
}

/** The subject and body, and the templates that fill them in. */
@Composable
private fun ColumnScope.MessageSection(
    state: DocDistUiState,
    onEvent: (DocDistEvent) -> Unit,
) {
    ZillitSectionLabel("Message")
    if (state.templates.isNotEmpty()) {
        ZillitSelect(
            value = NO_TEMPLATE,
            options = listOf(NO_TEMPLATE) + state.templates,
            onSelect = { chosen ->
                if (chosen.id.isNotBlank()) onEvent(DocDistEvent.ApplyTemplate(chosen.id))
            },
            label = { it.name },
        )
    }
    ZillitTextField(
        value = state.composer.subject,
        onValueChange = { onEvent(DocDistEvent.ComposeSubject(it)) },
        label = "Subject",
    )
    ZillitTextField(
        value = state.composer.bodyHtml,
        onValueChange = { onEvent(DocDistEvent.ComposeBody(it)) },
        label = "Body",
        singleLine = false,
    )
}

/** What is going with it, and which of those carry a stamp. */
@Composable
private fun ColumnScope.AttachmentSection(
    state: DocDistUiState,
    onEvent: (DocDistEvent) -> Unit,
) {
    val composer = state.composer

    ZillitSectionLabel("Attachments")
    composer.attachments.forEach { document ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitFileBadge(fileName = document.name)
            ZillitText(text = document.name, maxLines = 1, modifier = Modifier.weight(1f))
            if (document.isWatermarkable) {
                ZillitCheckbox(
                    checked = document.id in composer.watermarked,
                    onCheckedChange = { onEvent(DocDistEvent.ToggleWatermark(document.id)) },
                    label = "Watermark",
                )
            } else {
                // Said rather than left blank: a sender who assumes every file
                // is stamped needs to know which one is not, and an empty cell
                // reads as "not ticked" rather than "cannot be".
                ZillitText(
                    text = "Cannot be watermarked",
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textMuted,
                )
            }
            ZillitIconButton(
                icon = ZillitIcons.Close,
                contentDescription = "Remove ${document.name}",
                onClick = { onEvent(DocDistEvent.RemoveAttachment(document.id)) },
            )
        }
    }

    if (composer.watermarked.isNotEmpty()) {
        WatermarkControls(composer, onEvent)
    }
}

@Composable
private fun WatermarkControls(composer: ComposerState, onEvent: (DocDistEvent) -> Unit) {
    val watermark = composer.watermark
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
        ZillitSectionLabel("Watermark")
        ZillitText(
            text = "Stamping ${composer.watermarked.size} of " +
                "${composer.attachments.count { it.isWatermarkable }} eligible files · " +
                watermark.summary(),
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textSecondary,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitSelect(
                value = watermark.line1,
                options = listOf(WatermarkLine.RecipientName, WatermarkLine.Custom),
                onSelect = { onEvent(DocDistEvent.SetWatermark(watermark.copy(line1 = it))) },
                label = { line ->
                    if (line == WatermarkLine.RecipientName) "Recipient's name" else "Custom text"
                },
                modifier = Modifier.weight(1f),
            )
            ZillitSelect(
                value = watermark.size,
                options = WatermarkSize.entries,
                onSelect = { onEvent(DocDistEvent.SetWatermark(watermark.copy(size = it))) },
                label = { it.name },
                modifier = Modifier.weight(1f),
            )
        }
        if (watermark.line1 == WatermarkLine.Custom) {
            ZillitTextField(
                value = watermark.line1Custom,
                onValueChange = {
                    onEvent(DocDistEvent.SetWatermark(watermark.copy(line1Custom = it)))
                },
                label = "Stamp text",
                placeholder = "CONFIDENTIAL",
            )
        }
    }
}

/**
 * The "pick one" row in a select that has no null.
 *
 * [ZillitSelect] takes a non-null value, and there is no "unselected" list or
 * template — choosing one is an action, not a state. A sentinel row with a
 * blank id is what turns the picker into a one-shot action without giving the
 * component a nullable value to render.
 */
private val NO_LIST = DistributionList(id = "", name = "Add a distribution list…")
private val NO_TEMPLATE = EmailTemplate(id = "", name = "Apply a template…")

private const val DIALOG_WIDTH = 720
