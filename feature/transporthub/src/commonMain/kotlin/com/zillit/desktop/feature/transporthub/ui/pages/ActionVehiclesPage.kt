// A register row carries the whole flow's state; splitting it hides the layout.
@file:Suppress("LongMethod", "TooManyFunctions")

package com.zillit.desktop.feature.transporthub.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitLazyColumn
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitTab
import com.zillit.desktop.core.designsystem.component.ZillitTabStrip
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.transporthub.data.hubDate
import com.zillit.desktop.feature.transporthub.domain.ActionVehicle
import com.zillit.desktop.feature.transporthub.domain.AvApprovalRound
import com.zillit.desktop.feature.transporthub.domain.AvStatus
import com.zillit.desktop.feature.transporthub.domain.avCategories
import com.zillit.desktop.feature.transporthub.ui.AvTab
import com.zillit.desktop.feature.transporthub.ui.AvFlow
import com.zillit.desktop.feature.transporthub.ui.HubEvent
import com.zillit.desktop.feature.transporthub.ui.kit.HubGlyphs
import com.zillit.desktop.feature.transporthub.ui.kit.HubPageHeader
import com.zillit.desktop.feature.transporthub.ui.HubUiState

/**
 * Coordinator → Action Vehicles.
 *
 * The register of everything that appears ON CAMERA — a hero car, its stunt
 * double, a period filler — and deliberately not the Fleet, which is what
 * carries crew.
 *
 * The flow is request → source → approval → on set → return, and the page is
 * the tabs of whoever is looking: an AD sees their own requests, a transport
 * captain sees the queue to source, an approver sees what they must decide.
 * A viewer who holds several of those sees all of their tabs at once — the
 * set is a union, not a choice.
 */
@Composable
internal fun ActionVehiclesPage(state: HubUiState, onEvent: (HubEvent) -> Unit) {
    val tabs = state.actionVehicleTabs
    val counts = state.actionVehicleCounts
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        HubPageHeader(
            icon = HubGlyphs.FilmSlate,
            title = str(S.action_vehicles),
            description = state.visibleActionVehicles.size.let { count ->
                // The app keeps the two forms as separate keys, not a plurals
                // element, so the choice is made here.
                if (count == 1) {
                    str(S.av_vehicle_count_one)
                } else {
                    str(S.av_vehicle_count_other, count)
                }
            },
            actions = {
                if (state.avCapabilities.canRequest) {
                    ZillitButton(
                        text = str(S.av_request_a_vehicle),
                        onClick = { onEvent(HubEvent.NewActionVehicleRequest) },
                        leadingIcon = ZillitIcons.Add,
                    )
                }
            },
        )
        ZillitTabStrip(
            tabs = tabs.map { ZillitTab(it.name, it.label(), counts[it] ?: 0) },
            activeId = state.avTab.name,
            onSelect = { id -> onEvent(HubEvent.AvTabChanged(AvTab.valueOf(id))) },
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            HubSearch(
                value = state.avSearch,
                placeholder = str(S.desktop_hub_av_search_hint),
                onValueChange = { onEvent(HubEvent.AvSearchChanged(it)) },
            )
            val categories = state.avCategoryOptions
            if (categories.isNotEmpty()) {
                ZillitSelect(
                    value = state.avCategory.takeIf { it.isNotBlank() },
                    options = listOf(null) + categories,
                    onSelect = { onEvent(HubEvent.AvCategoryChanged(it.orEmpty())) },
                    label = { it?.categoryLabel() ?: str(S.desktop_ds_all_categories) },
                    showInitials = false,
                )
            }
        }
        AvRows(state, onEvent)
    }
}

@Composable
private fun AvRows(state: HubUiState, onEvent: (HubEvent) -> Unit) {
    val rows = state.visibleActionVehicles
    if (rows.isEmpty()) {
        ZillitEmptyState(
            title = str(S.av_empty),
            message = if (state.avTab == AvTab.Identify) {
                str(S.desktop_hub_nothing_to_source)
            } else {
                null
            },
            icon = ZillitIcons.Transport,
            modifier = Modifier.fillMaxWidth(),
        )
        return
    }
    ZillitLazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        items(rows, key = { it.id }) { vehicle ->
            AvCard(
                vehicle = vehicle,
                busy = state.busyRowId == vehicle.id,
                canSource = state.avCapabilities.canSource,
                onEvent = onEvent,
            )
        }
    }
}

