package com.zillit.desktop.feature.bankrec.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.bankrec.domain.BankPeriod
import com.zillit.desktop.feature.bankrec.domain.BankRecFormat
import com.zillit.desktop.feature.bankrec.domain.PortalLink
import com.zillit.desktop.feature.bankrec.ui.BankRecEvent
import com.zillit.desktop.feature.bankrec.ui.BankRecUiState
import com.zillit.desktop.feature.bankrec.ui.components.BankRecIcons
import com.zillit.desktop.feature.bankrec.ui.components.BrAlign
import com.zillit.desktop.feature.bankrec.ui.components.BrBadge
import com.zillit.desktop.feature.bankrec.ui.components.BrCard
import com.zillit.desktop.feature.bankrec.ui.components.BrColumn
import com.zillit.desktop.feature.bankrec.ui.components.BrSkeletonRows
import com.zillit.desktop.feature.bankrec.ui.components.BrTable
import com.zillit.desktop.feature.bankrec.ui.components.BrTone
import com.zillit.desktop.feature.bankrec.ui.components.mono

/**
 * Read-only reconciliation summaries for guarantors, broadcasters and auditors.
 *
 * The preview comes first, for the period chosen at the top, so the accountant
 * sees exactly what a link would show before generating one; the links already
 * issued sit under it with their views and what each may see.
 */
@Suppress("LongMethod") // The period choice, the preview and the links, as the web lays them out.
@Composable
fun ColumnScope.PortalPage(state: BankRecUiState, onEvent: (BankRecEvent) -> Unit) {
    val portal = state.portal
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ZillitText(
            str(S.desktop_br_portal_intro),
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textSecondary,
            modifier = Modifier.weight(1f),
        )
        if (state.periods.isNotEmpty()) {
            ZillitSelect(
                value = portal.selectedPeriodId ?: state.periods.first().id,
                options = state.periods.map { it.id },
                onSelect = { onEvent(BankRecEvent.SelectPortalPeriod(it)) },
                label = { id ->
                    state.period(id)?.let { portalPeriodLabel(it, state) } ?: str(S.desktop_br_select_period)
                },
                modifier = Modifier.widthIn(min = 190.dp, max = 280.dp),
            )
        }
        ZillitButton(
            text = str(S.desktop_br_generate_share_link),
            onClick = { onEvent(BankRecEvent.ComposePortalLink) },
            size = ButtonSize.Small,
            leadingIcon = BankRecIcons.Share,
        )
    }

    PortalPreviewCard(
        preview = portal.preview,
        loading = portal.previewLoading || (state.periodsLoading && portal.preview == null),
        state = state,
    )

    BrCard(
        Modifier.fillMaxWidth(),
        title = str(S.desktop_br_active_portal_links),
        icon = BankRecIcons.Share,
        titleRight = if (portal.links.isEmpty()) {
            null
        } else {
            {
                val count = portal.links.size
                ZillitText(
                    if (count == 1) {
                        str(S.desktop_br_link_count_one, count)
                    } else {
                        str(S.desktop_br_link_count_many, count)
                    },
                    style = ZillitTheme.typography.labelSmall,
                    color = ZillitTheme.colors.textMuted,
                )
            }
        },
    ) {
        when {
            portal.loading && portal.links.isEmpty() -> BrSkeletonRows(4)
            portal.links.isEmpty() -> ZillitText(
                str(S.desktop_br_no_portal_links),
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 44.dp),
            )

            else -> LinksTable(state, onEvent)
        }
    }
}

/** `Apr 2026 · Complete` — and the account's name when two periods share the month. */
private fun portalPeriodLabel(period: BankPeriod, state: BankRecUiState): String {
    val month = BankRecFormat.periodLabel(period)
    val shared = state.periods.count { it.periodMillis == period.periodMillis } > 1
    val account = state.account(period.bankAccountId)?.displayName.orEmpty()
    val status = if (period.isOpen) str(S.in_progress) else str(S.dm_action_complete)
    return listOfNotNull(month, account.takeIf { shared && it.isNotBlank() }, status).joinToString(" · ")
}

