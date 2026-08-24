package com.zillit.desktop.feature.dealmemo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.common.EpochDate
import com.zillit.desktop.core.common.Money
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ColumnWidth
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.TableColumn
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDataTable
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitErrorState
import com.zillit.desktop.core.designsystem.component.ZillitErrorToast
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitPageHeader
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitTab
import com.zillit.desktop.core.designsystem.component.ZillitTabStrip
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.component.ZillitToast
import com.zillit.desktop.core.designsystem.component.ZillitToastTone
import com.zillit.desktop.core.designsystem.component.textColumn
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.workspace.OpenMode
import com.zillit.desktop.core.workspace.ToolProvider
import com.zillit.desktop.core.workspace.WindowNavigator
import com.zillit.desktop.core.workspace.WorkspaceRoute
import com.zillit.desktop.feature.dealmemo.domain.Deal
import com.zillit.desktop.feature.dealmemo.domain.DealStatus
import com.zillit.desktop.feature.dealmemo.domain.RateCardEntry
import com.zillit.desktop.feature.dealmemo.domain.RateSource
import com.zillit.desktop.feature.dealmemo.domain.ResolvedTier

/**
 * The Deal Memo tool.
 *
 * Two audiences, sharply separated: a crew member sees their own terms and
 * nothing else, and whoever writes deals sees the production's. That
 * separation is the tool's main obligation — everyone's rates in one list is
 * exactly what must not leak.
 */
@Composable
fun DealMemoScreen(
    state: DealUiState,
    onEvent: (DealEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize().background(ZillitTheme.colors.canvas)) {
        Column(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ZillitTheme.colors.surface)
                    .padding(horizontal = ZillitTheme.spacing.xl, vertical = ZillitTheme.spacing.lg),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
            ) {
                ZillitPageHeader(
                    eyebrow = "Production",
                    title = "Deal Memos",
                    description = "The agreed terms behind every timecard, payroll line and budget commitment.",
                    actions = {
                        ZillitButton(
                            text = "Refresh",
                            onClick = { onEvent(DealEvent.Refresh) },
                            variant = ButtonVariant.Tertiary,
                            size = ButtonSize.Small,
                            leadingIcon = ZillitIcons.Reload,
                            loading = state.loading,
                        )
                    },
                )
                if (state.visibleDestinations.size > 1) {
                    ZillitTabStrip(
                        tabs = state.visibleDestinations.map { ZillitTab(it.slug, it.label) },
                        activeId = state.destination.slug,
                        onSelect = { slug ->
                            DealDestination.entries.firstOrNull { it.slug == slug }
                                ?.let { onEvent(DealEvent.Open(it)) }
                        },
                    )
                }
            }
            ZillitDivider()

            val error = state.error
            when {
                error != null -> ZillitErrorState(
                    message = error.userMessage,
                    onRetry = { onEvent(DealEvent.Refresh) },
                )

                state.destination == DealDestination.MyDeal -> MyDealPage(state, onEvent)
                state.destination == DealDestination.Create -> CreatePage(state, onEvent)
                state.destination == DealDestination.RateCard -> RateCardPage(state, onEvent)
                else -> DealsPage(state, onEvent)
            }
        }

        DealPromptDialog(state.prompt, onEvent)
        ZillitToast(
            message = state.notice,
            onDismiss = { onEvent(DealEvent.ClearNotice) },
            tone = ZillitToastTone.Success,
        )
    }
}

