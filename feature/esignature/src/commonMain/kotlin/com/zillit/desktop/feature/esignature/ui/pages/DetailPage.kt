@file:Suppress("LongMethod", "TooManyFunctions", "CyclomaticComplexMethod", "ComplexCondition")

package com.zillit.desktop.feature.esignature.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.TagTone
import com.zillit.desktop.core.designsystem.component.ZillitAvatar
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitProgressBar
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitTag
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.esignature.domain.AuditEntry
import com.zillit.desktop.feature.esignature.domain.EnvelopeRecipient
import com.zillit.desktop.feature.esignature.domain.EnvelopeStatus
import com.zillit.desktop.feature.esignature.domain.EsignFormat
import com.zillit.desktop.feature.esignature.ui.DetailState
import com.zillit.desktop.feature.esignature.ui.EsignEvent
import com.zillit.desktop.feature.esignature.ui.EsignUiState
import com.zillit.desktop.feature.esignature.ui.SigningMode
import com.zillit.desktop.feature.esignature.ui.components.BlockTitle
import com.zillit.desktop.feature.esignature.ui.components.DocTile
import com.zillit.desktop.feature.esignature.ui.components.EnvelopeStatusPill
import com.zillit.desktop.feature.esignature.ui.components.EsignCard
import com.zillit.desktop.feature.esignature.ui.components.Hairline
import com.zillit.desktop.feature.esignature.ui.components.KeyValue
import com.zillit.desktop.feature.esignature.ui.components.signerColor
import com.zillit.desktop.feature.esignature.ui.key

/**
 * An open envelope — the web's `EnvelopeStatusTracker`: who has done
 * what, the audit trail, reminders, and the way into the signed document.
 */