@Composable
private fun AvCard(
    vehicle: ActionVehicle,
    busy: Boolean,
    canSource: Boolean,
    onEvent: (HubEvent) -> Unit,
) {
    ZillitSectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            ) {
                ZillitText(
                    text = vehicle.title,
                    style = ZillitTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                vehicle.categories.take(CATEGORY_PILLS).forEach { category ->
                    ZillitStatusPill(label = category.categoryLabel(), tone = StatusTone.Neutral)
                }
                // A running count while the round is open; once any one person
                // has answered, the count stops meaning anything — the first
                // write finalizes the whole request.
                vehicle.roundChip()?.let { (label, tone) ->
                    ZillitStatusPill(label = label, tone = tone)
                }
                ZillitStatusPill(
                    label = vehicle.status.label(),
                    tone = vehicle.status.tone(),
                    dot = true,
                )
            }
            if (vehicle.vehicleLine.isNotBlank()) {
                ZillitText(
                    text = vehicle.vehicleLine,
                    style = ZillitTheme.typography.bodyMedium,
                    color = ZillitTheme.colors.textSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
                HubFact(
                    label = str(S.desktop_ce_requested_by),
                    value = vehicle.requesterName,
                    modifier = Modifier.weight(1f),
                )
                HubFact(
                    label = str(S.av_scenes),
                    value = vehicle.scenes.joinToString(", "),
                    modifier = Modifier.weight(1f),
                )
                HubFact(
                    label = str(S.av_need_shooting),
                    value = vehicle.needShootingMs.hubDate(),
                    modifier = Modifier.weight(1f),
                )
                HubFact(
                    label = str(S.av_location),
                    value = vehicle.location.display(),
                    modifier = Modifier.weight(1f),
                )
            }
            // Why a request was sent back is the first thing anyone asks of a
            // bounced row, so it is on the card and not behind the detail.
            if (vehicle.rejectionReason.isNotBlank()) {
                HubFact(label = str(S.reason), value = vehicle.rejectionReason)
            }
            AvActions(vehicle, busy, canSource, onEvent)
        }
    }
}

/**
 * The buttons a row offers.
 *
 * Who may do what is the point of this page, and the two rules that matter
 * are: sourcing follows posting access only, and a decision belongs to the
 * people named as approvers on *this* record — which the server answers per
 * row, so the buttons appear for them and nobody else.
 */
