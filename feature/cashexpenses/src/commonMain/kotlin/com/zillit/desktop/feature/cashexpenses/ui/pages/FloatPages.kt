package com.zillit.desktop.feature.cashexpenses.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ColumnWidth
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.TableColumn
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDataTable
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.component.textColumn
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.cashexpenses.domain.CashFloat
import com.zillit.desktop.feature.cashexpenses.domain.CashPeople
import com.zillit.desktop.feature.cashexpenses.domain.CashTopUp
import com.zillit.desktop.feature.cashexpenses.domain.FloatStatus
import com.zillit.desktop.feature.cashexpenses.ui.AmountAction
import com.zillit.desktop.feature.cashexpenses.domain.CashFormFields
import com.zillit.desktop.feature.cashexpenses.ui.CashEvent
import com.zillit.desktop.feature.cashexpenses.ui.CashPrompt
import com.zillit.desktop.feature.cashexpenses.ui.CashUiState
import com.zillit.desktop.feature.cashexpenses.ui.LocalCashPeople
import com.zillit.desktop.feature.cashexpenses.ui.personColumn
import com.zillit.desktop.feature.cashexpenses.ui.ConfirmAction
import com.zillit.desktop.feature.cashexpenses.ui.FloatRequestDraft
import com.zillit.desktop.feature.cashexpenses.ui.FloatStatusPill
import com.zillit.desktop.feature.cashexpenses.ui.date
import com.zillit.desktop.feature.cashexpenses.ui.money

/**
 * The accountant's float register.
 *
 * A float moves through issue → ready to collect → collected → closed, and the
 * row offers exactly the transition that is next. Showing every action on every
 * row was how the web let an accountant mark an uncollected float closed.
 */
@Composable
fun ActiveFloatsPage(state: CashUiState, onEvent: (CashEvent) -> Unit) {
    val people = LocalCashPeople.current
    val rows = state.activeFloats.filter { it.matches(state.search, people) }

    FixedPage {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitSearchField(
                value = state.search,
                onValueChange = { onEvent(CashEvent.Search(it)) },
                placeholder = str(S.desktop_ce_search_floats),
                modifier = Modifier.width(SEARCH_WIDTH),
            )
            ZillitText(
                text = if (rows.size == 1) {
                    str(S.desktop_ce_float_count_one, rows.size)
                } else {
                    str(S.desktop_ce_floats_count, rows.size)
                },
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textSecondary,
            )
        }

        ZillitSectionCard(
            title = str(S.desktop_ce_active_floats),
            icon = ZillitIcons.Wallet,
            padded = false,
            modifier = Modifier.weight(1f),
        ) {
            ZillitDataTable(
                rows = rows,
                columns = floatColumns() + floatActionColumn(state, onEvent),
                key = { it.id },
                loading = state.loading,
                emptyTitle = if (state.search.isBlank()) {
                    str(S.desktop_ce_no_active_floats)
                } else {
                    str(S.desktop_ce_no_floats_match)
                },
                emptyMessage = if (state.search.isBlank()) {
                    str(S.desktop_ce_active_floats_empty)
                } else {
                    str(S.desktop_ce_clear_search_floats)
                },
                isSelected = { it.id == state.selectedFloatId },
                onRowClick = { onEvent(CashEvent.SelectFloat(it.id)) },
            )
        }
    }
}

/**
 * The action a float's current status makes available.
 *
 * One button, never a menu of all of them: the lifecycle is linear, so at any
 * moment exactly one transition is correct and the rest are mistakes.
 */
private fun floatActionColumn(
    state: CashUiState,
    onEvent: (CashEvent) -> Unit,
): TableColumn<CashFloat> = TableColumn(
    header = "",
    width = ColumnWidth.Fixed(ACTION_COLUMN),
    cell = { row ->
        val holder = LocalCashPeople.current.nameOrNull(row.userId, row.holderName) ?: str(S.desktop_ce_the_holder)
        val action = row.nextAction(holder)
        if (action == null || !state.viewer.isAccountant) {
            ZillitText(
                text = "—",
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
            )
        } else {
            ZillitButton(
                text = action.label,
                onClick = {
                    onEvent(
                        CashEvent.Ask(
                            CashPrompt.Confirm(
                                action = action.action,
                                targetId = row.id,
                                title = action.label,
                                message = action.message,
                            ),
                        ),
                    )
                },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                enabled = !state.busy,
            )
        }
    },
)