/** The crew member's own terms, and the confirmation they are asked for. */
@Suppress("LongMethod") // One person's terms, top to bottom.
@Composable
private fun MyDealPage(state: DealUiState, onEvent: (DealEvent) -> Unit) {
    val deal = state.myDeal

    ZillitScrollColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(ZillitTheme.spacing.xl),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
    ) {
        if (deal == null) {
            ZillitEmptyState(
                title = "No deal on file yet",
                message = "Your terms appear here once the production office writes them up.",
                icon = ZillitIcons.File,
            )
            return@ZillitScrollColumn
        }

        if (deal.awaitingReacknowledgement) {
            ZillitNotice(
                text = "These terms were amended after you agreed to them. Read them again and confirm.",
                tone = StatusTone.Pending,
                icon = ZillitIcons.Warning,
            )
        }

        ZillitSectionCard(
            // Both are translation keys on the wire — `driver_label`,
            // `transportation_department_label`.
            title = deal.designation?.takeIf { it.isNotBlank() }?.localised() ?: "Your engagement",
            icon = ZillitIcons.File,
            meta = deal.departmentName?.localised(),
            action = { ZillitStatusPill(deal.status.label, tone = deal.status.tone, dot = true) },
        ) {
            ZillitText(
                text = Money.format(deal.rates.estimatedWeek.takeIf { it > 0 }, deal.currency),
                style = ZillitTheme.typography.displayLarge,
            )
            ZillitText(
                text = "per week" + (
                    deal.rates.daysPerWeek.takeIf { it > 0 }?.let { " · $it day week" } ?: ""
                    ),
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textSecondary,
            )
            Spacer(Modifier.padding(ZillitTheme.spacing.xs))
            ZillitDivider()
            RateLine("Weekly rate", deal.rates.weeklyRate, deal.currency)
            RateLine("Daily rate", deal.rates.dailyRate, deal.currency)
            RateLine("Overtime", deal.rates.overtimeRate, deal.currency)
            RateLine("Box rental", deal.rates.boxRental, deal.currency)
            RateLine("Vehicle allowance", deal.rates.vehicleAllowance, deal.currency)
            if (deal.rates.standardHours > 0) {
                PlainLine("Standard day", "${deal.rates.standardHours} hours")
            }
            deal.unionName?.takeIf { it.isNotBlank() }?.let { PlainLine("Union", it) }
            deal.agreementName?.takeIf { it.isNotBlank() }?.let { PlainLine("Agreement", it) }
            PlainLine("Dates", "${EpochDate.date(deal.startDate).ifEmpty { "—" }} to " +
                EpochDate.date(deal.endDate).ifEmpty { "open" })
        }

        deal.notes?.takeIf { it.isNotBlank() }?.let {
            ZillitSectionCard(title = "Notes", icon = ZillitIcons.Info) {
                ZillitText(text = it, style = ZillitTheme.typography.bodyMedium)
            }
        }

        if (!deal.acknowledged) {
            ZillitButton(
                text = "I agree to these terms",
                onClick = {
                    onEvent(
                        DealEvent.Ask(
                            DealPrompt.Confirm(
                                DealConfirmAction.Acknowledge,
                                deal.id,
                                "Confirm these terms",
                                "Your agreement is recorded against the deal and dated.",
                            ),
                        ),
                    )
                },
                leadingIcon = ZillitIcons.Check,
                loading = state.busy,
            )
        } else {
            ZillitNotice(
                text = "You agreed to these terms on ${EpochDate.date(deal.acknowledgedAt)}.",
                tone = StatusTone.Done,
                icon = ZillitIcons.Check,
            )
        }
    }
}

