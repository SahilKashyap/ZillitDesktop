package com.zillit.desktop.feature.cashexpenses.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ColumnWidth
import com.zillit.desktop.core.designsystem.component.TableColumn
import com.zillit.desktop.core.designsystem.component.ZillitActionMenu
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitMenuEntry
import com.zillit.desktop.core.designsystem.component.ZillitMenuTone
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.cashexpenses.domain.BatchAssignment
import com.zillit.desktop.feature.cashexpenses.domain.CashFloat
import com.zillit.desktop.feature.cashexpenses.domain.CashRules
import com.zillit.desktop.feature.cashexpenses.domain.ClaimBatch
import com.zillit.desktop.feature.cashexpenses.domain.ExportFormat
import com.zillit.desktop.feature.cashexpenses.ui.BatchPanel
import com.zillit.desktop.feature.cashexpenses.ui.CashDestination
import com.zillit.desktop.feature.cashexpenses.ui.CashEvent
import com.zillit.desktop.feature.cashexpenses.ui.CashPrompt
import com.zillit.desktop.feature.cashexpenses.ui.CashUiState
import com.zillit.desktop.feature.cashexpenses.ui.ConfirmAction
import com.zillit.desktop.feature.cashexpenses.ui.ExportRegister
import com.zillit.desktop.feature.cashexpenses.ui.FloatStatusPill
import com.zillit.desktop.feature.cashexpenses.ui.LocalCashPeople
import com.zillit.desktop.feature.cashexpenses.ui.QueryPanel
import com.zillit.desktop.feature.cashexpenses.ui.ReasonedAction
import com.zillit.desktop.feature.cashexpenses.ui.date
import com.zillit.desktop.feature.cashexpenses.ui.isPostLedger
import com.zillit.desktop.feature.cashexpenses.ui.isSignOff
import com.zillit.desktop.feature.cashexpenses.ui.money

/** One button under a batch, and what it raises. */
internal data class BatchAction(
    val label: String,
    val variant: ButtonVariant,
    val event: CashEvent,
    val enabled: Boolean = true,
)

/**
 * The actions this viewer may take on this batch, on this page — the web's
 * batch views, rule for rule, drawn from [CashRules] as the view model's
 * handlers check them.
 *
 * Deliberately assembled per destination rather than per status: the same
 * batch is actionable in different ways depending on which queue it was
 * reached through.
 */