@Composable
internal fun DetailPage(state: EsignUiState, onEvent: (EsignEvent) -> Unit) {
    val detail = state.detail ?: return
    val colors = ZillitTheme.colors
    Column(Modifier.fillMaxSize()) {
        DetailHeader(detail, onEvent)
        if (detail.loading && detail.envelope.recipients.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { ZillitSpinner() }
        } else {
            ZillitScrollColumn(Modifier.fillMaxSize().background(colors.surfaceSunken)) {
                Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(Modifier.weight(5f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        RecipientActivity(detail, state, onEvent)
                    }
                    Column(Modifier.weight(3f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        ActionsCard(detail, state, onEvent)
                        DetailsCard(detail)
                        FieldsSummary(detail)
                        DocumentCard(detail, onEvent)
                    }
                    if (detail.showAudit) {
                        Column(Modifier.weight(4f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            AuditCard(detail, state, onEvent)
                        }
                    }
                }
            }
        }
    }
    OrderDialog(detail, onEvent)
    VoidDialog(detail, onEvent)
}

@Composable
private fun DetailHeader(detail: DetailState, onEvent: (EsignEvent) -> Unit) {
    val colors = ZillitTheme.colors
    val envelope = detail.envelope
    Column(Modifier.fillMaxWidth().background(colors.surface)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ZillitIconButton(ZillitIcons.ArrowLeft, str(S.docusign_back), onClick = { onEvent(EsignEvent.Back) })
            Column(Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ZillitText(
                        envelope.title.ifBlank { envelope.document?.name ?: str(S.desktop_ds_envelope) },
                        style = ZillitTheme.typography.titleMedium,
                        maxLines = 1,
                    )
                    val terminal = envelope.completedOn ?: envelope.updated
                    when (envelope.status) {
                        EnvelopeStatus.Completed -> EnvelopeStatusPill(
                            envelope.status,
                            "Completed on ${EsignFormat.date(terminal)}",
                        )
                        EnvelopeStatus.Declined, EnvelopeStatus.Rejected -> EnvelopeStatusPill(
                            envelope.status,
                            "Rejected on ${EsignFormat.date(terminal)}",
                        )
                        else -> EnvelopeStatusPill(envelope.status)
                    }
                }
                ZillitText(
                    listOfNotNull(
                        envelope.created?.let { "Created ${EsignFormat.date(it)}" },
                        envelope.sentOn?.let { "Sent ${EsignFormat.date(it)}" },
                        envelope.completedOn?.let { "Completed ${EsignFormat.date(it)}" },
                    ).joinToString(" · ").ifBlank { "—" },
                    style = ZillitTheme.typography.bodySmall,
                    color = colors.textMuted,
                )
            }
            if (detail.canVoid) {
                ZillitButton(
                    str(S.desktop_ds_cancel_envelope),
                    onClick = { onEvent(EsignEvent.StartVoid) },
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                )
            }
            ZillitButton(
                str(S.docusign_refresh),
                onClick = { onEvent(EsignEvent.RefreshDetail) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Reload,
                loading = detail.refreshing,
            )
            ZillitButton(
                str(S.docusign_audit_trail),
                onClick = { onEvent(EsignEvent.ToggleAudit) },
                variant = if (detail.showAudit) ButtonVariant.Primary else ButtonVariant.Secondary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Shield,
            )
        }
        Hairline()
    }
}

@Composable
private fun RecipientActivity(detail: DetailState, state: EsignUiState, onEvent: (EsignEvent) -> Unit) {
    val colors = ZillitTheme.colors
    val envelope = detail.envelope
    val signers = envelope.signers
    val done = envelope.signedCount
    val outstanding = signers.count { it.outstanding }
    EsignCard {
        BlockTitle(str(S.docusign_detail_recipient_activity)) {
            if (envelope.recipients.size > 1) {
                ZillitButton(
                    str(S.desktop_ds_signing_order),
                    onClick = { onEvent(EsignEvent.ToggleOrder) },
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.Hierarchy,
                )
            }
            if (signers.size > 1 && outstanding > 1 && envelope.status.isInFlight && !detail.loading) {
                Spacer(Modifier.width(6.dp))
                val cooling = nowMillis() - detail.remindAllAt < REMIND_COOLDOWN_MS
                ZillitButton(
                    if (cooling) str(S.desktop_ds_reminded) else "Remind $outstanding",
                    onClick = { onEvent(EsignEvent.Remind(null)) },
                    variant = ButtonVariant.Secondary,
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.Bell,
                    enabled = !cooling,
                    loading = "__all__" in detail.reminding,
                )
            }
            if (signers.size > 1) {
                Spacer(Modifier.width(8.dp))
                ZillitText(
                    "$done/${signers.size} signed",
                    style = ZillitTheme.typography.label,
                    color = colors.accentText,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        if (signers.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ZillitText(str(S.desktop_progress), style = ZillitTheme.typography.labelSmall, color = colors.textMuted)
                ZillitProgressBar(
                    fraction = done.toFloat() / signers.size,
                    modifier = Modifier.weight(1f),
                    fillColor = if (done == signers.size) colors.success else colors.accent,
                )
                ZillitText(
                    "${if (signers.isEmpty()) 0 else done * 100 / signers.size}%",
                    style = ZillitTheme.typography.labelSmall,
                    color = colors.textMuted,
                )
            }
            Spacer(Modifier.height(12.dp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            envelope.recipients.sortedWith(compareBy({ it.isCc }, { it.routingOrder })).forEach { recipient ->
                RecipientCard(recipient, envelope.signers.indexOf(recipient), detail, state, onEvent)
            }
        }
    }
}

@Composable
private fun RecipientCard(
    recipient: EnvelopeRecipient,
    signerPosition: Int,
    detail: DetailState,
    state: EsignUiState,
    onEvent: (EsignEvent) -> Unit,
) {
    val colors = ZillitTheme.colors
    val envelope = detail.envelope
    val hue = if (recipient.isCc) colors.info else signerColor(signerPosition)
    val isMe = recipient.userId == state.currentUserId ||
        (state.currentUserEmail.isNotBlank() && recipient.email.equals(state.currentUserEmail, true))
    val tone = when {
        recipient.declined -> StatusTone.Rejected
        recipient.signed -> StatusTone.Done
        recipient.status == "delivered" -> StatusTone.Progress
        recipient.status == "sent" -> StatusTone.Pending
        else -> StatusTone.Neutral
    }
    Column(
        Modifier.fillMaxWidth().clip(ZillitTheme.shapes.medium).background(colors.surfaceSunken)
            .border(1.dp, colors.border, ZillitTheme.shapes.medium).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier.size(34.dp).clip(CircleShape).border(2.dp, hue, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                ZillitAvatar(name = recipient.name.ifBlank { recipient.email }, userId = recipient.userId, size = 28.dp)
            }
            Column(Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    ZillitText(
                        recipient.name.ifBlank { recipient.email } + if (isMe) " (you)" else "",
                        style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 1,
                    )
                    if (recipient.isCc) ZillitTag(str(S.dd_label_cc), tone = TagTone.Info)
                    if (recipient.isExternal) ZillitTag(str(S.docusign_row_external_chip), tone = TagTone.Neutral)
                    if (!recipient.isCc && envelope.settings.signingOrderEnabled) ZillitTag(
                        "#${recipient.routingOrder}",
                        tone = TagTone.Neutral,
                    )
                }
                ZillitText(
                    recipient.email,
                    style = ZillitTheme.typography.labelSmall,
                    color = colors.textMuted,
                    maxLines = 1,
                )
            }
            ZillitStatusPill(
                label = if (recipient.isCc) str(S.copy) else recipient.statusLabel,
                tone = if (recipient.isCc) StatusTone.Neutral else tone,
                dot = !recipient.isCc,
            )
        }
        val stamps = listOfNotNull(
            recipient.viewedOn?.let { "Viewed ${EsignFormat.dateTime(it)}" },
            recipient.acceptedTermsOn?.let { "Accepted terms ${EsignFormat.dateTime(it)}" },
            recipient.signedOn?.let { "Signed ${EsignFormat.dateTime(it)}" },
            recipient.declinedOn?.let { "Declined ${EsignFormat.dateTime(it)}" },
        )
        if (stamps.isNotEmpty()) {
            ZillitText(
                stamps.joinToString(" · "),
                style = ZillitTheme.typography.labelSmall,
                color = colors.textSecondary,
            )
        }
        if (recipient.declined && recipient.declinedReason.isNotBlank()) {
            ZillitText(
                "“${recipient.declinedReason}”",
                style = ZillitTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
                color = colors.danger,
            )
        }
        if (recipient.note.isNotBlank()) {
            ZillitText(
                "Note: ${recipient.note}",
                style = ZillitTheme.typography.labelSmall,
                color = colors.textSecondary,
            )
        }
        if (!recipient.isCc && recipient.outstanding && envelope.status.isInFlight && detail.sentByMe) {
            val since = detail.remindedAt[recipient.id] ?: 0L
            val cooling = nowMillis() - since < REMIND_COOLDOWN_MS
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ZillitButton(
                    if (cooling) str(S.desktop_ds_reminder_sent) else str(S.txt_send_reminder),
                    onClick = { onEvent(EsignEvent.Remind(recipient.id)) },
                    variant = ButtonVariant.Secondary,
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.Bell,
                    enabled = !cooling,
                    loading = recipient.key() in detail.reminding || recipient.id in detail.reminding,
                )
            }
        } else if (recipient.waitsForTurn(envelope.settings.signingOrderEnabled, envelope.status.isInFlight)) {
            ZillitText(
                str(S.desktop_ds_notified_once_the_signer_before_them_completes),
                style = ZillitTheme.typography.labelSmall,
                color = colors.textMuted,
            )
        }
    }
}

@Composable
private fun ActionsCard(detail: DetailState, state: EsignUiState, onEvent: (EsignEvent) -> Unit) {
    val envelope = detail.envelope
    val showSigned = detail.hasSignedValues
    val showSign = detail.canSignNow
    val showDownload = envelope.signedDocument != null && (state.viewer.canDownload || state.viewer.isAdmin)
    if (!showSigned && !showSign && !showDownload) return
    EsignCard {
        BlockTitle(str(S.dd_actions))
        Spacer(Modifier.height(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (showSign) {
                ZillitButton(
                    str(S.docusign_sign_now),
                    onClick = { onEvent(EsignEvent.OpenSigning(envelope, SigningMode.Sign, fromDetail = true)) },
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.Edit,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (showSigned) {
                ZillitButton(
                    str(S.docusign_view_signed_only),
                    onClick = { onEvent(EsignEvent.OpenSigning(envelope, SigningMode.ViewSigned, fromDetail = true)) },
                    size = ButtonSize.Small,
                    variant = if (showSign) ButtonVariant.Secondary else ButtonVariant.Primary,
                    leadingIcon = ZillitIcons.Eye,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (showDownload) {
                ZillitButton(
                    str(S.desktop_ds_download_signed_pdf),
                    onClick = { onEvent(EsignEvent.DownloadSigned) },
                    size = ButtonSize.Small,
                    variant = ButtonVariant.Secondary,
                    leadingIcon = ZillitIcons.Download,
                    loading = detail.downloadingSigned,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun DetailsCard(detail: DetailState) {
    val colors = ZillitTheme.colors
    val envelope = detail.envelope
    EsignCard {
        BlockTitle(str(S.docusign_section_details))
        Spacer(Modifier.height(8.dp))
        if (envelope.description.isNotBlank()) {
            ZillitText(envelope.description, style = ZillitTheme.typography.bodySmall, color = colors.textSecondary)
            Spacer(Modifier.height(8.dp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            envelope.created?.let { KeyValue(str(S.drive_created), EsignFormat.dateTime(it)) }
            envelope.sentOn?.let { KeyValue(str(S.txt_sent), EsignFormat.dateTime(it)) }
            envelope.completedOn?.let { KeyValue(str(S.completed), EsignFormat.dateTime(it)) }
            val expires = envelope.expiresOn
                ?: envelope.settings.expirationDays?.let { days -> envelope.sentOn?.let { it + days * DAY_MS } }
            expires?.let { KeyValue(str(S.drive_link_expires_label), EsignFormat.dateTime(it)) }
            KeyValue(str(S.desktop_ds_signing_order), if (envelope.settings.signingOrderEnabled) {
                str(S.desktop_ds_sequential)
            } else {
                str(S.desktop_ds_all_at_once)
            })
            if (envelope.settings.initialsOnAllPages) {
                KeyValue(str(S.docusign_saved_sig_initials), str(S.desktop_ds_on_every_page))
            }
        }
    }
}

@Composable
private fun FieldsSummary(detail: DetailState) {
    val colors = ZillitTheme.colors
    val envelope = detail.envelope
    if (envelope.fields.isEmpty()) return
    EsignCard {
        BlockTitle(str(S.docusign_send_confirm_fields_label)) {
            ZillitText("${envelope.fields.size}", style = ZillitTheme.typography.labelSmall, color = colors.textMuted)
        }
        Spacer(Modifier.height(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            envelope.signers.forEachIndexed { position, signer ->
                val mine = envelope.fieldsFor(signer)
                if (mine.isEmpty()) return@forEachIndexed
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(signerColor(position)))
                    ZillitText(
                        signer.name.ifBlank { signer.email },
                        style = ZillitTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                    )
                    ZillitText(
                        mine.groupBy { it.type }.entries.joinToString(", ") { (type, list) ->
                            "${list.size} ${type.label.lowercase()}"
                        },
                        style = ZillitTheme.typography.labelSmall,
                        color = colors.textMuted,
                    )
                }
            }
        }
    }
}

@Composable
private fun DocumentCard(detail: DetailState, onEvent: (EsignEvent) -> Unit) {
    val colors = ZillitTheme.colors
    val document = detail.envelope.document ?: return
    EsignCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            DocTile()
            Column(Modifier.weight(1f)) {
                ZillitText(
                    document.name.ifBlank { str(S.docusign_section_document) },
                    style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    maxLines = 1,
                )
                ZillitText(
                    listOfNotNull(
                        EsignFormat.size(document.sizeBytes).takeIf { it.isNotBlank() },
                        document.pageCount.takeIf { it > 0 }?.let { "$it page${if (it == 1) "" else "s"}" },
                    ).joinToString(" · "),
                    style = ZillitTheme.typography.labelSmall,
                    color = colors.textMuted,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        ZillitButton(
            str(S.docusign_view_document),
            onClick = { onEvent(EsignEvent.OpenSigning(detail.envelope, SigningMode.Plain, fromDetail = true)) },
            variant = ButtonVariant.Secondary,
            size = ButtonSize.Small,
            leadingIcon = ZillitIcons.Eye,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun AuditCard(detail: DetailState, state: EsignUiState, onEvent: (EsignEvent) -> Unit) {
    val colors = ZillitTheme.colors
    EsignCard {
        BlockTitle(str(S.docusign_audit_trail)) {
            if (state.viewer.canDownload || state.viewer.isAdmin) {
                ZillitButton(
                    "PDF",
                    onClick = { onEvent(EsignEvent.DownloadAuditPdf) },
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.Download,
                    loading = detail.downloadingAudit,
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        ZillitText(
            str(S.desktop_ds_every_action_on_this_envelope_newest_first_the),
            style = ZillitTheme.typography.labelSmall,
            color = colors.textMuted,
        )
        Spacer(Modifier.height(10.dp))
        when {
            detail.auditLoading && detail.audit.isEmpty() -> ZillitSpinner()
            detail.audit.isEmpty() -> ZillitText(
                str(S.docusign_audit_no_events),
                style = ZillitTheme.typography.bodySmall,
                color = colors.textMuted,
            )
            else -> Column {
                detail.audit.forEachIndexed { index, entry ->
                    AuditRow(entry, last = index == detail.audit.lastIndex)
                }
            }
        }
    }
}

@Composable
private fun AuditRow(entry: AuditEntry, last: Boolean) {
    val colors = ZillitTheme.colors
    val tone = when {
        entry.action.contains("declin") || entry.action.contains("void") -> colors.danger
        entry.action.contains("sign") || entry.action.contains("complet") -> colors.success
        entry.action.contains("view") || entry.action.contains("deliver") -> colors.info
        else -> colors.accent
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(tone).border(2.dp, colors.surface, CircleShape))
            if (!last) Box(Modifier.width(1.dp).height(34.dp).background(colors.border))
        }
        Column(Modifier.weight(1f).padding(bottom = if (last) 0.dp else 8.dp)) {
            ZillitText(
                entry.actionLabel.ifBlank { str(S.history_target_event) },
                style = ZillitTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
            )
            ZillitText(
                listOfNotNull(
                    entry.actorName.ifBlank { entry.actorEmail }.takeIf { it.isNotBlank() },
                    EsignFormat.dateTime(entry.happenedOn).takeIf { entry.happenedOn != null },
                ).joinToString(" · "),
                style = ZillitTheme.typography.labelSmall,
                color = colors.textMuted,
            )
            if (entry.details.isNotBlank()) {
                ZillitText(entry.details, style = ZillitTheme.typography.labelSmall, color = colors.textSecondary)
            }
        }
    }
}

@Composable
private fun OrderDialog(detail: DetailState, onEvent: (EsignEvent) -> Unit) {
    val colors = ZillitTheme.colors
    val envelope = detail.envelope
    ZillitDialogShell(
        title = str(S.desktop_ds_signing_order),
        visible = detail.showOrder,
        onDismiss = { onEvent(EsignEvent.ToggleOrder) },
        scrollable = false,
        icon = ZillitIcons.Hierarchy,
        width = 460.dp,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ZillitText(
                if (envelope.settings.signingOrderEnabled) {
                    str(S.desktop_ds_sequential_order_hint)
                } else {
                    str(S.desktop_ds_all_signers_are_notified_at_once_no_set)
                },
                style = ZillitTheme.typography.bodySmall,
                color = colors.textSecondary,
            )
            envelope.signers.sortedBy { it.routingOrder }.forEachIndexed { i, s ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        Modifier.size(24.dp).clip(CircleShape).background(signerColor(i)),
                        contentAlignment = Alignment.Center,
                    ) {
                        ZillitText("${i + 1}", style = ZillitTheme.typography.labelSmall, color = Color.White)
                    }
                    ZillitText(
                        s.name.ifBlank { s.email },
                        style = ZillitTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    ZillitStatusPill(
                        label = s.statusLabel,
                        tone = if (s.signed) {
                            StatusTone.Done
                        } else {
                            if (s.declined) StatusTone.Rejected else StatusTone.Pending
                        },
                    )
                }
            }
            if (envelope.ccs.isNotEmpty()) {
                Hairline()
                ZillitText(
                    "Copied on completion: ${envelope.ccs.joinToString { it.name.ifBlank { it.email } }}",
                    style = ZillitTheme.typography.bodySmall,
                    color = colors.textMuted,
                )
            }
        }
    }
}

@Composable
private fun VoidDialog(detail: DetailState, onEvent: (EsignEvent) -> Unit) {
    ZillitDialogShell(
        title = str(S.desktop_ds_cancel_this_envelope),
        subtitle = detail.envelope.title.takeIf { it.isNotBlank() },
        icon = ZillitIcons.Warning,
        visible = detail.voiding,
        onDismiss = { onEvent(EsignEvent.CancelVoid) },
        scrollable = false,
        width = 460.dp,
        actions = {
            ZillitButton(
                str(S.desktop_ds_keep_it),
                onClick = { onEvent(EsignEvent.CancelVoid) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
            )
            ZillitButton(
                str(S.desktop_ds_cancel_envelope),
                onClick = { onEvent(EsignEvent.ConfirmVoid) },
                variant = ButtonVariant.Danger,
                size = ButtonSize.Small,
                enabled = detail.voidReason.isNotBlank(),
            )
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ZillitText(
                str(S.desktop_ds_cancel_envelope_body),
                style = ZillitTheme.typography.bodyMedium,
            )
            ZillitTextField(
                value = detail.voidReason,
                onValueChange = { onEvent(EsignEvent.EditVoidReason(it)) },
                label = str(S.reason),
                helperText = str(S.desktop_ds_the_recipients_are_given_this_and_the_trail),
                singleLine = false,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Read at composition — the cooldown label settles on the next refresh, which is soon enough. */
private fun nowMillis(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()

/** Not notified yet: sequential signing, and the signer before them has not finished. */
private fun EnvelopeRecipient.waitsForTurn(sequential: Boolean, inFlight: Boolean): Boolean =
    !isCc && status == "created" && sequential && inFlight

private const val REMIND_COOLDOWN_MS = 60_000L
private const val DAY_MS = 24L * 60 * 60 * 1000