@Suppress("LongMethod") // Filters, list and detail: one screen read together.
@Composable
private fun DealsPage(state: DealUiState, onEvent: (DealEvent) -> Unit) {
    val rows = state.rows
    val selected = state.selected

    Column(
        modifier = Modifier.fillMaxSize().padding(ZillitTheme.spacing.xl),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
    ) {
        if (state.awaitingAcknowledgement.isNotEmpty()) {
            ZillitNotice(
                text = "${state.awaitingAcknowledgement.size} deal(s) were amended and not re-confirmed by " +
                    "the crew member.",
                tone = StatusTone.Pending,
                icon = ZillitIcons.Warning,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitSearchField(
                value = state.search,
                onValueChange = { onEvent(DealEvent.Search(it)) },
                placeholder = "Search by crew member or role",
                modifier = Modifier.width(SEARCH_WIDTH),
            )
            ZillitSelect(
                value = state.statusFilter,
                options = listOf(null) + DealStatus.entries.filter { it != DealStatus.Unknown },
                onSelect = { onEvent(DealEvent.Filter(it)) },
                label = { it?.label ?: "Every status" },
            )
            ZillitText(
                text = "${rows.size} deal${if (rows.size == 1) "" else "s"}",
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textSecondary,
                modifier = Modifier.weight(1f),
            )
            ZillitButton(
                text = "New deal",
                onClick = { onEvent(DealEvent.Open(DealDestination.Create)) },
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Add,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
        ) {
            ZillitSectionCard(
                title = "Deals",
                icon = ZillitIcons.File,
                padded = false,
                modifier = Modifier.weight(LIST_WEIGHT).fillMaxHeight(),
            ) {
                ZillitDataTable(
                    rows = rows,
                    columns = dealColumns(),
                    key = { it.id },
                    loading = state.loading,
                    onRowClick = { onEvent(DealEvent.Select(it.id)) },
                    isSelected = { it.id == state.selectedId },
                    emptyTitle = if (state.search.isBlank()) "No deals yet" else "Nothing matches that search",
                    emptyMessage = "Deals written for this production appear here.",
                )
            }

            ZillitSectionCard(
                title = "Deal detail",
                icon = ZillitIcons.Eye,
                padded = false,
                modifier = Modifier.weight(DETAIL_WEIGHT).fillMaxHeight(),
            ) {
                if (selected == null) {
                    ZillitEmptyState(
                        title = "Pick a deal",
                        message = "Its rates, agreement and history show here.",
                        icon = ZillitIcons.Eye,
                    )
                } else {
                    DealDetail(state, selected, onEvent)
                }
            }
        }
    }
}

@Suppress("LongMethod") // One deal, top to bottom; the order is the reading order.
@Composable
private fun DealDetail(state: DealUiState, deal: Deal, onEvent: (DealEvent) -> Unit) {
    ZillitScrollColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(ZillitTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                ZillitText(
                    text = deal.crewName.ifBlank { deal.userId },
                    style = ZillitTheme.typography.titleMedium,
                )
                ZillitText(
                    text = listOfNotNull(deal.designation, deal.departmentName)
                        .map { it.localised() }
                        .joinToString(" · ")
                        .ifBlank { "—" },
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textSecondary,
                )
            }
            ZillitStatusPill(deal.status.label, tone = deal.status.tone, dot = true)
        }

        ZillitText(
            text = Money.format(deal.rates.estimatedWeek.takeIf { it > 0 }, deal.currency),
            style = ZillitTheme.typography.displayLarge,
        )

        if (deal.awaitingReacknowledgement) {
            ZillitNotice(
                text = "Amended after acknowledgement — the crew member has not confirmed the new terms.",
                tone = StatusTone.Pending,
                icon = ZillitIcons.Warning,
            )
        }

        ZillitDivider()
        RateLine("Weekly", deal.rates.weeklyRate, deal.currency)
        RateLine("Daily", deal.rates.dailyRate, deal.currency)
        RateLine("Overtime", deal.rates.overtimeRate, deal.currency)
        RateLine("Box rental", deal.rates.boxRental, deal.currency)
        deal.nominalCode?.takeIf { it.isNotBlank() }?.let { PlainLine("Nominal code", it) }
        deal.unionName?.takeIf { it.isNotBlank() }?.let { PlainLine("Union", it) }

        if (state.history.isNotEmpty()) {
            ZillitDivider()
            ZillitText(text = "History", style = ZillitTheme.typography.titleSmall)
            state.history.take(HISTORY_ROWS).forEach { entry ->
                ZillitText(
                    text = "${EpochDate.date(entry.at).ifEmpty { "—" }} · ${entry.action}" +
                        (entry.note?.let { " — $it" } ?: ""),
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textMuted,
                )
            }
        }

        if (state.viewer.canWriteDeals && deal.status != DealStatus.Terminated) {
            ZillitDivider()
            ZillitButton(
                text = "Send to crew member",
                onClick = {
                    onEvent(
                        DealEvent.Ask(
                            DealPrompt.Confirm(
                                DealConfirmAction.SendToCrew,
                                deal.id,
                                "Send these terms",
                                "${deal.crewName.ifBlank { "The crew member" }} is notified and asked " +
                                    "to confirm them.",
                            ),
                        ),
                    )
                },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                enabled = !state.busy,
            )
        }
    }
}