@Composable
private fun LinksTable(state: BankRecUiState, onEvent: (BankRecEvent) -> Unit) {
    val colors = ZillitTheme.colors
    val muted = mono(12.sp)
    BrTable(
        rows = state.portal.links,
        key = { it.id },
        minWeightWidth = 96.dp,
        columns = listOf(
            BrColumn(str(S.dm_nda_recipient_label), weight = 1.3f) { link ->
                Column {
                    ZillitText(
                        link.recipientName.ifBlank { BankRecFormat.DASH },
                        style = ZillitTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 1,
                    )
                    ZillitText(link.recipientEmail, style = mono(10.5.sp), color = colors.textMuted, maxLines = 1)
                }
            },
            BrColumn(str(S.type), width = 104.dp) { link ->
                ZillitText(
                    link.orgType.label,
                    style = ZillitTheme.typography.bodySmall,
                    color = colors.textSecondary,
                    maxLines = 2,
                )
            },
            BrColumn(str(S.cr_meta_period), width = 76.dp) { link ->
                ZillitText(link.periodLabel.ifBlank { BankRecFormat.DASH }, style = muted, maxLines = 1)
            },
            BrColumn(str(S.desktop_permissions), weight = 1.6f) { link -> PermissionChips(link) },
            BrColumn(str(S.drive_created), width = 84.dp) { link ->
                ZillitText(BankRecFormat.day(link.createdAtMillis), style = muted, color = colors.textSecondary)
            },
            BrColumn(str(S.drive_link_expires_label), width = 84.dp) { link ->
                ZillitText(
                    link.expiresAtMillis?.let(BankRecFormat::day) ?: str(S.desktop_no_expiry),
                    style = muted,
                    color = colors.textSecondary,
                )
            },
            BrColumn(str(S.desktop_views), width = 48.dp, align = BrAlign.End) { link ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    ZillitIcon(BankRecIcons.Views, tint = colors.textMuted, size = 12.dp)
                    ZillitText(link.views.toString(), style = muted)
                }
            },
            BrColumn(str(S.status), width = 70.dp) { link -> BrBadge(link.status.label, link.status.tone) },
            BrColumn(str(S.dd_actions), width = 176.dp, align = BrAlign.End) { link ->
                LinkActions(link, state, onEvent)
            },
        ),
    )
}

@Composable
private fun PermissionChips(link: PortalLink) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        link.permissions.forEach { BrBadge(it.shortLabel, BrTone.Blue) }
    }
}

@Composable
private fun LinkActions(link: PortalLink, state: BankRecUiState, onEvent: (BankRecEvent) -> Unit) {
    val portal = state.portal
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (!link.isActive) {
            ZillitButton(
                text = str(S.desktop_br_re_share),
                onClick = { onEvent(BankRecEvent.EditPortalLink(link)) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
            )
            return@Row
        }
        val copied = portal.copiedToken == link.token
        ZillitIconButton(
            icon = if (copied) ZillitIcons.Check else BankRecIcons.Copy,
            contentDescription = if (copied) str(S.dd_copied) else str(S.copy_link_txt),
            onClick = { onEvent(BankRecEvent.CopyPortalLink(link)) },
            tint = if (copied) ZillitTheme.colors.success else ZillitTheme.colors.textMuted,
        )
        ZillitButton(
            text = str(S.edit),
            onClick = { onEvent(BankRecEvent.EditPortalLink(link)) },
            variant = ButtonVariant.Secondary,
            size = ButtonSize.Small,
        )
        val revoking = portal.revokingId == link.id
        ZillitButton(
            text = if (revoking) str(S.desktop_br_revoking) else str(S.drive_link_revoke),
            onClick = { onEvent(BankRecEvent.RevokePortalLink(link)) },
            variant = ButtonVariant.Danger,
            size = ButtonSize.Small,
            loading = revoking,
            enabled = portal.revokingId == null,
        )
    }
}
