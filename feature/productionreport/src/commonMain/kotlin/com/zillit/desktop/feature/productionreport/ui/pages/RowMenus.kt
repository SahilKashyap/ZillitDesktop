package com.zillit.desktop.feature.productionreport.ui.pages

import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.productionreport.domain.ReportStatus
import com.zillit.desktop.feature.productionreport.domain.ReportSummary
import com.zillit.desktop.feature.productionreport.domain.canApproveReject
import com.zillit.desktop.feature.productionreport.domain.canPublish
import com.zillit.desktop.feature.productionreport.domain.sendActions
import com.zillit.desktop.feature.productionreport.domain.shouldShowReminderBell
import com.zillit.desktop.feature.productionreport.ui.ListEvent
import com.zillit.desktop.feature.productionreport.ui.ReportEvent
import com.zillit.desktop.feature.productionreport.ui.ReportUiState
import com.zillit.desktop.feature.productionreport.ui.WorkflowEvent
import com.zillit.desktop.feature.productionreport.ui.components.CardLink
import com.zillit.desktop.feature.productionreport.ui.components.CardPill
import com.zillit.desktop.feature.productionreport.ui.components.MenuEntry
import com.zillit.desktop.feature.productionreport.ui.components.MenuTone
import com.zillit.desktop.feature.productionreport.ui.components.PillKind
import com.zillit.desktop.feature.productionreport.ui.theme.ReportIcons

/**
 * Each list's row actions, one definition for the kebab and the card — the
 * web's `rowActions` (Drafts) and the three Approvals sub-tabs' menus, with
 * their gates (`DraftTab.jsx:158-210`, `ApprovalsTab.jsx:424-498, 1051-1105, 1552-1576`).
 */

/** Chat is not offered while a report is a draft, final-approved or published. */
private val NO_CHAT = setOf(ReportStatus.Draft, ReportStatus.Published, ReportStatus.ApprovedForPublish)

/** Comment needs a thread to exist, unless unread comments are waiting. */
private val NO_COMMENT = setOf(ReportStatus.Draft, ReportStatus.Published)

internal fun draftMenu(
    state: ReportUiState,
    row: ReportSummary,
    onEvent: (ReportEvent) -> Unit,
    includeView: Boolean,
): List<MenuEntry> {
    val unread = unreadFor(state, row)
    val send = sendActions(row.status, unread)
    val canPost = state.isPoster
    return listOfNotNull(
        if (includeView) viewAction(row, onEvent) else null,
        if (canPost) MenuEntry.Action("edit", "Edit", ZillitIcons.Edit) { onEvent(ListEvent.Edit(row)) } else null,
        if (send.readComments) commentAction(row, unread, readOnly = false, onEvent) else null,
        if (canPost && send.sendForSignature) {
            MenuEntry.Action("signature", "Send for Signature", ZillitIcons.Send, MenuTone.Primary) {
                onEvent(WorkflowEvent.SendForSignature(row))
            }
        } else {
            null
        },
        if (canPost && send.sendForComments) {
            MenuEntry.Action(
                "comments",
                "Send for Comments",
                ZillitIcons.UserPlus,
            ) { onEvent(WorkflowEvent.SendForComments(row)) }
        } else {
            null
        },
        if (canPost && state.canDistribute && !row.status.locked) docDistAction(
            row,
            fromDraft = true,
            onEvent,
        ) else null,
        if (canPost) deleteAction(row, onEvent) else null,
    )
}

internal fun sentMenu(state: ReportUiState, row: ReportSummary, onEvent: (ReportEvent) -> Unit): List<MenuEntry> {
    val unread = unreadFor(state, row)
    return listOfNotNull(
        viewAction(row, onEvent),
        historyAction(state, row, "Approval History", onEvent),
        if (row.status !in NO_CHAT) {
            MenuEntry.Action(
                "chat",
                "Chat",
                ZillitIcons.Chat,
                MenuTone.Info,
            ) { onEvent(ListEvent.ChatWithApprovers(row)) }
        } else {
            null
        },
        if (row.status !in NO_COMMENT || unread > 0) commentAction(row, unread, readOnly = false, onEvent) else null,
        MenuEntry.Divider,
        if (!row.status.locked) MenuEntry.Action(
            "edit",
            "Edit",
            ZillitIcons.Edit,
        ) { onEvent(ListEvent.Edit(row)) } else null,
        if (row.status == ReportStatus.PendingApproval) {
            MenuEntry.Action(
                "remind",
                "Send Reminder",
                ZillitIcons.Bell,
                MenuTone.Primary,
            ) { onEvent(WorkflowEvent.OpenReminder(row)) }
        } else {
            null
        },
        if (state.canDistribute && !row.status.locked) docDistAction(row, fromDraft = false, onEvent) else null,
        if (!row.status.locked) deleteAction(row, onEvent) else null,
    )
}