private data class FloatAction(
    val label: String,
    val action: ConfirmAction,
    val message: String,
)

/** [holder] is the name the confirmation addresses, already looked up. */
private fun CashFloat.nextAction(holder: String): FloatAction? = when (status) {
    FloatStatus.Approved, FloatStatus.AcctOverride -> FloatAction(
        label = str(S.desktop_ce_ready_to_collect),
        action = ConfirmAction.ReadyToCollect,
        message = str(S.desktop_ce_tell_holder_ready, holder),
    )

    FloatStatus.ReadyToCollect -> FloatAction(
        label = str(S.desktop_ce_mark_collected),
        action = ConfirmAction.CollectFloat,
        message = str(S.desktop_ce_record_handover, money(requestedAmount, currency)),
    )

    FloatStatus.Spent, FloatStatus.PendingReturn -> FloatAction(
        label = str(S.desktop_ce_close_float),
        action = ConfirmAction.CloseFloat,
        message = str(S.desktop_ce_close_float_note),
    )

    FloatStatus.AwaitingApproval -> FloatAction(
        label = str(S.desktop_ce_issue),
        action = ConfirmAction.IssueFloat,
        message = str(S.desktop_ce_issue_float_note),
    )

    else -> null
}

/**
 * The crew member's float request form.
 *
 * Shows their existing floats above the form on purpose: most "I need a float"
 * requests are made by someone who already has one and has forgotten, and a
 * second float on one person is work for the accounts team to unpick.
 */