@Suppress("LongMethod", "CyclomaticComplexMethod") // A rights table; flattening it is what makes it readable.
internal fun batchActions(state: CashUiState, batch: ClaimBatch): List<BatchAction> {
    val viewer = state.viewer
    val locked = state.selectedLocked
    val claims = state.panelClaims
    val loaded = state.panel?.claims != null
    val history = BatchAction(
        str(S.history),
        ButtonVariant.Tertiary,
        CashEvent.ShowHistory(state.panel?.historyOpen != true),
    )
    val query = BatchAction(str(S.ah_cd_query), ButtonVariant.Tertiary, CashEvent.ShowQuery(state.panel?.query == null))
    val assign = BatchAction(
        BatchAssignment.actionLabel(batch),
        ButtonVariant.Secondary,
        CashEvent.Ask(
            CashPrompt.Assign(
                batchId = batch.id,
                title = if (BatchAssignment.isUnassigned(batch)) {
                    str(S.desktop_ce_assign_batch, batch.reference).trim()
                } else {
                    str(S.desktop_ce_reassign_batch, batch.reference).trim()
                },
                label = BatchAssignment.actionLabel(batch),
            ),
        ),
    )
    val reject = BatchAction(
        str(S.reject),
        ButtonVariant.Danger,
        CashEvent.Ask(
            CashPrompt.WithReason(
                action = ReasonedAction.RejectBatch,
                targetId = batch.id,
                title = str(S.desktop_ce_reject_this_batch),
                label = str(S.desktop_ce_why_it_is_being_rejected),
            ),
        ),
    )
    fun confirm(action: ConfirmAction, label: String, title: String, message: String, enabled: Boolean = true) =
        BatchAction(
            label = label,
            variant = ButtonVariant.Primary,
            event = CashEvent.Ask(CashPrompt.Confirm(action, batch.id, title, message)),
            enabled = enabled,
        )

    return when {
        state.destination == CashDestination.CodingQueue -> if (viewer.isCoordinator) {
            listOf(
                confirm(
                    ConfirmAction.SaveAndSubmitCoded,
                    str(S.desktop_ce_submit_coding),
                    str(S.desktop_ce_submit_coding),
                    str(S.desktop_ce_submit_coding_note),
                    enabled = loaded && CashRules.allCoded(claims),
                ),
            )
        } else {
            emptyList()
        }

        state.destination == CashDestination.AuditQueue -> if (viewer.isAccountant) {
            buildList {
                if (!locked) {
                    add(
                        confirm(
                            ConfirmAction.SaveAndVerify,
                            str(S.av_send_for_approval),
                            str(S.desktop_ce_verify_this_batch),
                            str(S.desktop_ce_verify_note),
                            enabled = loaded && CashRules.allVerified(claims),
                        ),
                    )
                    add(assign)
                }
                if (CashRules.canQuery(batch)) add(query)
                add(history)
            }
        } else {
            emptyList()
        }

        state.destination == CashDestination.ApprovalQueue -> buildList {
            if (CashRules.mayApprove(viewer, batch)) {
                add(
                    confirm(
                        ConfirmAction.ApproveBatch,
                        str(S.approve),
                        str(S.desktop_ce_approve_this_batch),
                        str(S.desktop_ce_approve_batch_note),
                        enabled = loaded,
                    ),
                )
                add(reject)
            }
            // An accountant who is not an approver sees the queue read-only —
            // unless they hold the override right.
            if (viewer.canOverrideBatch()) {
                add(
                    BatchAction(
                        str(S.dm_nom_table_override),
                        ButtonVariant.Secondary,
                        CashEvent.Ask(
                            CashPrompt.Confirm(
                                ConfirmAction.OverrideBatch,
                                batch.id,
                                str(S.desktop_card_override_chain),
                                str(S.desktop_ce_override_batch_note),
                            ),
                        ),
                    ),
                )
            }
            add(history)
        }

        // The senior's sign-off: Return to Accounts on an escalated batch, and
        // Approve & Post (`SeniorBatchItem.jsx:80-88, 205-218`).
        state.destination.isSignOff -> if (viewer.canSeeSignOff) {
            buildList {
                if (!locked) {
                    if (CashRules.canReturnToAccounts(batch)) {
                        add(
                            BatchAction(
                                str(S.desktop_ce_return_to_accounts),
                                ButtonVariant.Danger,
                                CashEvent.Ask(
                                    CashPrompt.Confirm(
                                        ConfirmAction.ReturnToAccounts,
                                        batch.id,
                                        str(S.desktop_ce_return_to_accounts),
                                        str(S.desktop_ce_return_to_accounts_note),
                                    ),
                                ),
                            ),
                        )
                    }
                    add(
                        confirm(
                            ConfirmAction.PostBatch,
                            str(S.desktop_ce_sign_off_and_post),
                            str(S.desktop_ce_post_this_batch),
                            str(S.desktop_card_goes_to_ledger_undone, money(batch.totalGross, batch.currency)),
                        ),
                    )
                }
                add(history)
            }
        } else {
            emptyList()
        }

        // Post & Ledger (`PCPostLedgerPage.jsx:1047-1076`).
        state.destination.isPostLedger -> if (viewer.isAccountant && CashRules.canOpenPostRow(viewer, batch)) {
            buildList {
                if (!locked) {
                    add(assign)
                    if (CashRules.canEscalate(viewer, batch)) {
                        add(
                            BatchAction(
                                str(S.desktop_ce_escalate),
                                ButtonVariant.Secondary,
                                CashEvent.Ask(
                                    CashPrompt.WithReason(
                                        action = ReasonedAction.EscalateBatch,
                                        targetId = batch.id,
                                        title = str(S.desktop_ce_escalate_for_senior),
                                        label = str(S.desktop_ce_why_it_needs_a_senior),
                                    ),
                                ),
                            ),
                        )
                    }
                    when {
                        CashRules.canPost(viewer, claims) -> add(
                            confirm(
                                ConfirmAction.PostBatch,
                                str(S.ah_post_to_ledger),
                                str(S.desktop_ce_post_this_batch),
                                str(S.desktop_card_goes_to_ledger_undone, money(batch.totalGross, batch.currency)),
                                enabled = loaded,
                            ),
                        )

                        CashRules.canSubmitForReview(viewer, batch, claims) -> add(
                            confirm(
                                ConfirmAction.SubmitForReview,
                                str(S.desktop_submit_for_review),
                                str(S.desktop_submit_for_review),
                                str(S.desktop_ce_submit_for_review_note),
                            ),
                        )
                    }
                }
                if (CashRules.canQuery(batch)) add(query)
                add(history)
            }
        } else {
            emptyList()
        }

        // History browses posted batches and hands them on; nothing posts from here.
        state.destination == CashDestination.History -> if (viewer.isAccountant) {
            buildList {
                if (!locked) add(assign)
                add(history)
            }
        } else {
            emptyList()
        }

        else -> emptyList()
    }
}

