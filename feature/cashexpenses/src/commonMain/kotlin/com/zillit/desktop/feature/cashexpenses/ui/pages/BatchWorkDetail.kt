package com.zillit.desktop.feature.cashexpenses.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitAvatar
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDateField
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.cashexpenses.domain.BatchAssignment
import com.zillit.desktop.feature.cashexpenses.domain.BatchStatus
import com.zillit.desktop.feature.cashexpenses.domain.CashDates
import com.zillit.desktop.feature.cashexpenses.domain.CashPeople
import com.zillit.desktop.feature.cashexpenses.domain.CashRules
import com.zillit.desktop.feature.cashexpenses.domain.ClaimBatch
import com.zillit.desktop.feature.cashexpenses.domain.Settlement
import com.zillit.desktop.feature.cashexpenses.ui.BatchEvent
import com.zillit.desktop.feature.cashexpenses.ui.CashDestination
import com.zillit.desktop.feature.cashexpenses.ui.CashEvent
import com.zillit.desktop.feature.cashexpenses.ui.CashPrompt
import com.zillit.desktop.feature.cashexpenses.ui.CashUiState
import com.zillit.desktop.feature.cashexpenses.ui.ConfirmAction
import com.zillit.desktop.feature.cashexpenses.ui.LocalCashPeople
import com.zillit.desktop.feature.cashexpenses.ui.ReasonedAction
import com.zillit.desktop.feature.cashexpenses.ui.date
import com.zillit.desktop.feature.cashexpenses.ui.isPostLedger
import com.zillit.desktop.feature.cashexpenses.ui.mayEdit
import com.zillit.desktop.feature.cashexpenses.ui.mayWorkOn
import com.zillit.desktop.feature.cashexpenses.ui.rememberFace

/**
 * The open batch on Post & Ledger, Audit, History and Coding — the web's
 * `PostModal` full-page view (`PCPostLedgerPage.jsx:1019-1276`) inside the
 * detail pane: the action bar at the top, then the batch header, the
 * assignment, the banner, the match bar and one card per receipt.
 */
@Composable
internal fun BatchWorkDetail(state: CashUiState, batch: ClaimBatch, onEvent: (CashEvent) -> Unit) {
    val panel = state.panel?.takeIf { it.batchId == batch.id }
    Column(modifier = Modifier.fillMaxSize()) {
        ActionBar(state, batch, onEvent)
        ZillitDivider()
        ZillitScrollColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(ZillitTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            if (state.selectedLocked) {
                ZillitNotice(
                    text = str(S.desktop_ce_batch_in_locked_period, state.lockedThrough.orEmpty()),
                    tone = StatusTone.Rejected,
                    icon = ZillitIcons.Lock,
                )
            }
            BatchHeader(state, batch, onEvent)
            AssignmentLine(batch)
            InfoBanner(state.destination == CashDestination.AuditQueue)
            val claims = state.panelClaims
            GrossMatchBar(
                backend = claims.sumOf { it.grossAmount }.takeIf { it > 0 } ?: batch.totalGross,
                live = CashRules.codedTotal(claims),
                format = { state.formatMoney(it, batch.currency) },
            )
            Receipts(state, batch, onEvent)
            if (panel?.historyOpen == true) BatchHistory(panel)
            panel?.query?.let { QueryThreadView(it, onEvent) }
        }
    }
}

