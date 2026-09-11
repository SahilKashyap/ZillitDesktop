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
import com.zillit.desktop.feature.cashexpenses.domain.CashFloat
import com.zillit.desktop.feature.cashexpenses.domain.CashTopUp
import com.zillit.desktop.feature.cashexpenses.domain.FloatStatus
import com.zillit.desktop.feature.cashexpenses.ui.AmountAction
import com.zillit.desktop.feature.cashexpenses.domain.CashFormFields
import com.zillit.desktop.feature.cashexpenses.ui.CashEvent
import com.zillit.desktop.feature.cashexpenses.ui.CashPrompt
import com.zillit.desktop.feature.cashexpenses.ui.CashUiState
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
    val rows = state.activeFloats.filter { it.matches(state.search) }

    FixedPage {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitSearchField(
                value = state.search,
                onValueChange = { onEvent(CashEvent.Search(it)) },
                placeholder = "Search by holder or reference",
                modifier = Modifier.width(SEARCH_WIDTH),
            )
            ZillitText(
                text = "${rows.size} float${if (rows.size == 1) "" else "s"}",
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textSecondary,
            )
        }

        ZillitSectionCard(
            title = "Active floats",
            icon = ZillitIcons.Wallet,
            padded = false,
            modifier = Modifier.weight(1f),
        ) {
            ZillitDataTable(
                rows = rows,
                columns = floatColumns() + floatActionColumn(state, onEvent),
                key = { it.id },
                loading = state.loading,
                emptyTitle = if (state.search.isBlank()) "No active floats" else "No floats match that search",
                emptyMessage = if (state.search.isBlank()) {
                    "Approved float requests appear here once they are issued."
                } else {
                    "Clear the search to see every float."
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
        val action = row.nextAction()
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
                                message = action.message(row),
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
    val message: (CashFloat) -> String,
)

private fun CashFloat.nextAction(): FloatAction? = when (status) {
    FloatStatus.Approved, FloatStatus.AcctOverride -> FloatAction(
        label = "Ready to collect",
        action = ConfirmAction.ReadyToCollect,
        message = { "Tell ${it.holderName.ifBlank { "the holder" }} their cash is ready to pick up." },
    )

    FloatStatus.ReadyToCollect -> FloatAction(
        label = "Mark collected",
        action = ConfirmAction.CollectFloat,
        message = { "Record that ${money(it.requestedAmount, it.currency)} was handed over." },
    )

    FloatStatus.Spent, FloatStatus.PendingReturn -> FloatAction(
        label = "Close float",
        action = ConfirmAction.CloseFloat,
        message = { "Closing is final. Any outstanding return must be recorded first." },
    )

    FloatStatus.AwaitingApproval -> FloatAction(
        label = "Issue",
        action = ConfirmAction.IssueFloat,
        message = { "Issue this float without waiting for the approval chain." },
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
                text = "You already hold ${existing.size} float(s). " +
                    "Ask for a top-up on Cash Extension rather than a second float.",
                tone = StatusTone.Pending,
                icon = ZillitIcons.Wallet,
            )
        }

        ZillitSectionCard(title = "Request a float", icon = ZillitIcons.Wallet) {
            // The amount and the purpose are what a float *is*; the form
            // template can require them but never take them away, because a
            // request without either is not a request.
            ZillitTextField(
                value = draft.amount,
                onValueChange = { onEvent(CashEvent.EditFloatRequest(draft.copy(amount = it))) },
                label = "Amount needed",
                placeholder = "0.00",
                keyboardType = KeyboardType.Decimal,
                modifier = Modifier.fillMaxWidth(),
            )
            ZillitTextField(
                value = draft.purpose,
                onValueChange = { onEvent(CashEvent.EditFloatRequest(draft.copy(purpose = it))) },
                label = "What is it for",
                placeholder = "Set dressing consumables for the week",
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
                                "How long for (required)"
                            } else {
                                "How long for"
                            },
                            placeholder = "2",
                            keyboardType = KeyboardType.Number,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (shows(CashFormFields.DURATION_TYPE)) {
                        Column(modifier = Modifier.weight(1f)) {
                            ZillitText(
                                text = "Unit",
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
                    text = "Send request",
                    onClick = { onEvent(CashEvent.SubmitFloatRequest) },
                    leadingIcon = ZillitIcons.Send,
                    loading = state.busy,
                )
                ZillitButton(
                    text = "Clear",
                    onClick = { onEvent(CashEvent.EditFloatRequest(FloatRequestDraft())) },
                    variant = ButtonVariant.Tertiary,
                )
            }
        }

        ZillitSectionCard(title = "Your floats", icon = ZillitIcons.Ledger, padded = false) {
            ZillitDataTable(
                rows = state.myFloats,
                columns = crewFloatColumns(),
                key = { it.id },
                loading = state.loading,
                emptyTitle = "No floats yet",
                emptyMessage = "Once a request is approved it appears here with its balance.",
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
@Composable
fun CashExtensionPage(state: CashUiState, onEvent: (CashEvent) -> Unit) {
    val activeFloat = state.myFloats.firstOrNull { it.status.isOutstanding }

    ScrollingPage {
        if (activeFloat == null) {
            ZillitNotice(
                text = "You have no float to extend. Request one first.",
                tone = StatusTone.Progress,
            )
            return@ScrollingPage
        }

        ZillitSectionCard(
            title = "Top up ${activeFloat.requestNumber}",
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
                        text = "remaining of ${money(activeFloat.issuedAmount, activeFloat.currency)}",
                        style = ZillitTheme.typography.bodySmall,
                        color = ZillitTheme.colors.textSecondary,
                    )
                }
                ZillitButton(
                    text = "Request top-up",
                    onClick = {
                        onEvent(
                            CashEvent.Ask(
                                CashPrompt.WithAmount(
                                    action = AmountAction.RequestFloatTopUp,
                                    targetId = activeFloat.id,
                                    title = "Request a top-up",
                                    label = "How much more do you need",
                                ),
                            ),
                        )
                    },
                    leadingIcon = ZillitIcons.Add,
                    enabled = !state.busy,
                )
            }
        }

        ZillitSectionCard(title = "Top-ups on this float", icon = ZillitIcons.Ledger, padded = false) {
            ZillitDataTable(
                rows = state.floatTopUps,
                columns = topUpColumns(showHolder = false),
                key = { it.id },
                loading = state.loading,
                emptyTitle = "No top-ups requested",
                emptyMessage = "Every top-up you ask for shows here with what the accounts team did with it.",
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
            title = "Top-up requests",
            icon = ZillitIcons.Wallet,
            meta = "${state.topUps.count { it.status == PENDING }} pending",
            padded = false,
            modifier = Modifier.weight(1f),
        ) {
            ZillitDataTable(
                rows = state.topUps,
                columns = topUpColumns(showHolder = true) + topUpActionColumn(state, onEvent),
                key = { it.id },
                loading = state.loading,
                emptyTitle = "Nothing waiting",
                emptyMessage = "Crew top-up requests land here as they are raised.",
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
        if (row.status != PENDING) {
            ZillitText(
                text = "—",
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
            )
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
                ZillitButton(
                    text = "Pay",
                    onClick = {
                        onEvent(
                            CashEvent.Ask(
                                CashPrompt.Confirm(
                                    action = ConfirmAction.CompleteTopUp,
                                    targetId = row.id,
                                    title = "Complete top-up",
                                    message = "Record ${money(row.amount, row.currency)} handed to " +
                                        "${row.holderName.ifBlank { "the holder" }}.",
                                ),
                            ),
                        )
                    },
                    size = ButtonSize.Small,
                    enabled = !state.busy,
                )
                ZillitButton(
                    text = "Part",
                    onClick = {
                        onEvent(
                            CashEvent.Ask(
                                CashPrompt.WithAmount(
                                    action = AmountAction.PartialTopUp,
                                    targetId = row.id,
                                    title = "Partial top-up",
                                    label = "Amount handed over",
                                ),
                            ),
                        )
                    },
                    variant = ButtonVariant.Secondary,
                    size = ButtonSize.Small,
                    enabled = !state.busy,
                )
                ZillitButton(
                    text = "Skip",
                    onClick = {
                        onEvent(
                            CashEvent.Ask(
                                CashPrompt.Confirm(
                                    action = ConfirmAction.SkipTopUp,
                                    targetId = row.id,
                                    title = "Skip this top-up",
                                    message = "The request is closed without cash changing hands.",
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
        add(textColumn("Holder", ColumnWidth.Weight(1.3f)) { it.holderName.ifBlank { it.userId } })
    }
    add(textColumn("Float", ColumnWidth.Weight(1f), muted = true) { it.floatRequestNumber ?: "—" })
    add(textColumn("Requested", ColumnWidth.Weight(1f), numeric = true) { money(it.amount, it.currency) })
    add(textColumn("Float balance", ColumnWidth.Weight(1f), numeric = true) { money(it.floatBalance, it.currency) })
    add(textColumn("Raised", ColumnWidth.Weight(1f), muted = true) { date(it.createdAt) })
    add(
        TableColumn(
            header = "Status",
            width = ColumnWidth.Fixed(TOPUP_STATUS_COLUMN),
            cell = { row ->
                ZillitStatusPill(
                    label = row.status.replaceFirstChar { it.uppercase() }.ifBlank { "Pending" },
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
    textColumn("Reference", ColumnWidth.Weight(1f)) { it.requestNumber.ifBlank { "—" } },
    textColumn("Requested", ColumnWidth.Weight(1f), numeric = true) { money(it.requestedAmount, it.currency) },
    textColumn("Issued", ColumnWidth.Weight(1f), numeric = true) { money(it.issuedAmount, it.currency) },
    textColumn("Balance", ColumnWidth.Weight(1f), numeric = true) { money(it.balance, it.currency) },
    textColumn("Asked on", ColumnWidth.Weight(1f), muted = true) { date(it.createdAt) },
    TableColumn(
        header = "Status",
        width = ColumnWidth.Fixed(FLOAT_STATUS_COLUMN),
        cell = { FloatStatusPill(it.status) },
    ),
)

private fun CashFloat.matches(query: String): Boolean {
    if (query.isBlank()) return true
    val needle = query.trim().lowercase()
    return holderName.lowercase().contains(needle) ||
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
            label = if (field.required) "${field.name} (required)" else field.name,
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
        text = "This production also requires ${missing.joinToString(", ") { it.name }} on a " +
            "float request. Those are filled in on the web, not here, so this request may " +
            "come back.",
        tone = StatusTone.Pending,
        icon = ZillitIcons.Info,
    )
}