/** The batch's audit trail, beneath its actions. */
@Composable
internal fun BatchHistory(panel: BatchPanel) {
    val people = LocalCashPeople.current
    ZillitDivider()
    ZillitText(text = str(S.history), style = ZillitTheme.typography.titleSmall)
    val entries = panel.history
    when {
        entries == null -> ZillitSpinner(size = SPINNER)
        entries.isEmpty() -> ZillitText(
            text = str(S.desktop_ce_no_history_yet),
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textMuted,
        )

        else -> entries.forEach { entry ->
            Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
                ZillitText(
                    text = entry.action.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() },
                    style = ZillitTheme.typography.bodyMedium,
                )
                ZillitText(
                    text = listOfNotNull(
                        entry.userId?.let { people.nameOf(it) },
                        date(entry.at).takeIf { it != "—" },
                        entry.note?.takeIf { it.isNotBlank() },
                    ).joinToString(" · "),
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textMuted,
                )
            }
        }
    }
}

/** The batch's query thread (`/account-hub/queries`), beside it. */
@Composable
internal fun QueryThreadView(query: QueryPanel, onEvent: (CashEvent) -> Unit) {
    val people = LocalCashPeople.current
    ZillitDivider()
    ZillitText(text = str(S.ah_cd_query), style = ZillitTheme.typography.titleSmall)
    val messages = query.thread?.messages.orEmpty()
    when {
        query.loading -> ZillitSpinner(size = SPINNER)
        messages.isEmpty() -> ZillitText(
            text = str(S.ah_no_queries_yet),
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textMuted,
        )

        else -> messages.forEach { message ->
            Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
                ZillitText(
                    text = listOfNotNull(people.nameOf(message.userId), date(message.at).takeIf { it != "—" })
                        .joinToString(" · "),
                    style = ZillitTheme.typography.labelSmall,
                    color = ZillitTheme.colors.textMuted,
                )
                ZillitText(text = message.text, style = ZillitTheme.typography.bodyMedium)
            }
        }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitTextField(
            value = query.draft,
            onValueChange = { onEvent(CashEvent.EditQuery(it)) },
            placeholder = str(S.type_a_message),
            singleLine = false,
            modifier = Modifier.weight(1f),
        )
        ZillitButton(
            text = str(S.desktop_ce_send_query),
            onClick = { onEvent(CashEvent.SendQuery) },
            size = ButtonSize.Small,
            leadingIcon = ZillitIcons.Send,
            enabled = query.draft.isNotBlank(),
            loading = query.sending,
        )
    }
}