@Composable
private fun AvActions(
    vehicle: ActionVehicle,
    busy: Boolean,
    canSource: Boolean,
    onEvent: (HubEvent) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        ZillitButton(
            text = str(S.view),
            onClick = { onEvent(HubEvent.OpenActionVehicle(vehicle.id)) },
            variant = ButtonVariant.Secondary,
            size = ButtonSize.Small,
        )
        if (canSource) AvSourcerButtons(vehicle, busy, onEvent)
        // An approver's own two buttons. Gated on the server's per-record
        // answer, never on a global role: the same person is an approver on
        // one request and a bystander on the next.
        if (vehicle.my.isApprover && vehicle.status == AvStatus.PendingApproval) {
            AvDecisionButtons(vehicle, busy, AvFlow.Approval, onEvent)
        }
        if (vehicle.my.isReturnApprover && vehicle.status == AvStatus.ReturnPending) {
            AvDecisionButtons(vehicle, busy, AvFlow.Return, onEvent)
        }
        // A finished record's thread closes with it, so the button goes too.
        if (!vehicle.status.isTerminal) {
            ZillitButton(
                text = if (vehicle.comments.isEmpty()) {
                    str(S.desktop_hub_discuss)
                } else {
                    str(S.desktop_hub_discuss_count, vehicle.comments.size.toString())
                },
                onClick = { onEvent(HubEvent.OpenAvThread(vehicle.id)) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
            )
        }
        if (canSource && !vehicle.status.isTerminal) {
            ZillitButton(
                text = str(S.edit),
                onClick = { onEvent(HubEvent.EditActionVehicle(vehicle)) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
            )
            ZillitButton(
                text = str(S.delete),
                onClick = { onEvent(HubEvent.DeleteActionVehicle(vehicle)) },
                variant = ButtonVariant.Danger,
                size = ButtonSize.Small,
                enabled = !busy,
            )
        }
    }
}

/** The one act the sourcer owes this record at its current stage. */
@Composable
private fun AvSourcerButtons(
    vehicle: ActionVehicle,
    busy: Boolean,
    onEvent: (HubEvent) -> Unit,
) {
    when (vehicle.status) {
        AvStatus.Requested, AvStatus.Rejected -> ZillitButton(
            text = str(S.av_tab_identify),
            onClick = { onEvent(HubEvent.SourceActionVehicle(vehicle)) },
            size = ButtonSize.Small,
            enabled = !busy,
        )

        AvStatus.Sourced -> ZillitButton(
            text = str(S.av_send_for_approval),
            onClick = { onEvent(HubEvent.PickAvApprovers(vehicle, AvFlow.Approval)) },
            size = ButtonSize.Small,
            enabled = !busy,
        )

        AvStatus.Approved -> ZillitButton(
            text = str(S.av_send_to_set),
            onClick = { onEvent(HubEvent.SetActionVehicleStatus(vehicle.id, AvStatus.OnSet)) },
            size = ButtonSize.Small,
            enabled = !busy,
        )

        AvStatus.OnSet -> ZillitButton(
            text = str(S.av_request_return),
            onClick = { onEvent(HubEvent.PickAvApprovers(vehicle, AvFlow.Return)) },
            variant = ButtonVariant.Secondary,
            size = ButtonSize.Small,
            enabled = !busy,
        )

        // The round's own approvers act on these, not the sourcer.
        AvStatus.PendingApproval, AvStatus.ReturnPending,
        AvStatus.Returned, AvStatus.Cancelled,
        -> Unit
    }
}

@Composable
private fun AvDecisionButtons(
    vehicle: ActionVehicle,
    busy: Boolean,
    flow: AvFlow,
    onEvent: (HubEvent) -> Unit,
) {
    ZillitButton(
        text = str(S.av_approve),
        onClick = { onEvent(HubEvent.DecideActionVehicle(vehicle, flow, isReject = false)) },
        size = ButtonSize.Small,
        enabled = !busy,
    )
    ZillitButton(
        text = str(S.av_reject),
        onClick = { onEvent(HubEvent.DecideActionVehicle(vehicle, flow, isReject = true)) },
        variant = ButtonVariant.Secondary,
        size = ButtonSize.Small,
        enabled = !busy,
    )
}

/** "2 of 3 approved", or who settled it. Nothing when no round is open. */
private fun ActionVehicle.roundChip(): Pair<String, StatusTone>? {
    val round = when (status) {
        AvStatus.PendingApproval -> AvApprovalRound.of(approvers)
        AvStatus.ReturnPending -> AvApprovalRound.of(returnApprovers)
        else -> return null
    }
    if (round.total == 0) return null
    return when {
        round.declined -> str(S.desktop_hub_a_reviewer_declined) to StatusTone.Rejected
        round.approved > 0 -> {
            val by = round.approvedByName
            val label = if (by.isBlank()) {
                str(S.approved)
            } else {
                str(S.ah_tier_approved_by, by)
            }
            label to StatusTone.Done
        }

        // "%1$d/%2$d approved" — the app's own wording for a live round.
        else -> str(S.av_approval_progress, round.approved, round.total) to StatusTone.Pending
    }
}

internal fun AvTab.label(): String = when (this) {
    AvTab.Request -> str(S.av_tab_requested_vehicles)
    AvTab.Identify -> str(S.av_tab_identify)
    AvTab.InProgress -> str(S.av_tab_sent_for_approval)
    AvTab.Pending -> str(S.av_tab_pending_approval)
    AvTab.Finalized -> str(S.av_tab_finalized)
    AvTab.Comments -> str(S.av_comments)
}

internal fun AvStatus.label(): String = when (this) {
    AvStatus.Requested -> str(S.av_chip_requested)
    AvStatus.Sourced -> str(S.av_chip_sourced)
    AvStatus.PendingApproval -> str(S.av_tab_pending_approval)
    AvStatus.Approved -> str(S.approved)
    AvStatus.OnSet -> str(S.desktop_csync_on_set)
    AvStatus.ReturnPending -> str(S.desktop_hub_return_pending)
    AvStatus.Returned -> str(S.desktop_pc_returned)
    AvStatus.Rejected -> str(S.av_chip_rejected)
    AvStatus.Cancelled -> str(S.cancelled)
}

internal fun AvStatus.tone(): StatusTone = when (this) {
    AvStatus.Requested -> StatusTone.Progress
    AvStatus.Sourced -> StatusTone.Ready
    AvStatus.PendingApproval, AvStatus.ReturnPending -> StatusTone.Pending
    AvStatus.Approved -> StatusTone.Done
    AvStatus.OnSet -> StatusTone.InTransit
    AvStatus.Returned -> StatusTone.Neutral
    AvStatus.Rejected -> StatusTone.Rejected
    AvStatus.Cancelled -> StatusTone.Neutral
}

/**
 * A category's own label.
 *
 * A production may add its own, and a custom entry stores its label as the
 * value — so anything not in the built-ins is already the words to show.
 */
internal fun String.categoryLabel(): String =
    avCategories.firstOrNull { it.first == this }?.second ?: this

/** How many category pills a row wears before the rest stay in the detail. */
private const val CATEGORY_PILLS = 2