/**
 * The web's sticky header buttons, in its order: Query, History, Save,
 * Assign/Reassign, Escalate, Post to Ledger or Submit for Review, Send for
 * Approval, Forward to Accounts. Inside the locked period only Query and
 * History remain (`PCPostLedgerPage.jsx:1054-1058`).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActionBar(state: CashUiState, batch: ClaimBatch, onEvent: (CashEvent) -> Unit) {
    val destination = state.destination
    val audit = destination == CashDestination.AuditQueue
    val post = destination.isPostLedger
    val working = state.mayWorkOn(batch)
    val open = working && !state.selectedLocked
    val panel = state.panel?.takeIf { it.batchId == batch.id }
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(ZillitTheme.spacing.md),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm, Alignment.End),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        val queryable = (audit || post) && CashRules.canQuery(batch)
        if (queryable && working) {
            val unread = destination.batchBadgeKey?.let { state.unreadFor(it, batch.id) } ?: 0
            Row(verticalAlignment = Alignment.CenterVertically) {
                ZillitButton(
                    text = str(S.ah_cd_query),
                    onClick = { onEvent(CashEvent.ShowQuery(panel?.query == null)) },
                    variant = ButtonVariant.Secondary,
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.Chat,
                )
                UnreadBadge(unread)
            }
        }
        ZillitButton(
            text = str(S.history),
            onClick = { onEvent(CashEvent.ShowHistory(panel?.historyOpen != true)) },
            variant = ButtonVariant.Secondary,
            size = ButtonSize.Small,
            leadingIcon = ZillitIcons.Clock,
        )
        if (open) OpenActions(state, batch, onEvent)
    }
}

/** The actions that change the batch — none inside the locked period, none without the rights. */
@Suppress("LongMethod", "CyclomaticComplexMethod") // The web's header, button by button.
@Composable
private fun OpenActions(state: CashUiState, batch: ClaimBatch, onEvent: (CashEvent) -> Unit) {
    val destination = state.destination
    val coding = destination == CashDestination.CodingQueue
    val audit = destination == CashDestination.AuditQueue
    val post = destination.isPostLedger
    val loaded = state.panel?.takeIf { it.batchId == batch.id }?.claims != null
    val claims = state.panelClaims
    val idle = !state.busy
    ZillitButton(
        text = when {
            audit -> str(S.desktop_pc_save_progress)
            coding -> str(S.ah_save_draft)
            else -> str(S.save)
        },
        onClick = { onEvent(BatchEvent.Save(batch.id)) },
        variant = ButtonVariant.Secondary,
        size = ButtonSize.Small,
        leadingIcon = ZillitIcons.Save,
        enabled = idle && loaded,
    )
    if (!coding) {
        ZillitButton(
            text = BatchAssignment.actionLabel(batch),
            onClick = {
                onEvent(
                    CashEvent.Ask(
                        CashPrompt.Assign(
                            batchId = batch.id,
                            title = if (BatchAssignment.isUnassigned(batch)) {
                                str(S.desktop_pc_assign_ref, batch.reference)
                            } else {
                                str(S.desktop_pc_reassign_ref, batch.reference)
                            },
                            label = BatchAssignment.actionLabel(batch),
                        ),
                    ),
                )
            },
            variant = ButtonVariant.Secondary,
            size = ButtonSize.Small,
            leadingIcon = ZillitIcons.Users,
            enabled = idle,
        )
    }
    if (post && CashRules.canEscalate(state.viewer, batch)) {
        ZillitButton(
            text = str(S.desktop_ce_escalate),
            onClick = {
                onEvent(
                    CashEvent.Ask(
                        CashPrompt.WithReason(
                            action = ReasonedAction.EscalateBatch,
                            targetId = batch.id,
                            title = str(S.desktop_pc_escalate_ref, batch.reference),
                            label = str(S.desktop_card_reason_for_escalation),
                        ),
                    ),
                )
            },
            variant = ButtonVariant.Secondary,
            size = ButtonSize.Small,
            enabled = idle,
        )
    }
    if (post) {
        when {
            CashRules.canPost(state.viewer, claims) -> ZillitButton(
                text = str(S.ah_post_to_ledger),
                // Kept behind its confirmation: posting writes to the
                // ledger and nothing in this tool undoes it.
                onClick = {
                    onEvent(
                        CashEvent.Ask(
                            CashPrompt.Confirm(
                                ConfirmAction.PostBatch,
                                batch.id,
                                str(S.desktop_ce_post_this_batch),
                                str(
                                    S.desktop_card_goes_to_ledger_undone,
                                    state.formatMoney(batch.totalGross, batch.currency),
                                ),
                            ),
                        ),
                    )
                },
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Ledger,
                enabled = idle && loaded,
            )

            CashRules.canSubmitForReview(state.viewer, batch, claims) -> ZillitButton(
                text = str(S.desktop_submit_for_review),
                onClick = { onEvent(BatchEvent.SubmitForReview(batch.id)) },
                size = ButtonSize.Small,
                enabled = idle,
            )
        }
    }
    if (audit) {
        ZillitButton(
            text = str(S.cs_send_for_approval),
            onClick = { onEvent(BatchEvent.SendForApproval(batch.id)) },
            size = ButtonSize.Small,
            enabled = idle && loaded && CashRules.allVerified(claims),
        )
    }
    if (coding) {
        ZillitButton(
            text = str(S.desktop_pc_forward_to_accounts),
            onClick = { onEvent(BatchEvent.ForwardCoded(batch.id)) },
            size = ButtonSize.Small,
            leadingIcon = ZillitIcons.Ledger,
            enabled = idle && loaded && CashRules.allCoded(claims),
        )
    }
}