@Suppress("LongMethod") // One form; splitting the rates from the header hides the shape.
@Composable
private fun CreatePage(state: DealUiState, onEvent: (DealEvent) -> Unit) {
    val draft = state.draft

    ZillitScrollColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(ZillitTheme.spacing.xl),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
    ) {
        ZillitSectionCard(title = "Who the deal is for", icon = ZillitIcons.Users) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            ) {
                ZillitTextField(
                    value = draft.crewName,
                    onValueChange = { onEvent(DealEvent.EditDraft(draft.copy(crewName = it))) },
                    label = "Crew member",
                    modifier = Modifier.weight(1f),
                )
                ZillitTextField(
                    value = draft.userId,
                    onValueChange = { onEvent(DealEvent.EditDraft(draft.copy(userId = it))) },
                    label = "User id",
                    modifier = Modifier.weight(1f),
                )
                ZillitTextField(
                    value = draft.designation,
                    onValueChange = { onEvent(DealEvent.EditDraft(draft.copy(designation = it))) },
                    label = "Role",
                    modifier = Modifier.weight(1f),
                )
            }
        }

        ZillitSectionCard(title = "Terms", icon = ZillitIcons.Ledger) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            ) {
                RateField("Weekly rate", draft.weeklyRate, Modifier.weight(1f)) {
                    onEvent(DealEvent.EditDraft(draft.copy(weeklyRate = it)))
                }
                RateField("Daily rate", draft.dailyRate, Modifier.weight(1f)) {
                    onEvent(DealEvent.EditDraft(draft.copy(dailyRate = it)))
                }
                RateField("Overtime rate", draft.overtimeRate, Modifier.weight(1f)) {
                    onEvent(DealEvent.EditDraft(draft.copy(overtimeRate = it)))
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            ) {
                RateField("Standard hours", draft.standardHours, Modifier.weight(1f)) {
                    onEvent(DealEvent.EditDraft(draft.copy(standardHours = it)))
                }
                RateField("Days per week", draft.daysPerWeek, Modifier.weight(1f)) {
                    onEvent(DealEvent.EditDraft(draft.copy(daysPerWeek = it)))
                }
                RateField("Box rental", draft.boxRental, Modifier.weight(1f)) {
                    onEvent(DealEvent.EditDraft(draft.copy(boxRental = it)))
                }
            }
        }

        RateLookupPanel(state, onEvent)

        ZillitSectionCard(title = "Agreement", icon = ZillitIcons.Shield) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                verticalAlignment = Alignment.Bottom,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    ZillitText(
                        text = "Union",
                        style = ZillitTheme.typography.label,
                        color = ZillitTheme.colors.textSecondary,
                    )
                    ZillitSelect(
                        value = state.unions.firstOrNull { it.id == draft.unionId },
                        options = state.unions,
                        onSelect = { onEvent(DealEvent.EditDraft(draft.copy(unionId = it?.id))) },
                        label = { it?.name ?: "None" },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    ZillitText(
                        text = "Agreement",
                        style = ZillitTheme.typography.label,
                        color = ZillitTheme.colors.textSecondary,
                    )
                    ZillitSelect(
                        value = state.agreements.firstOrNull { it.id == draft.agreementId },
                        options = state.agreements,
                        onSelect = { onEvent(DealEvent.EditDraft(draft.copy(agreementId = it?.id))) },
                        label = { it?.name ?: "None" },
                        // Agreements belong to a union, so the list is empty
                        // until one is chosen rather than showing every
                        // agreement on the system.
                        enabled = draft.unionId != null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                ZillitTextField(
                    value = draft.nominalCode,
                    onValueChange = { onEvent(DealEvent.EditDraft(draft.copy(nominalCode = it))) },
                    label = "Nominal code",
                    modifier = Modifier.weight(1f),
                )
            }
            ZillitTextField(
                value = draft.notes,
                onValueChange = { onEvent(DealEvent.EditDraft(draft.copy(notes = it))) },
                label = "Notes (optional)",
                singleLine = false,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.padding(ZillitTheme.spacing.xs))
            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                ZillitButton(
                    text = "Save and send",
                    onClick = { onEvent(DealEvent.SubmitDraft(notify = true)) },
                    leadingIcon = ZillitIcons.Send,
                    loading = state.busy,
                )
                // Saving without notifying is the ordinary case while terms are
                // still being settled; sending is the deliberate act.
                ZillitButton(
                    text = "Save without sending",
                    onClick = { onEvent(DealEvent.SubmitDraft(notify = false)) },
                    variant = ButtonVariant.Secondary,
                    enabled = !state.busy,
                )
            }
        }
    }
}