@Suppress("LongMethod") // The form and the floats it should stop you duplicating.
@Composable
fun FloatRequestPage(state: CashUiState, onEvent: (CashEvent) -> Unit) {
    val draft = state.floatDraft
    val existing = state.myFloats.filter { it.status.isOutstanding }
    // What this production configured the form to be. An unread template shows
    // every field, which is this form as it was before templates existed.
    val form = state.floatForm
    val shows = { label: String -> form.shows(CashFormFields.FLOAT_REQUEST, label) }
    val required = { label: String -> form.isRequired(CashFormFields.FLOAT_REQUEST, label) }

    ScrollingPage {
        if (existing.isNotEmpty()) {
            ZillitNotice(
                text = str(S.desktop_ce_already_hold_floats, existing.size),
                tone = StatusTone.Pending,
                icon = ZillitIcons.Wallet,
            )
        }

        ZillitSectionCard(title = str(S.ah_request_a_float), icon = ZillitIcons.Wallet) {
            // The amount and the purpose are what a float *is*; the form
            // template can require them but never take them away, because a
            // request without either is not a request.
            ZillitTextField(
                value = draft.amount,
                onValueChange = { onEvent(CashEvent.EditFloatRequest(draft.copy(amount = it))) },
                label = str(S.desktop_ce_amount_needed),
                placeholder = "0.00",
                keyboardType = KeyboardType.Decimal,
                modifier = Modifier.fillMaxWidth(),
            )
            ZillitTextField(
                value = draft.purpose,
                onValueChange = { onEvent(CashEvent.EditFloatRequest(draft.copy(purpose = it))) },
                label = str(S.desktop_ce_what_is_it_for),
                placeholder = str(S.desktop_ce_float_purpose_placeholder),
                singleLine = false,
                modifier = Modifier.fillMaxWidth(),
            )
            if (shows(CashFormFields.DURATION) || shows(CashFormFields.DURATION_TYPE)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                ) {
                    if (shows(CashFormFields.DURATION)) {
                        ZillitTextField(
                            value = draft.duration,
                            onValueChange = {
                                onEvent(CashEvent.EditFloatRequest(draft.copy(duration = it)))
                            },
                            label = if (required(CashFormFields.DURATION)) {
                                str(S.desktop_ce_how_long_for_required)
                            } else {
                                str(S.desktop_ce_how_long_for)
                            },
                            placeholder = "2",
                            keyboardType = KeyboardType.Number,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (shows(CashFormFields.DURATION_TYPE)) {
                        Column(modifier = Modifier.weight(1f)) {
                            ZillitText(
                                text = str(S.dm_step2_unit),
                                style = ZillitTheme.typography.label,
                                color = ZillitTheme.colors.textSecondary,
                            )
                            ZillitSelect(
                                value = draft.durationType,
                                options = DURATION_TYPES,
                                onSelect = {
                                    onEvent(CashEvent.EditFloatRequest(draft.copy(durationType = it)))
                                },
                                label = { it.replaceFirstChar { char -> char.uppercase() } },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
            FloatCustomFields(state, onEvent)
            FloatUnansweredNotice(state)
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = ZillitTheme.spacing.sm),
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            ) {
                ZillitButton(
                    text = str(S.av_send_request),
                    onClick = { onEvent(CashEvent.SubmitFloatRequest) },
                    leadingIcon = ZillitIcons.Send,
                    loading = state.busy,
                )
                ZillitButton(
                    text = str(S.ah_clear),
                    onClick = { onEvent(CashEvent.EditFloatRequest(FloatRequestDraft())) },
                    variant = ButtonVariant.Tertiary,
                )
            }
        }

        ZillitSectionCard(title = str(S.desktop_ce_your_floats), icon = ZillitIcons.Ledger, padded = false) {
            ZillitDataTable(
                rows = state.myFloats,
                columns = crewFloatColumns(),
                key = { it.id },
                loading = state.loading,
                emptyTitle = str(S.desktop_ce_no_floats_yet),
                emptyMessage = str(S.desktop_ce_your_floats_empty),
                virtualised = false,
            )
        }
    }
}

/**
 * Cash Extension — the crew member asking for more on an existing float.
 *
 * Separate from a new request because it is a different thing to the accounts
 * team: a top-up lands in their top-up inbox and adds to a float that already
 * has a balance and a history.
 */
@Suppress("LongMethod") // One page, laid out in one place; the sweep's wrapped calls added the lines.
@Composable
fun CashExtensionPage(state: CashUiState, onEvent: (CashEvent) -> Unit) {
    val activeFloat = state.myFloats.firstOrNull { it.status.isOutstanding }

    ScrollingPage {
        if (activeFloat == null) {
            ZillitNotice(
                text = str(S.desktop_ce_no_float_to_extend),
                tone = StatusTone.Progress,
            )
            return@ScrollingPage
        }

        ZillitSectionCard(
            title = str(S.desktop_card_top_up_card, activeFloat.requestNumber),
            icon = ZillitIcons.Wallet,
            meta = activeFloat.status.label,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    ZillitText(
                        text = money(activeFloat.balance, activeFloat.currency),
                        style = ZillitTheme.typography.displayLarge,
                    )
                    ZillitText(
                        text = str(
                            S.desktop_ce_remaining_of,
                            money(activeFloat.issuedAmount, activeFloat.currency),
                        ),
                        style = ZillitTheme.typography.bodySmall,
                        color = ZillitTheme.colors.textSecondary,
                    )
                }
                ZillitButton(
                    text = str(S.desktop_card_request_top_up),
                    onClick = {
                        onEvent(
                            CashEvent.Ask(
                                CashPrompt.WithAmount(
                                    action = AmountAction.RequestFloatTopUp,
                                    targetId = activeFloat.id,
                                    title = str(S.desktop_card_request_a_top_up),
                                    label = str(S.desktop_card_how_much_more),
                                ),
                            ),
                        )
                    },
                    leadingIcon = ZillitIcons.Add,
                    enabled = !state.busy,
                )
            }
        }

        ZillitSectionCard(
            title = str(S.desktop_ce_topups_on_this_float),
            icon = ZillitIcons.Ledger,
            padded = false,
        ) {
            ZillitDataTable(
                rows = state.floatTopUps,
                columns = topUpColumns(showHolder = false),
                key = { it.id },
                loading = state.loading,
                emptyTitle = str(S.desktop_card_no_topups_requested),
                emptyMessage = str(S.desktop_ce_topups_empty),
                virtualised = false,
            )
        }
    }
}

/** The accountant's top-up inbox: complete in full, part-pay, or skip. */
@Composable
fun TopUpsPage(state: CashUiState, onEvent: (CashEvent) -> Unit) {
    FixedPage {
        ZillitSectionCard(
            title = str(S.desktop_card_topup_requests),
            icon = ZillitIcons.Wallet,
            meta = str(S.desktop_card_pending_count, state.topUps.count { it.status == PENDING }),
            padded = false,
            modifier = Modifier.weight(1f),
        ) {
            ZillitDataTable(
                rows = state.topUps,
                columns = topUpColumns(showHolder = true) + topUpActionColumn(state, onEvent),
                key = { it.id },
                loading = state.loading,
                emptyTitle = str(S.desktop_nothing_waiting),
                emptyMessage = str(S.desktop_ce_topup_queue_empty),
            )
        }
    }
}

@Suppress("LongMethod") // Three row actions, each with its own confirmation.
private fun topUpActionColumn(
    state: CashUiState,
    onEvent: (CashEvent) -> Unit,
): TableColumn<CashTopUp> = TableColumn(
    header = "",
    width = ColumnWidth.Fixed(TOPUP_ACTION_COLUMN),
    cell = { row ->
        val holder = LocalCashPeople.current.nameOrNull(row.userId, row.holderName) ?: str(S.desktop_ce_the_holder)
        if (row.status != PENDING) {
            ZillitText(
                text = "—",
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
            )
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
                ZillitButton(
                    text = str(S.desktop_pay),
                    onClick = {
                        onEvent(
                            CashEvent.Ask(
                                CashPrompt.Confirm(
                                    action = ConfirmAction.CompleteTopUp,
                                    targetId = row.id,
                                    title = str(S.desktop_ce_complete_topup),
                                    message = str(
                                        S.desktop_ce_record_topup_handed,
                                        money(row.amount, row.currency),
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
                    text = str(S.desktop_card_part),
                    onClick = {
                        onEvent(
                            CashEvent.Ask(
                                CashPrompt.WithAmount(
                                    action = AmountAction.PartialTopUp,
                                    targetId = row.id,
                                    title = str(S.ah_partial_topup),
                                    label = str(S.desktop_ce_amount_handed_over),
                                ),
                            ),
                        )
                    },
                    variant = ButtonVariant.Secondary,
                    size = ButtonSize.Small,
                    enabled = !state.busy,
                )
                ZillitButton(
                    text = str(S.skip),
                    onClick = {
                        onEvent(
                            CashEvent.Ask(
                                CashPrompt.Confirm(
                                    action = ConfirmAction.SkipTopUp,
                                    targetId = row.id,
                                    title = str(S.desktop_card_skip_this_topup),
                                    message = str(S.desktop_ce_skip_topup_note),
                                ),
                            ),
                        )
                    },
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                    enabled = !state.busy,
                )
            }
        }
    },
)

@Suppress("MagicNumber") // Column proportions; naming each would not clarify them.
private fun topUpColumns(showHolder: Boolean): List<TableColumn<CashTopUp>> = buildList {
    if (showHolder) {
        // The web treats a top-up's `holder_name` as the holder's id when
        // `user_id` is missing, so it is only a fallback for the lookup.
        add(personColumn(str(S.ah_holder), ColumnWidth.Weight(1.6f), userId = { it.userId }) { it.holderName })
    }
    add(textColumn(str(S.ah_col_float), ColumnWidth.Weight(1f), muted = true) { it.floatRequestNumber ?: "—" })
    add(textColumn(str(S.av_chip_requested), ColumnWidth.Weight(1f), numeric = true) { money(it.amount, it.currency) })
    add(
        textColumn(str(S.desktop_ce_float_balance), ColumnWidth.Weight(1f), numeric = true) {
            money(it.floatBalance, it.currency)
        },
    )
    add(textColumn(str(S.desktop_card_raised), ColumnWidth.Weight(1f), muted = true) { date(it.createdAt) })
    add(
        TableColumn(
            header = str(S.status),
            width = ColumnWidth.Fixed(TOPUP_STATUS_COLUMN),
            cell = { row ->
                ZillitStatusPill(
                    label = row.status.replaceFirstChar { it.uppercase() }.ifBlank { str(S.pending) },
                    tone = when (row.status) {
                        COMPLETED -> StatusTone.Done
                        PARTIAL -> StatusTone.Progress
                        SKIPPED -> StatusTone.Neutral
                        else -> StatusTone.Pending
                    },
                    dot = true,
                )
            },
        ),
    )
}

@Suppress("MagicNumber") // Column proportions; naming each would not clarify them.
private fun crewFloatColumns(): List<TableColumn<CashFloat>> = listOf(
    textColumn(str(S.desktop_reference), ColumnWidth.Weight(1f)) { it.requestNumber.ifBlank { "—" } },
    textColumn(str(S.av_chip_requested), ColumnWidth.Weight(1f), numeric = true) {
        money(it.requestedAmount, it.currency)
    },
    textColumn(str(S.desktop_issued), ColumnWidth.Weight(1f), numeric = true) { money(it.issuedAmount, it.currency) },
    textColumn(str(S.ah_balance_label), ColumnWidth.Weight(1f), numeric = true) { money(it.balance, it.currency) },
    textColumn(str(S.desktop_ce_asked_on), ColumnWidth.Weight(1f), muted = true) { date(it.createdAt) },
    TableColumn(
        header = str(S.status),
        width = ColumnWidth.Fixed(FLOAT_STATUS_COLUMN),
        cell = { FloatStatusPill(it.status) },
    ),
)

/** By the name on screen, which is the crew list's rather than the row's — see [CashPeople]. */
private fun CashFloat.matches(query: String, people: CashPeople): Boolean {
    if (query.isBlank()) return true
    val needle = query.trim().lowercase()
    return people.nameOrNull(userId, holderName).orEmpty().lowercase().contains(needle) ||
        requestNumber.lowercase().contains(needle) ||
        purpose?.lowercase()?.contains(needle) == true
}

private val DURATION_TYPES = listOf("days", "weeks", "months")

private const val PENDING = "pending"
private const val COMPLETED = "completed"
private const val PARTIAL = "partial"
private const val SKIPPED = "skipped"

private val SEARCH_WIDTH = 300.dp
private val ACTION_COLUMN = 150.dp
private val TOPUP_ACTION_COLUMN = 190.dp
private val TOPUP_STATUS_COLUMN = 120.dp
private val FLOAT_STATUS_COLUMN = 150.dp


/**
 * The extra fields this production added to a float request.
 *
 * Plain text boxes whatever the field says its type is. The template offers
 * seven types and a source for a select, and honouring those properly means
 * pickers this form does not have; typing a date into a text box is worse than
 * an unconfigured field but better than a control that sends the wrong shape.
 */
@Composable
private fun ColumnScope.FloatCustomFields(state: CashUiState, onEvent: (CashEvent) -> Unit) {
    val fields = state.floatForm.custom(CashFormFields.FLOAT_REQUEST)
    if (fields.isEmpty()) return
    val draft = state.floatDraft

    fields.forEach { field ->
        ZillitTextField(
            value = draft.customFields[field.label].orEmpty(),
            onValueChange = { text ->
                onEvent(
                    CashEvent.EditFloatRequest(
                        draft.copy(customFields = draft.customFields + (field.label to text)),
                    ),
                )
            },
            label = if (field.required) str(S.desktop_ce_field_required, field.name) else field.name,
            helperText = field.typeLabel.takeIf { field.knownType == null },
            singleLine = false,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * The required fields this form cannot offer.
 *
 * A template can mark one required that only the web's larger form renders — a
 * collection date, an episode, a request on somebody else's behalf. Saying so
 * is the honest treatment: the request is not blocked here, because there
 * would be nothing on screen to put right, and the server decides.
 */
@Composable
private fun ColumnScope.FloatUnansweredNotice(state: CashUiState) {
    val missing = state.floatForm
        .requiredMissing(CashFormFields.FLOAT_REQUEST, CashFormFields.RENDERED)
    if (missing.isEmpty()) return
    ZillitNotice(
        text = str(S.desktop_ce_web_only_fields, missing.joinToString(", ") { it.name }),
        tone = StatusTone.Pending,
        icon = ZillitIcons.Info,
    )
}