/** Face, reference and settlement, name, role, the three steps, and the batch's figures. */
@Suppress("LongMethod") // The web's batch header, top to bottom.
@Composable
private fun BatchHeader(state: CashUiState, batch: ClaimBatch, onEvent: (CashEvent) -> Unit) {
    val people = LocalCashPeople.current
    val name = people.nameOf(batch.userId, batch.holderName)
    val claims = state.panelClaims
    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
        ZillitAvatar(name = name, image = rememberFace(batch.userId), userId = batch.userId, size = HEADER_AVATAR)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            ) {
                ZillitStatusPill(label = "#${batch.reference}", tone = StatusTone.Neutral)
                batch.settlementType?.takeIf { it.isNotBlank() }?.let {
                    ZillitStatusPill(label = Settlement.label(it), tone = StatusTone.Progress)
                }
                HeaderStatePill(state)
            }
            ZillitText(text = name, style = ZillitTheme.typography.titleLarge)
            ZillitText(
                text = listOfNotNull(
                    people.designationOf(batch.userId)?.localised(),
                    state.departmentName(batch.departmentId),
                    date(batch.createdAt).takeIf { it != "—" },
                ).joinToString(" · "),
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
            )
            ProcessStepper(stage(state, batch), Modifier.padding(top = ZillitTheme.spacing.sm))
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg, Alignment.End),
    ) {
        HeaderFigure(str(S.ah_receipts_label), (if (claims.isEmpty()) batch.claimCount else claims.size).toString())
        HeaderFigure(
            label = str(S.desktop_pc_batch_total),
            value = state.formatMoney(
                claims.sumOf { it.grossAmount }.takeIf { it > 0 } ?: batch.totalGross,
                batch.currency,
            ),
            color = ZillitTheme.colors.accent,
        )
        // The ledger date: not chosen while coding (`PCPostLedgerPage.jsx:1120-1139`).
        val panel = state.panel?.takeIf { it.batchId == batch.id }
        if (state.destination != CashDestination.CodingQueue && panel != null) {
            Column {
                FieldLabel(str(S.ah_lbl_eff_date))
                if (state.selectedLocked || !state.mayWorkOn(batch)) {
                    LockedField(panel.effectiveDate.ifBlank { "—" }, Modifier.width(DATE_WIDTH))
                } else {
                    ZillitDateField(
                        value = panel.effectiveDate,
                        onValueChange = { onEvent(CashEvent.EditEffectiveDate(it)) },
                        helperText = CashDates.minimum(state.lockedThrough)
                            ?.let { str(S.desktop_ce_ledger_date_from, it) },
                        modifier = Modifier.width(DATE_WIDTH),
                    )
                }
            }
        }
    }
}

