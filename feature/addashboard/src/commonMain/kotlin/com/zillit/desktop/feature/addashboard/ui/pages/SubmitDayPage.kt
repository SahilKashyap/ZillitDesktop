package com.zillit.desktop.feature.addashboard.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ColumnWidth
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.TableColumn
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitChoiceChip
import com.zillit.desktop.core.designsystem.component.ZillitDataTable
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitSectionLabel
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.textColumn
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.addashboard.domain.AdDates
import com.zillit.desktop.feature.addashboard.domain.AgencyGroup
import com.zillit.desktop.feature.addashboard.domain.SubmitRules
import com.zillit.desktop.feature.addashboard.domain.SupportingArtistDay
import com.zillit.desktop.feature.addashboard.ui.AdEvent
import com.zillit.desktop.feature.addashboard.ui.AdUiState
import com.zillit.desktop.feature.addashboard.ui.DayEvent
import com.zillit.desktop.feature.addashboard.ui.SubmitEvent

/**
 * Submit Day: the AD's sign-off that the day's attendance and costs are final.
 *
 * The day is split **by currency** into direct-PAYE artistes, who are paid
 * through payroll once they approve their voucher, and agency artistes, who
 * are paid through a purchase order to their agency. Submitting locks the day
 * for everyone, the AD included.
 */
@Composable
internal fun SubmitDayPage(state: AdUiState, onEvent: (AdEvent) -> Unit) {
    val submitted = state.day?.status?.locked == true
    AdPage {
        if (submitted) {
            ZillitNotice(
                text = str(
                    S.desktop_ad_day_submitted_banner,
                    state.day?.poReference.orEmpty().ifBlank { state.poReference },
                ),
                tone = StatusTone.Done,
                icon = ZillitIcons.Tick,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        CurrencyTotals(state)
        ExceptionList(state)
        PurchaseOrderCards(state, submitted, onEvent)
        SigningLinks(state, submitted, onEvent)

        if (!submitted) {
            SignOff(state, onEvent)
        } else {
            SignedProof(state)
        }
    }

    SignaturePadDialog(state, onEvent)
}

/** What each currency owes, split the way the payment runs are split. */
@Composable
private fun CurrencyTotals(state: AdUiState) {
    val totals = state.dayTotals
    if (totals.isEmpty()) return
    ZillitSectionCard(title = str(S.desktop_ad_to_be_paid), padded = false) {
        ZillitDataTable(
            rows = totals,
            columns = listOf(
                textColumn(str(S.ah_lbl_currency)) { it.currency.ifBlank { state.currencies.defaultCode } },
                textColumn(str(S.desktop_ad_engagement_direct_paye)) { row ->
                    "${row.directCount} · ${state.money(row.directTotal, row.currency)}"
                },
                textColumn(str(S.desktop_dm_agency)) { row ->
                    "${row.agencyCount} · ${state.money(row.agencyTotal, row.currency)}"
                },
                TableColumn(str(S.ah_total_label), width = ColumnWidth.Fixed(AMOUNT_WIDTH), numeric = true) { row ->
                    ZillitText(
                        text = state.money(row.total, row.currency),
                        style = ZillitTheme.typography.numeric,
                    )
                },
            ),
            key = { it.currency },
            virtualised = false,
        )
    }
}

/**
 * The rows a submit will **not** move.
 *
 * Absent, blocked and draft artistes are listed rather than silently left
 * behind: they are the reason a day can read as unfinished afterwards.
 */
@Composable
private fun ExceptionList(state: AdUiState) {
    val exceptions = SubmitRules.exceptions(state.dayList)
    if (exceptions.isEmpty()) return
    ZillitSectionCard(
        title = str(S.desktop_transport_not_submitted),
        meta = str(S.desktop_ad_n_exceptions, exceptions.size),
        padded = false,
    ) {
        ZillitDataTable(
            rows = exceptions,
            columns = listOf(
                textColumn(str(S.name), width = ColumnWidth.Weight(2f)) { it.artisteName },
                TableColumn(str(S.status), width = ColumnWidth.Fixed(STATUS_WIDTH)) { row ->
                    ZillitStatusPill(label = row.statusLabel(), tone = row.statusTone())
                },
                textColumn(str(S.reason)) { it.blockReason },
            ),
            key = { it.id },
            virtualised = false,
        )
    }
}

/**
 * One card per agency and currency.
 *
 * An agency whose artistes carry a name but no registered vendor is **skipped**
 * and says so: raising its order would mean an order with no vendor at all.
 * Once the artistes are re-linked in the register, the card offers **Post PO**.
 */
@Composable
private fun PurchaseOrderCards(state: AdUiState, submitted: Boolean, onEvent: (AdEvent) -> Unit) {
    val groups = state.agencyGroups
    if (groups.isEmpty()) return
    val saved = state.day?.purchaseOrders.orEmpty()
    val pending = SubmitRules.pendingGroups(groups, saved)
    ZillitSectionCard(
        title = str(S.desktop_ad_agency_pos),
        meta = state.poReference,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            groups.forEachIndexed { index, group ->
                PurchaseOrderCard(
                    state = state,
                    group = group,
                    // The card prints the reference over every group, while the
                    // order's own description counts only the vendor-linked
                    // ones — so the two differ when a group is skipped.
                    reference = "${state.poReference}-${index + 1}",
                    posted = group !in pending,
                    submitted = submitted,
                    onEvent = onEvent,
                )
            }
        }
    }
}

@Composable
private fun PurchaseOrderCard(
    state: AdUiState,
    group: AgencyGroup,
    reference: String,
    posted: Boolean,
    submitted: Boolean,
    onEvent: (AdEvent) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.fillMaxWidth().weight(1f)) {
            ZillitText(text = reference, style = ZillitTheme.typography.numeric)
            ZillitText(
                text = "${group.agency} · ${group.artistes.size} · ${state.money(group.amount, group.currency)}",
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textSecondary,
            )
        }
        when {
            posted -> ZillitStatusPill(label = str(S.ah_step_posted), tone = StatusTone.Done)
            !group.hasVendor -> ZillitStatusPill(
                label = str(S.desktop_ad_no_vendor_po_skipped),
                tone = StatusTone.Rejected,
            )

            submitted && state.viewer.canPost -> ZillitButton(
                text = str(S.desktop_ad_post_po),
                onClick = { onEvent(SubmitEvent.PostPendingPo(group)) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                loading = state.submit.postingPo == group.key,
            )

            else -> ZillitStatusPill(label = str(S.pending), tone = StatusTone.Pending)
        }
    }
}