internal fun receivedMenu(state: ReportUiState, row: ReportSummary, onEvent: (ReportEvent) -> Unit): List<MenuEntry> {
    val unread = unreadFor(state, row)
    val actionable = canApproveReject(row, state.me)
    return listOfNotNull(
        if (shouldShowReminderBell(row, state.me)) {
            MenuEntry.Action(
                "reminder",
                "View Reminder",
                ZillitIcons.Bell,
                MenuTone.Primary,
            ) { onEvent(WorkflowEvent.ViewReminders(row)) }
        } else {
            null
        },
        viewAction(row, onEvent),
        historyAction(state, row, "Approval History", onEvent),
        if (row.status !in NO_CHAT) {
            MenuEntry.Action(
                "chat",
                "Chat",
                ZillitIcons.Chat,
                MenuTone.Info,
            ) { onEvent(ListEvent.ChatWithCreator(row)) }
        } else {
            null
        },
        if (row.status !in NO_COMMENT || unread > 0) commentAction(row, unread, readOnly = false, onEvent) else null,
        if (actionable) MenuEntry.Divider else null,
        if (actionable) MenuEntry.Action(
            "approve",
            "Approve",
            ZillitIcons.Check,
            MenuTone.Approve,
        ) { onEvent(WorkflowEvent.OpenApprove(row)) } else null,
        if (actionable) MenuEntry.Action(
            "reject",
            "Reject",
            ZillitIcons.Close,
            MenuTone.Danger,
        ) { onEvent(WorkflowEvent.OpenReject(row)) } else null,
    )
}

internal fun finalizedMenu(state: ReportUiState, row: ReportSummary, onEvent: (ReportEvent) -> Unit): List<MenuEntry> {
    val unread = unreadFor(state, row)
    val publishable = canPublish(row, state.isPoster, state.me)
    return listOfNotNull(
        viewAction(row, onEvent),
        historyAction(state, row, "History", onEvent),
        commentAction(row, unread, readOnly = true, onEvent),
        if (publishable) MenuEntry.Divider else null,
        if (publishable) {
            MenuEntry.Action(
                "publish",
                "Publish",
                ReportIcons.FileUpload,
                MenuTone.Primary,
            ) { onEvent(WorkflowEvent.OpenPublish(row)) }
        } else {
            null
        },
    )
}

private fun viewAction(row: ReportSummary, onEvent: (ReportEvent) -> Unit) =
    MenuEntry.Action("view", "View", ZillitIcons.Eye, MenuTone.Primary) { onEvent(ListEvent.View(row)) }

private fun historyAction(
    state: ReportUiState,
    row: ReportSummary,
    title: String,
    onEvent: (ReportEvent) -> Unit,
): MenuEntry.Action {
    val loading = state.historyLoadingId == row.id
    return MenuEntry.Action(
        key = "history",
        label = if (loading) "Loading…" else "View History",
        icon = ReportIcons.History,
        tone = MenuTone.Info,
        enabled = state.historyLoadingId == null,
    ) { onEvent(ListEvent.OpenHistory(row, title)) }
}

private fun commentAction(row: ReportSummary, unread: Int, readOnly: Boolean, onEvent: (ReportEvent) -> Unit) =
    MenuEntry.Action("comment", "Comment", ReportIcons.Comment, badge = unread) {
        onEvent(ListEvent.OpenComments(row, readOnly = readOnly || row.status == ReportStatus.ApprovedForPublish))
    }

private fun docDistAction(row: ReportSummary, fromDraft: Boolean, onEvent: (ReportEvent) -> Unit) =
    MenuEntry.Action("docdist", "Send to Document Distribution", ZillitIcons.Upload) {
        onEvent(WorkflowEvent.SendToDocDist(row, fromDraft))
    }

private fun deleteAction(row: ReportSummary, onEvent: (ReportEvent) -> Unit) =
    MenuEntry.Action("delete", "Delete", ZillitIcons.Trash, MenuTone.Danger) { onEvent(ListEvent.Delete(row)) }

/** A card's text links: every action that is not one of its pills. */
internal fun cardLinks(entries: List<MenuEntry>, primaryKeys: Set<String>): List<CardLink> =
    entries.filterIsInstance<MenuEntry.Action>()
        .filter { it.key !in primaryKeys }
        .map { action ->
            CardLink(
                label = when (action.key) {
                    "history" -> if (action.label == "Loading…") action.label else "History"
                    // The card's link row has a sixth of the width; the menu keeps the full wording.
                    "docdist" -> "Doc Distribution"
                    else -> action.label
                },
                icon = if (action.key == "chat") ZillitIcons.Chat else null,
                badge = action.badge,
                enabled = action.enabled,
                onClick = action.onClick,
            )
        }

/** A card's pills, in the web's colours per action. */
internal fun cardPills(entries: List<MenuEntry>, primaryKeys: Set<String>): List<CardPill> =
    entries.filterIsInstance<MenuEntry.Action>()
        .filter { it.key in primaryKeys }
        .map { action ->
            val (label, kind, tooltip) = when (action.key) {
                "signature" -> Triple("Send for Signature", PillKind.Navy, "Send for Signature")
                "delete" -> Triple("Delete", PillKind.Danger, "Delete report")
                "remind" -> Triple("Remind", PillKind.Accent, "Send reminder")
                "reminder" -> Triple("Reminder", PillKind.Accent, "View reminder")
                "approve" -> Triple("Approve", PillKind.Approve, "Approve this report")
                "reject" -> Triple("Reject", PillKind.Danger, "Reject this report")
                "publish" -> Triple("Publish", PillKind.Navy, "Publish this report")
                else -> Triple(action.label, PillKind.Navy, action.label)
            }
            CardPill(label, action.icon, kind, tooltip, wide = action.key != "reminder", onClick = action.onClick)
        }