/** "Ready to post" when this viewer can post it, "Coding" otherwise — History says "Posted". */
@Composable
private fun HeaderStatePill(state: CashUiState) {
    when {
        state.destination == CashDestination.History -> ZillitStatusPill(
            label = str(S.ah_step_posted),
            tone = StatusTone.Done,
            dot = true,
        )

        state.destination.isPostLedger && CashRules.canPost(state.viewer, state.panelClaims) -> ZillitStatusPill(
            label = str(S.desktop_pc_ready_to_post),
            tone = StatusTone.Done,
            dot = true,
        )

        else -> ZillitStatusPill(label = str(S.desktop_card_coding), tone = StatusTone.Pending, dot = true)
    }
}

/**
 * The web's stage: audit and coding are always coding (1); posted is done
 * (3); approved or with a senior, or coded in full, is posting (2).
 */
private fun stage(state: CashUiState, batch: ClaimBatch): Int = when {
    state.destination == CashDestination.AuditQueue || state.destination == CashDestination.CodingQueue -> 1
    batch.status == BatchStatus.Posted -> STAGE_DONE
    batch.status == BatchStatus.UnderReview || batch.status == BatchStatus.Escalated -> 2
    CashRules.allCoded(state.panelClaims) -> 2
    else -> 1
}

/** "Assigned to Name (Designation) · by Name (Designation) — reason", or "· Auto assigned". */
@Composable
private fun AssignmentLine(batch: ClaimBatch) {
    val assignee = batch.assignedTo?.takeIf { it.isNotBlank() } ?: return
    val people = LocalCashPeople.current
    val by = batch.assignedBy?.takeIf { it.isNotBlank() }
    val detail = when {
        by == SYSTEM -> str(S.desktop_ce_auto_assigned)
        by != null -> str(S.dm_nda_sent_by, withRole(people, by)) +
            (batch.assignmentReason?.takeIf { it.isNotBlank() }?.let { " — $it" } ?: "")

        else -> null
    }
    ZillitText(
        text = listOfNotNull(str(S.desktop_ce_assigned_to, withRole(people, assignee)), detail).joinToString(" · "),
        style = ZillitTheme.typography.bodySmall,
        color = ZillitTheme.colors.textMuted,
    )
}

private fun withRole(people: CashPeople, userId: String): String {
    val role = people.designationOf(userId)?.localised()
    return if (role == null) people.nameOf(userId) else "${people.nameOf(userId)} ($role)"
}

/** The amber banner that says what this page is for (`PCPostLedgerPage.jsx:1152-1169`). */
@Composable
private fun InfoBanner(audit: Boolean) {
    TitledNotice(
        title = if (audit) str(S.desktop_pc_banner_audit_title) else str(S.desktop_pc_banner_post_title),
        body = if (audit) str(S.desktop_pc_banner_audit_body) else str(S.desktop_pc_banner_post_body),
        icon = ZillitIcons.File,
    )
}

@Composable
private fun Receipts(state: CashUiState, batch: ClaimBatch, onEvent: (CashEvent) -> Unit) {
    val panel = state.panel?.takeIf { it.batchId == batch.id }
    if (panel != null && panel.claims == null && !panel.failed) {
        Box(Modifier.fillMaxWidth().padding(ZillitTheme.spacing.xl), contentAlignment = Alignment.Center) {
            ZillitSpinner()
        }
        return
    }
    if (panel?.failed == true && state.panelClaims.isEmpty()) {
        ZillitNotice(text = str(S.desktop_ce_receipts_failed), tone = StatusTone.Rejected, icon = ZillitIcons.Warning)
        return
    }
    val editable = state.mayEdit(batch)
    val audit = state.destination == CashDestination.AuditQueue
    state.panelClaims.forEachIndexed { index, claim ->
        ReceiptCard(
            claim = claim,
            number = index + 1,
            currency = batch.currency,
            formatMoney = state::formatMoney,
            accounts = state.chartAccounts,
            mode = ReceiptCardMode(
                editable = editable,
                lockClaimant = state.destination == CashDestination.CodingQueue,
                showVerify = audit,
                verifying = panel?.verifying == claim.id,
            ),
            onEvent = onEvent,
        )
    }
}

private const val SYSTEM = "system"
private const val STAGE_DONE = 3
private val HEADER_AVATAR = 46.dp
private val DATE_WIDTH = 200.dp