/**
 * The artistes the server e-mails an approval link to on submission.
 *
 * External direct-PAYE artistes only: an internal one approves in the
 * Supporting Artistes tool, and an agency artiste is covered by their agency's
 * manifest countersign.
 */
@Composable
private fun SigningLinks(state: AdUiState, submitted: Boolean, onEvent: (AdEvent) -> Unit) {
    val rows = state.dayList.filter { it.isPresent && it.needsApprovalLink }
    if (rows.isEmpty()) return
    ZillitSectionCard(
        title = str(S.desktop_ad_artiste_signing_links),
        meta = str(S.desktop_ad_links_emailed_on_submit),
        padded = false,
    ) {
        ZillitDataTable(
            rows = rows,
            columns = listOf(
                textColumn(str(S.name), width = ColumnWidth.Weight(2f)) { it.artisteName },
                TableColumn(str(S.status), width = ColumnWidth.Fixed(LINK_STATUS_WIDTH)) { row ->
                    ZillitStatusPill(
                        label = row.linkLabel(),
                        tone = if (row.signed) StatusTone.Done else StatusTone.Pending,
                    )
                },
                TableColumn(str(S.dd_actions), width = ColumnWidth.Fixed(ACTION_WIDTH)) { row ->
                    if (!state.viewer.canPost) return@TableColumn
                    ZillitButton(
                        text = str(S.desktop_ad_send_link),
                        onClick = { onEvent(DayEvent.SendApprovalLink(row.id)) },
                        variant = ButtonVariant.Tertiary,
                        size = ButtonSize.Small,
                        enabled = !row.isDraft,
                        loading = state.todayList.sendingLink == row.id,
                    )
                },
            ),
            key = { it.id },
            virtualised = false,
        )
        if (!submitted) {
            ZillitText(
                text = str(S.desktop_ad_links_sent_on_submit),
                style = ZillitTheme.typography.labelSmall,
                color = ZillitTheme.colors.textMuted,
            )
        }
    }
}

