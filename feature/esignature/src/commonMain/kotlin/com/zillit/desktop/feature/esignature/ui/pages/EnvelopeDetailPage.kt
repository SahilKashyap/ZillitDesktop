package com.zillit.desktop.feature.esignature.ui.pages

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitPageHeader
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitSectionLabel
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.component.zillitVerticalScroll
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.esignature.domain.EnvelopeField
import com.zillit.desktop.feature.esignature.domain.EnvelopeStatus
import com.zillit.desktop.feature.esignature.domain.EsignPage
import com.zillit.desktop.feature.esignature.domain.FieldAnswer
import com.zillit.desktop.feature.esignature.domain.FieldType
import com.zillit.desktop.feature.esignature.ui.EnvelopeDetailState
import com.zillit.desktop.feature.esignature.ui.EsignEvent
import com.zillit.desktop.feature.esignature.ui.EsignUiState
import com.zillit.desktop.feature.esignature.ui.decodeImageBitmap
import com.zillit.desktop.feature.esignature.ui.tone

/**
 * An open envelope: the tracker for everyone, and the signing surface when
 * fields are waiting on the current user.
 *
 * The consent step mirrors the web: fields stay hidden behind "Accept
 * digital signature?" until the signer says yes.
 */
@Composable
internal fun EnvelopeDetailPage(
    state: EsignUiState,
    detail: EnvelopeDetailState,
    onEvent: (EsignEvent) -> Unit,
) {
    val envelope = detail.envelope

    ZillitPageHeader(
        eyebrow = "E-Signature",
        title = envelope.title.ifBlank { envelope.document?.name ?: "Envelope" },
        actions = {
            ZillitButton(
                text = "Back",
                onClick = { onEvent(EsignEvent.CloseDetail) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.ArrowLeft,
            )
            if (detail.myFields.isNotEmpty() && detail.consented) {
                ZillitButton(
                    text = "Decline",
                    onClick = { onEvent(EsignEvent.StartDecline) },
                    variant = ButtonVariant.Secondary,
                    size = ButtonSize.Small,
                )
                ZillitButton(
                    text = "Sign envelope",
                    onClick = { onEvent(EsignEvent.SignEnvelope) },
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.Edit,
                    enabled = detail.canSignNow,
                    loading = detail.signing,
                )
            }
        },
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
    ) {
        Column(
            modifier = Modifier.weight(1f).zillitVerticalScroll(),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ConsentGate(detail, onEvent)
            when {
                detail.notPdf -> ZillitNotice(
                    text = "This envelope's document could not be previewed as a PDF.",
                    tone = StatusTone.Neutral,
                    icon = ZillitIcons.Warning,
                )

                detail.loadingPages -> ZillitSpinner()

                else -> detail.pages.forEach { page ->
                    EnvelopePageImage(page, detail, onEvent)
                }
            }
        }
        TrackerRail(state, detail, onEvent)
    }

    DeclineDialog(detail, onEvent)
}

@Composable
private fun ConsentGate(detail: EnvelopeDetailState, onEvent: (EsignEvent) -> Unit) {
    if (detail.myFields.isEmpty() || detail.consented) return
    ZillitNotice(
        text = "This envelope has ${detail.myFields.size} field(s) for you. Accept " +
            "digital signature to review and sign.",
        tone = StatusTone.Pending,
        icon = ZillitIcons.Info,
        action = {
            ZillitButton(
                text = "Yes, I accept",
                onClick = { onEvent(EsignEvent.Consent) },
                size = ButtonSize.Small,
            )
        },
    )
}

/** One page with this signer's fields overlaid; typed fields edit in place. */
@Composable
private fun EnvelopePageImage(
    page: EsignPage,
    detail: EnvelopeDetailState,
    onEvent: (EsignEvent) -> Unit,
) {
    val bitmap = remember(page.page, page.imageBytes.size) { decodeImageBitmap(page.imageBytes) }
        ?: return
    val density = LocalDensity.current
    val widthDp = with(density) { page.widthPx.toDp() }
    val heightDp = with(density) { page.heightPx.toDp() }
    val fields = detail.myFields.filter { it.page == page.page }

    Box(
        modifier = Modifier
            .width(widthDp)
            .border(1.dp, ZillitTheme.colors.border)
            .background(Color.White),
    ) {
        Image(
            bitmap = bitmap,
            contentDescription = "Page ${page.page}",
            modifier = Modifier.size(widthDp, heightDp),
            contentScale = ContentScale.FillBounds,
        )
        if (detail.consented) {
            fields.forEach { field -> FieldOverlay(page, field, detail, onEvent) }
        }
    }
}

@Composable
private fun FieldOverlay(
    page: EsignPage,
    field: EnvelopeField,
    detail: EnvelopeDetailState,
    onEvent: (EsignEvent) -> Unit,
) {
    val density = LocalDensity.current
    val rect = page.pixelRect(field)
    val x = with(density) { rect[0].toDp() }
    val y = with(density) { rect[1].toDp() }
    val w = with(density) { rect[2].toDp() }
    val h = with(density) { rect[3].toDp() }
    val answer = detail.answers[field.id]

    Box(
        modifier = Modifier
            .offset(x = x, y = y)
            .size(w, h)
            .background(FIELD_FILL)
            .border(1.dp, FIELD_EDGE)
            .let { base ->
                if (field.type == FieldType.Checkbox) {
                    base.clickable {
                        val now = (answer as? FieldAnswer.Ticked)?.checked ?: false
                        onEvent(EsignEvent.Answer(field.id, FieldAnswer.Ticked(!now)))
                    }
                } else {
                    base
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        when {
            field.type == FieldType.Checkbox -> ZillitText(
                text = if ((answer as? FieldAnswer.Ticked)?.checked == true) "☑" else "☐",
            )

            field.type.isTyped -> ZillitText(
                text = (answer as? FieldAnswer.Typed)?.text
                    ?.ifBlank { field.type.label } ?: field.type.label,
                style = ZillitTheme.typography.bodySmall,
                color = FIELD_EDGE,
            )

            else -> ZillitText(
                text = field.type.label,
                style = ZillitTheme.typography.bodySmall,
                color = FIELD_EDGE,
            )
        }
    }
}

/** Status, recipients, typed-field inputs and the audit trail. */
@Composable
private fun TrackerRail(
    state: EsignUiState,
    detail: EnvelopeDetailState,
    onEvent: (EsignEvent) -> Unit,
) {
    val envelope = detail.envelope
    Column(
        modifier = Modifier.width(RAIL_WIDTH.dp).zillitVerticalScroll(),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
    ) {
        ZillitSectionCard(title = "Status", icon = ZillitIcons.Info) {
            ZillitStatusPill(
                label = envelope.status.label.ifBlank { "—" },
                tone = envelope.status.tone(),
            )
        }

        RecipientsCard(state, detail, onEvent)

        TypedFieldInputs(detail, onEvent)

        if (detail.audit.isNotEmpty()) {
            ZillitSectionCard(title = "Audit trail", icon = ZillitIcons.Clock) {
                Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
                    detail.audit.forEach { entry ->
                        ZillitText(
                            text = listOf(entry.action, entry.actorName)
                                .filter { it.isNotBlank() }
                                .joinToString(" — "),
                            style = ZillitTheme.typography.bodySmall,
                            color = ZillitTheme.colors.textSecondary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RecipientsCard(
    state: EsignUiState,
    detail: EnvelopeDetailState,
    onEvent: (EsignEvent) -> Unit,
) {
    val envelope = detail.envelope
    ZillitSectionCard(title = "Recipients", icon = ZillitIcons.Users) {
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            envelope.recipients.forEach { recipient ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        ZillitText(text = recipient.name.ifBlank { recipient.email }, maxLines = 1)
                        ZillitText(
                            text = recipient.statusLabel,
                            style = ZillitTheme.typography.bodySmall,
                            color = ZillitTheme.colors.textSecondary,
                        )
                    }
                    val outstanding = !recipient.signed && !recipient.declined
                    if (
                        outstanding && state.viewer.canPost &&
                        envelope.status == EnvelopeStatus.Sent
                    ) {
                        ZillitButton(
                            text = "Remind",
                            onClick = { onEvent(EsignEvent.Remind(envelope.id, recipient.id)) },
                            variant = ButtonVariant.Tertiary,
                            size = ButtonSize.Small,
                        )
                    }
                }
            }
        }
    }
}

/** The typed fields waiting on this signer, edited in the rail. */
@Composable
private fun TypedFieldInputs(detail: EnvelopeDetailState, onEvent: (EsignEvent) -> Unit) {
    val typed = detail.myFields.filter { it.type.isTyped }
    val boxes = detail.myFields.filter { it.type == FieldType.Checkbox }
    if (!detail.consented || (typed.isEmpty() && boxes.isEmpty())) return

    ZillitSectionCard(title = "Your fields", icon = ZillitIcons.Edit) {
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            ZillitSectionLabel("FILL BEFORE SIGNING")
            typed.forEach { field ->
                ZillitTextField(
                    value = (detail.answers[field.id] as? FieldAnswer.Typed)?.text.orEmpty(),
                    onValueChange = {
                        onEvent(EsignEvent.Answer(field.id, FieldAnswer.Typed(it)))
                    },
                    label = field.label.ifBlank { field.type.label } + " (page ${field.page})",
                )
            }
            boxes.forEach { field ->
                ZillitCheckbox(
                    checked = (detail.answers[field.id] as? FieldAnswer.Ticked)?.checked == true,
                    onCheckedChange = {
                        onEvent(EsignEvent.Answer(field.id, FieldAnswer.Ticked(it)))
                    },
                    label = field.label.ifBlank { "Checkbox" } + " (page ${field.page})",
                )
            }
        }
    }
}

@Composable
private fun DeclineDialog(detail: EnvelopeDetailState, onEvent: (EsignEvent) -> Unit) {
    ZillitDialogShell(
        title = "Reject this document?",
        visible = detail.declining,
        onDismiss = { onEvent(EsignEvent.CancelDecline) },
        icon = ZillitIcons.Warning,
        actions = {
            ZillitButton(
                text = "Yes, reject",
                onClick = { onEvent(EsignEvent.ConfirmDecline) },
                size = ButtonSize.Small,
            )
        },
    ) {
        ZillitTextField(
            value = detail.declineReason,
            onValueChange = { onEvent(EsignEvent.EditDeclineReason(it)) },
            label = "Reason",
            placeholder = "Tell the sender why you’re declining…",
        )
    }
}

private val FIELD_FILL = Color(0x332B6BD8)
private val FIELD_EDGE = Color(0xFF2B6BD8)
private const val RAIL_WIDTH = 320