/**
 * What the agreement says this role should be paid.
 *
 * Advisory, not enforced: a production can pay above scale and sometimes has
 * a reason to structure a deal differently. What it must not do is pay below
 * one without noticing, which is why an under-scale rate is called out in red
 * rather than silently accepted.
 */
@Suppress("LongMethod") // The lookup, its result and the envelope check are one panel.
@Composable
private fun RateLookupPanel(state: DealUiState, onEvent: (DealEvent) -> Unit) {
    val resolved = state.resolvedRate

    ZillitSectionCard(
        title = "Published rate",
        icon = ZillitIcons.BarChart,
        meta = resolved?.dayType,
        action = {
            ZillitButton(
                text = "Look up",
                onClick = { onEvent(DealEvent.ResolveRate) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                loading = state.busy,
            )
        },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.Bottom,
        ) {
            ZillitTextField(
                value = state.draft.departmentIdentifier.orEmpty(),
                onValueChange = {
                    onEvent(DealEvent.EditDraft(state.draft.copy(departmentIdentifier = it.takeIf(String::isNotBlank))))
                },
                label = "Department",
                modifier = Modifier.weight(1f),
            )
            ZillitTextField(
                value = state.draft.productionType.orEmpty(),
                onValueChange = {
                    onEvent(DealEvent.EditDraft(state.draft.copy(productionType = it.takeIf(String::isNotBlank))))
                },
                label = "Production type",
                modifier = Modifier.weight(1f),
            )
            ZillitTextField(
                value = state.draft.budget,
                onValueChange = { onEvent(DealEvent.EditDraft(state.draft.copy(budget = it))) },
                label = "Budget",
                placeholder = "Decides the tier",
                keyboardType = KeyboardType.Decimal,
                modifier = Modifier.weight(1f),
            )
        }

        if (state.rateLookupFailed) {
            ZillitNotice(
                text = "No published rate covers that role under this agreement. " +
                    "The deal can still be written; it simply is not on a rate card.",
                tone = StatusTone.Neutral,
                icon = ZillitIcons.Info,
            )
        }

        resolved?.let { rate ->
            ZillitDivider()
            TierLine("Weekly", rate.weekly, state.draft.currency)
            TierLine("Daily", rate.daily, state.draft.currency)
            TierLine("Hourly", rate.hourly, state.draft.currency)

            when (state.weeklyWithinEnvelope) {
                false -> ZillitNotice(
                    text = "The weekly rate on this deal is outside what the agreement permits.",
                    tone = StatusTone.Rejected,
                    icon = ZillitIcons.Warning,
                )

                true -> ZillitNotice(
                    text = "The weekly rate sits inside the agreement's envelope.",
                    tone = StatusTone.Done,
                    icon = ZillitIcons.Check,
                )

                null -> Unit
            }

            ZillitButton(
                text = "Use these rates",
                onClick = { onEvent(DealEvent.ApplyResolvedRate) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
            )
        }
    }
}

/** One resolved tier, with where each half of it came from. */
@Composable
private fun TierLine(label: String, tier: ResolvedTier?, currency: String?) {
    if (tier == null || tier.isEmpty) return
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitText(
            text = label,
            style = ZillitTheme.typography.bodyMedium,
            color = ZillitTheme.colors.textSecondary,
            modifier = Modifier.weight(1f),
        )
        tier.workHours?.let {
            ZillitText(
                text = "$it h",
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
            )
        }
        // Which authority set the figure, because "the agreement says so" and
        // "this role publishes its own" are different answers to a challenge.
        if (tier.baseSource == RateSource.Agreement) {
            ZillitStatusPill(label = "Agreement scale", tone = StatusTone.Neutral)
        }
        if (tier.minRate != null || tier.maxRate != null) {
            ZillitText(
                text = listOfNotNull(
                    tier.minRate?.let { "min ${Money.format(it, currency)}" },
                    tier.maxRate?.let { "max ${Money.format(it, currency)}" },
                ).joinToString(" · "),
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
            )
        }
        ZillitText(
            text = Money.format(tier.baseRate, currency),
            style = ZillitTheme.typography.numeric,
            maxLines = 1,
        )
    }
}