/** Approve, reject and override on a float request, at the level this viewer signs. */
@Suppress("LongMethod") // Approve, reject and override, each with its confirmation.
internal fun floatApprovalActions(
    state: CashUiState,
    onEvent: (CashEvent) -> Unit,
): List<TableColumn<CashFloat>> = listOf(
    TableColumn(
        header = "",
        width = ColumnWidth.Fixed(FLOAT_ACTION_COLUMN),
        cell = { row ->
            val holder = LocalCashPeople.current.nameOrNull(row.userId, row.holderName)
                ?: str(S.desktop_this_crew_member)
            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
                if (CashRules.mayApprove(state.viewer, row)) {
                    ZillitButton(
                        text = str(S.approve),
                        onClick = {
                            onEvent(
                                CashEvent.Ask(
                                    CashPrompt.Confirm(
                                        ConfirmAction.ApproveFloat,
                                        row.id,
                                        str(S.desktop_ce_approve_this_float),
                                        str(
                                            S.desktop_timecard_approve_message,
                                            money(row.requestedAmount, row.currency),
                                            holder,
                                        ),
                                    ),
                                ),
                            )
                        },
                        size = ButtonSize.Small,
                        enabled = !state.busy,
                    )
                    ZillitButton(
                        text = str(S.reject),
                        onClick = {
                            onEvent(
                                CashEvent.Ask(
                                    CashPrompt.WithReason(
                                        action = ReasonedAction.RejectFloat,
                                        targetId = row.id,
                                        title = str(S.desktop_ce_reject_this_float),
                                        label = str(S.desktop_timecard_reject_label),
                                    ),
                                ),
                            )
                        },
                        variant = ButtonVariant.Danger,
                        size = ButtonSize.Small,
                        enabled = !state.busy,
                    )
                } else if (state.viewer.canOverrideFloat()) {
                    ZillitButton(
                        text = str(S.dm_nom_table_override),
                        onClick = {
                            onEvent(
                                CashEvent.Ask(
                                    CashPrompt.Confirm(
                                        ConfirmAction.OverrideFloat,
                                        row.id,
                                        str(S.desktop_card_override_chain),
                                        str(S.desktop_ce_override_float_note),
                                    ),
                                ),
                            )
                        },
                        variant = ButtonVariant.Secondary,
                        size = ButtonSize.Small,
                        enabled = !state.busy,
                    )
                } else {
                    FloatStatusPill(row.status)
                }
            }
        },
    ),
)

/**
 * The Export menu — PDF or spreadsheet for each register offered.
 *
 * An accountant's affordance, as on the web (`CashExpensesModule.jsx:687-713`):
 * crew tabs share the bar but never see the project-wide exports.
 */
@Composable
internal fun ExportButton(registers: List<ExportRegister>, busy: Boolean, onEvent: (CashEvent) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        ZillitButton(
            text = str(S.asset_export),
            onClick = { open = true },
            variant = ButtonVariant.Secondary,
            size = ButtonSize.Small,
            leadingIcon = ZillitIcons.Download,
            loading = busy,
        )
        ZillitActionMenu(
            expanded = open,
            onDismissRequest = { open = false },
            entries = registers.flatMap { register ->
                ExportFormat.entries.map { format ->
                    ZillitMenuEntry.Action(
                        label = exportLabel(register, format, registers.size > 1),
                        icon = if (format == ExportFormat.Pdf) ZillitIcons.File else ZillitIcons.Grid,
                        tone = ZillitMenuTone.Neutral,
                    ) {
                        open = false
                        onEvent(CashEvent.Export(register, format))
                    }
                }
            },
        )
    }
}

private fun exportLabel(register: ExportRegister, format: ExportFormat, named: Boolean): String {
    val action = if (format == ExportFormat.Pdf) str(S.recce_export_pdf) else str(S.desktop_dm_export_excel)
    if (!named) return action
    val what = when (register) {
        ExportRegister.Floats -> str(S.desktop_ce_float_register)
        else -> str(S.desktop_ce_receipts_register)
    }
    return "$what · $action"
}

private val SPINNER = 14.dp
private val FLOAT_ACTION_COLUMN = 190.dp