/** The signature, the routing, and the one button that sends the day. */
@Composable
private fun SignOff(state: AdUiState, onEvent: (AdEvent) -> Unit) {
    ZillitSectionCard(title = str(S.desktop_ad_sign_and_submit)) {
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
            ZillitSectionLabel(text = str(S.desktop_ad_po_routing))
            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                ZillitChoiceChip(
                    label = str(S.standard),
                    selected = state.submit.postType == "standard",
                    onClick = { onEvent(SubmitEvent.PostType("standard")) },
                )
                ZillitChoiceChip(
                    label = str(S.ah_topup_filter_urgent),
                    selected = state.submit.postType == "urgent",
                    // Flags the order and nothing else — the notes lead with
                    // "Urgent — " and no other screen reads it.
                    onClick = { onEvent(SubmitEvent.PostType("urgent")) },
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state.submit.hasSignature) {
                    ZillitStatusPill(label = str(S.signed), tone = StatusTone.Done)
                    ZillitButton(
                        text = str(S.desktop_ad_clear_signature),
                        onClick = { onEvent(SubmitEvent.ClearSignature) },
                        variant = ButtonVariant.Tertiary,
                        size = ButtonSize.Small,
                    )
                } else {
                    ZillitButton(
                        text = str(S.desktop_ad_add_signature),
                        onClick = { onEvent(SubmitEvent.OpenSignaturePad) },
                        variant = ButtonVariant.Secondary,
                        leadingIcon = ZillitIcons.Signature,
                        enabled = state.canEditDay,
                    )
                }
            }

            ZillitButton(
                text = str(S.desktop_ad_submit_day_and_post_po),
                onClick = { onEvent(SubmitEvent.Submit) },
                // One present artiste, the posting right and a signature.
                // Deliberately not a wrap check: present artistes with no time
                // out are submitted as they are.
                enabled = SubmitRules.ready(state.dayList, state.canEditDay, state.submit.hasSignature),
                loading = state.submit.submitting,
                leadingIcon = ZillitIcons.Send,
            )
        }
    }
}

/** What a submitted day shows in place of the controls. */
@Composable
private fun SignedProof(state: AdUiState) {
    val signature = state.day?.signature ?: return
    ZillitSectionCard(title = str(S.desktop_signed_off)) {
        Column(verticalArrangement = Arrangement.spacedBy ( ZillitTheme.spacing.xs)) {
            AdDetailRow(label = str(S.name), value = signature.typedName)
            AdDetailRow(
                label = str(S.desktop_ad_signed_at),
                // An audit instant, so it reads in the viewer's own zone —
                // unlike every shoot date in this module.
                value = AdDates.formatInstant(signature.signedAt),
            )
            AdDetailRow(
                label = str(S.desktop_ad_signature_method),
                value = if (signature.signStatus == "typed") {
                    str(S.desktop_ad_typed_signature)
                } else {
                    str(S.desktop_ad_external_signature)
                },
            )
            AdDetailRow(label = str(S.desktop_reference), value = state.day.poReference)
        }
    }
}

private fun SupportingArtistDay.linkLabel(): String = when {
    signed -> str(S.signed)
    isDraft -> str(S.desktop_ad_link_when_verified)
    else -> str(S.desktop_sa_awaiting_signature)
}

private val AMOUNT_WIDTH = 130.dp
private val STATUS_WIDTH = 130.dp
private val LINK_STATUS_WIDTH = 180.dp
private val ACTION_WIDTH = 130.dp