/** The production's whole rate card, browsable. */
@Composable
private fun RateCardPage(state: DealUiState, onEvent: (DealEvent) -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(ZillitTheme.spacing.xl),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.width(SEARCH_WIDTH)) {
                ZillitText(
                    text = "Union",
                    style = ZillitTheme.typography.label,
                    color = ZillitTheme.colors.textSecondary,
                )
                ZillitSelect(
                    value = state.unions.firstOrNull { it.id == state.draft.unionId },
                    options = state.unions,
                    onSelect = { union ->
                        onEvent(DealEvent.EditDraft(state.draft.copy(unionId = union?.id)))
                        onEvent(DealEvent.BrowseRateCard(state.draft.departmentIdentifier))
                    },
                    label = { it?.name ?: "Every union" },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            ZillitText(
                text = "${state.rateCard.size} published rate(s)",
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textSecondary,
                modifier = Modifier.weight(1f),
            )
        }

        ZillitSectionCard(
            title = "Rate card",
            icon = ZillitIcons.BarChart,
            padded = false,
            modifier = Modifier.weight(1f),
        ) {
            ZillitDataTable(
                rows = state.rateCard,
                columns = rateColumns(),
                key = { it.id },
                loading = state.loading,
                emptyTitle = "No rates published",
                emptyMessage = "Rates appear here once a union's card is loaded for this production.",
            )
        }
    }
}

@Suppress("MagicNumber") // Column proportions.
private fun rateColumns(): List<TableColumn<RateCardEntry>> = listOf(
    textColumn("Department", ColumnWidth.Weight(1.3f)) { it.departmentIdentifier },
    textColumn("Role", ColumnWidth.Weight(1.5f)) { it.designationIdentifier },
    textColumn("Production", ColumnWidth.Weight(1f), muted = true) { it.productionType ?: "Any" },
    TableColumn(
        header = "Budget band",
        width = ColumnWidth.Weight(1.2f),
        cell = { row ->
            // An open-ended envelope is the card's default scale; saying "any"
            // is clearer than two blanks.
            ZillitText(
                text = when {
                    row.minBudget == null && row.maxBudget == null -> "Any"
                    else -> listOfNotNull(
                        row.minBudget?.let { Money.compact(it, row.currency) },
                        row.maxBudget?.let { Money.compact(it, row.currency) },
                    ).joinToString(" – ")
                },
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
                maxLines = 1,
            )
        },
    ),
    textColumn("Weekly", ColumnWidth.Weight(1f), numeric = true) {
        Money.format(it.weekly?.baseRate, it.currency)
    },
    textColumn("Daily", ColumnWidth.Weight(1f), numeric = true) {
        Money.format(it.daily?.baseRate, it.currency)
    },
    textColumn("Hours", ColumnWidth.Weight(0.7f), numeric = true) {
        it.daily?.workHours?.toString() ?: "—"
    },
)

@Composable
private fun RateField(label: String, value: String, modifier: Modifier, onChange: (String) -> Unit) {
    ZillitTextField(
        value = value,
        onValueChange = onChange,
        label = label,
        placeholder = "0.00",
        keyboardType = KeyboardType.Decimal,
        modifier = modifier,
    )
}

@Composable
private fun RateLine(label: String, amount: Double, currency: String?) {
    // Zero rates are omitted rather than printed: a deal with no box rental
    // should not read as one with a box rental of nothing.
    if (amount <= 0) return
    PlainLine(label, Money.format(amount, currency))
}

@Composable
private fun PlainLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitText(
            text = label,
            style = ZillitTheme.typography.bodyMedium,
            color = ZillitTheme.colors.textSecondary,
            modifier = Modifier.weight(1f),
        )
        ZillitText(text = value, style = ZillitTheme.typography.numeric, maxLines = 1)
    }
}

@Composable
private fun DealPromptDialog(prompt: DealPrompt?, onEvent: (DealEvent) -> Unit) {
    val shown = remember(prompt) { prompt }
    ZillitDialogShell(
        title = (shown as? DealPrompt.Confirm)?.title.orEmpty(),
        icon = ZillitIcons.Info,
        visible = prompt != null,
        onDismiss = { onEvent(DealEvent.DismissPrompt) },
    ) {
        (shown as? DealPrompt.Confirm)?.let {
            ZillitText(
                text = it.message,
                style = ZillitTheme.typography.bodyMedium,
                color = ZillitTheme.colors.textSecondary,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            Spacer(Modifier.weight(1f))
            ZillitButton(
                text = "Cancel",
                onClick = { onEvent(DealEvent.DismissPrompt) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = "Confirm",
                onClick = { onEvent(DealEvent.ConfirmPrompt) },
            )
        }
    }
}

internal val DealStatus.tone: StatusTone
    get() = when (this) {
        DealStatus.Draft, DealStatus.Unknown -> StatusTone.Neutral
        DealStatus.Sent -> StatusTone.InTransit
        DealStatus.Acknowledged -> StatusTone.Ready
        DealStatus.Amended -> StatusTone.Pending
        DealStatus.Active -> StatusTone.Done
        DealStatus.Expired -> StatusTone.Neutral
        DealStatus.Terminated -> StatusTone.Rejected
    }

@Suppress("MagicNumber") // Column proportions.
private fun dealColumns(): List<TableColumn<Deal>> = listOf(
    textColumn("Crew", ColumnWidth.Weight(1.5f)) { it.crewName.ifBlank { it.userId } },
    textColumn("Role", ColumnWidth.Weight(1.2f), muted = true) { it.designation ?: "—" },
    textColumn("Weekly", ColumnWidth.Weight(1f), numeric = true) {
        Money.format(it.rates.estimatedWeek.takeIf { rate -> rate > 0 }, it.currency)
    },
    textColumn("Starts", ColumnWidth.Weight(1f), muted = true) {
        EpochDate.date(it.startDate).ifEmpty { "—" }
    },
    TableColumn(
        header = "Agreed",
        width = ColumnWidth.Fixed(AGREED_COLUMN),
        cell = { deal ->
            ZillitStatusPill(
                label = if (deal.acknowledged) "Yes" else "Not yet",
                tone = if (deal.acknowledged) StatusTone.Done else StatusTone.Pending,
            )
        },
    ),
    TableColumn(
        header = "Status",
        width = ColumnWidth.Fixed(STATUS_COLUMN),
        cell = { ZillitStatusPill(it.status.label, tone = it.status.tone, dot = true) },
    ),
)

/** Deal Memos as a workspace window. */
class DealMemoToolProvider(
    private val viewModel: DealMemoViewModel,
) : ToolProvider {

    override val path: String = DEAL_MEMO_PATH
    override val title: String = "Deal Memos"
    override val icon = com.zillit.desktop.core.designsystem.icon.ZillitToolIcons.DealMemo
    override val openMode: OpenMode = OpenMode.Maximized
    override val hostsOwnRoutes: Boolean = true
    override val defaultSize: DpSize = DpSize(1360.dp, 860.dp)

    @Composable
    override fun Content(route: WorkspaceRoute, navigator: WindowNavigator) {
        val state by viewModel.state.collectAsState()
        var failure by remember { mutableStateOf<String?>(null) }

        LaunchedEffect(viewModel) { viewModel.start() }
        LaunchedEffect(viewModel) {
            viewModel.effects.collect { effect ->
                when (effect) {
                    is DealEffect.Failed -> failure = effect.message
                }
            }
        }
        LaunchedEffect(state.destination) {
            navigator.setTitle("Deal Memos · ${state.destination.label}")
        }

        DealMemoScreen(state = state, onEvent = viewModel::onEvent)
        ZillitErrorToast(message = failure, onDismiss = { failure = null })
    }
}

const val DEAL_MEMO_PATH = "/film-tools/deal-memo"

private const val LIST_WEIGHT = 1.6f
private const val DETAIL_WEIGHT = 1f
private const val HISTORY_ROWS = 6
private val SEARCH_WIDTH = 320.dp
private val STATUS_COLUMN = 140.dp
private val AGREED_COLUMN = 100.dp
