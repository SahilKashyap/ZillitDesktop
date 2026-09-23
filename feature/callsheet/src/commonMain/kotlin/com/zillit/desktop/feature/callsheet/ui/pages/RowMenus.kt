package com.zillit.desktop.feature.callsheet.ui.pages

import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.callsheet.domain.CallSheetStatus
import com.zillit.desktop.feature.callsheet.domain.CallSheetSummary
import com.zillit.desktop.feature.callsheet.domain.canApproveReject
import com.zillit.desktop.feature.callsheet.domain.canPublish
import com.zillit.desktop.feature.callsheet.domain.commentAllowed
import com.zillit.desktop.feature.callsheet.domain.pendingFinalRequest
import com.zillit.desktop.feature.callsheet.domain.sendActions
import com.zillit.desktop.feature.callsheet.domain.sendForChatAllowed
import com.zillit.desktop.feature.callsheet.domain.shouldShowReminderBell
import com.zillit.desktop.feature.callsheet.ui.ListEvent
import com.zillit.desktop.feature.callsheet.ui.SheetEvent
import com.zillit.desktop.feature.callsheet.ui.SheetUiState
import com.zillit.desktop.feature.callsheet.ui.WorkflowEvent
import com.zillit.desktop.feature.callsheet.ui.components.CardLink
import com.zillit.desktop.feature.callsheet.ui.components.CardPill
import com.zillit.desktop.feature.callsheet.ui.components.MenuEntry
import com.zillit.desktop.feature.callsheet.ui.components.MenuTone
import com.zillit.desktop.feature.callsheet.ui.components.PillKind
import com.zillit.desktop.feature.callsheet.ui.theme.SheetIcons

/**
 * Each list's row actions, one definition for the kebab and the card — the
 * web's `rowActions` (`DraftTab.jsx`) and the three Approvals sub-tabs' menus
 * (`ApprovalsTab.jsx`), with their gates and the `TONE_BY_KEY` tiles.
 *
 * Send for Chat (ZL-21415) sits on Drafts, Sent and Received rows for anyone
 * who can see them, until the sheet locks; the approver "Chat" actions are
 * gone. Comment is offered to everyone — whether they may WRITE in the
 * thread is decided when it opens (`canPostComments`).
 */

internal fun draftMenu(
    state: SheetUiState,
    row: CallSheetSummary,
    onEvent: (SheetEvent) -> Unit,
    includeView: Boolean,
): List<MenuEntry> {
    val unread = state.unreadComments(row.id)
    val send = sendActions(row.status, unread)
    val canPost = state.isPoster
    return listOfNotNull(
        viewAction(row, onEvent).takeIf { includeView },
        editAction(row, onEvent).takeIf { canPost },
        commentAction(row, unread, readOnly = false, onEvent).takeIf { send.readComments },
        MenuEntry.Action("signature", "Send for Signature", ZillitIcons.Send) {
            onEvent(WorkflowEvent.SendForSignature(row))
        }.takeIf { canPost && send.sendForSignature },
        MenuEntry.Action("comments", "Send for Comments", SheetIcons.UsersAdd) {
            onEvent(WorkflowEvent.SendForComments(row))
        }.takeIf { canPost && send.sendForComments },
        docDistAction(row, fromDraft = true, onEvent).takeIf { canPost && state.canDistribute && !row.status.locked },
        sendForChatAction(row, onEvent).takeIf { sendForChatAllowed(row.status) },
        deleteAction(row, onEvent).takeIf { canPost },
    )
}

internal fun sentMenu(state: SheetUiState, row: CallSheetSummary, onEvent: (SheetEvent) -> Unit): List<MenuEntry> {
    val unread = state.unreadComments(row.id)
    return listOfNotNull(
        viewAction(row, onEvent),
        historyAction(state, row, "Approval History", onEvent),
        sendForChatAction(row, onEvent).takeIf { sendForChatAllowed(row.status) },
        commentAction(row, unread, readOnly = false, onEvent).takeIf { commentAllowed(row.status, unread) },
        MenuEntry.Divider,
        approveAction(row, onEvent).takeIf { pendingFinalRequest(row, state.me) != null },
        editAction(row, onEvent).takeIf { !row.status.locked },
        MenuEntry.Action("remind", "Send Reminder", ZillitIcons.Bell, MenuTone.Primary) {
            onEvent(WorkflowEvent.OpenReminder(row))
        }.takeIf { row.status == CallSheetStatus.PendingApproval },
        MenuEntry.Action("signature", "Send for Signature", ZillitIcons.Send) {
            onEvent(WorkflowEvent.SendForSignature(row))
        }.takeIf { row.status == CallSheetStatus.ApprovalRejected },
        docDistAction(row, fromDraft = false, onEvent).takeIf { state.canDistribute && !row.status.locked },
        deleteAction(row, onEvent).takeIf { !row.status.locked },
    )
}

internal fun receivedMenu(state: SheetUiState, row: CallSheetSummary, onEvent: (SheetEvent) -> Unit): List<MenuEntry> {
    val unread = state.unreadComments(row.id)
    val actionable = canApproveReject(row, state.me)
    return listOfNotNull(
        MenuEntry.Action("reminder", "View Reminder", ZillitIcons.Bell, MenuTone.Primary) {
            onEvent(ListEvent.ViewReminder(row))
        }.takeIf { shouldShowReminderBell(row, state.me) },
        viewAction(row, onEvent),
        historyAction(state, row, "Approval History", onEvent),
        sendForChatAction(row, onEvent).takeIf { sendForChatAllowed(row.status) },
        commentAction(row, unread, readOnly = false, onEvent).takeIf { commentAllowed(row.status, unread) },
        MenuEntry.Divider.takeIf { actionable },
        approveAction(row, onEvent).takeIf { actionable },
        MenuEntry.Action("reject", "Reject", ZillitIcons.Close, MenuTone.Danger) {
            onEvent(WorkflowEvent.OpenReject(row))
        }.takeIf { actionable },
    )
}

internal fun finalizedMenu(state: SheetUiState, row: CallSheetSummary, onEvent: (SheetEvent) -> Unit): List<MenuEntry> {
    val unread = state.unreadComments(row.id)
    val publishable = canPublish(row, state.me)
    return listOfNotNull(
        viewAction(row, onEvent),
        historyAction(state, row, "History", onEvent),
        commentAction(row, unread, readOnly = true, onEvent),
        MenuEntry.Divider.takeIf { publishable },
        publishAction(row, onEvent).takeIf { publishable },
    )
}

private fun viewAction(row: CallSheetSummary, onEvent: (SheetEvent) -> Unit) =
    MenuEntry.Action("view", "View", ZillitIcons.Eye, MenuTone.Primary) { onEvent(ListEvent.View(row)) }

private fun editAction(row: CallSheetSummary, onEvent: (SheetEvent) -> Unit) =
    MenuEntry.Action("edit", "Edit", ZillitIcons.Edit) { onEvent(ListEvent.Edit(row)) }

private fun approveAction(row: CallSheetSummary, onEvent: (SheetEvent) -> Unit) =
    MenuEntry.Action("approve", "Approve", ZillitIcons.Check, MenuTone.Approve) {
        onEvent(WorkflowEvent.OpenApprove(row))
    }

private fun publishAction(row: CallSheetSummary, onEvent: (SheetEvent) -> Unit) =
    MenuEntry.Action("publish", "Publish", SheetIcons.CloudUpload, MenuTone.Primary) {
        onEvent(WorkflowEvent.OpenPublish(row))
    }

private fun sendForChatAction(row: CallSheetSummary, onEvent: (SheetEvent) -> Unit) =
    MenuEntry.Action("sendChat", "Send for Chat", ZillitIcons.Chat, MenuTone.Info) {
        onEvent(WorkflowEvent.OpenSendForChat(row))
    }

private fun historyAction(
    state: SheetUiState,
    row: CallSheetSummary,
    title: String,
    onEvent: (SheetEvent) -> Unit,
): MenuEntry.Action {
    val loading = state.historyLoadingId == row.id
    return MenuEntry.Action(
        key = "history",
        label = if (loading) "Loading…" else "View History",
        icon = SheetIcons.History,
        tone = MenuTone.Info,
        enabled = state.historyLoadingId == null,
    ) { onEvent(ListEvent.OpenHistory(row, title)) }
}

private fun commentAction(row: CallSheetSummary, unread: Int, readOnly: Boolean, onEvent: (SheetEvent) -> Unit) =
    MenuEntry.Action("comment", "Comment", SheetIcons.Comment, badge = unread) {
        onEvent(ListEvent.OpenComments(row, readOnly = readOnly || row.status == CallSheetStatus.ApprovedForPublish))
    }

private fun docDistAction(row: CallSheetSummary, fromDraft: Boolean, onEvent: (SheetEvent) -> Unit) =
    MenuEntry.Action("docdist", "Send to Document Distribution", SheetIcons.CloudUpload) {
        onEvent(WorkflowEvent.SendToDocDist(row, fromDraft))
    }

private fun deleteAction(row: CallSheetSummary, onEvent: (SheetEvent) -> Unit) =
    MenuEntry.Action("delete", "Delete", ZillitIcons.Trash, MenuTone.Danger) { onEvent(ListEvent.Delete(row)) }

// Cards --------------------------------------------------------------------------------------------------

/** A card's text links: the listed keys, in that order, from the row's own action set. */
internal fun cardLinks(entries: List<MenuEntry>, keys: List<String>): List<CardLink> {
    val actions = entries.filterIsInstance<MenuEntry.Action>().associateBy { it.key }
    return keys.mapNotNull { key ->
        val action = actions[key] ?: return@mapNotNull null
        CardLink(
            label = when (key) {
                "history" -> if (action.label == "Loading…") action.label else "History"
                "reminder" -> "Reminder"
                "sendChat" -> "Chat"
                else -> action.label
            },
            icon = action.icon,
            badge = action.badge,
            enabled = action.enabled,
            onClick = action.onClick,
        )
    }
}

/** A card's pills, in the web's `PILL_CLASSES` per action. */
internal fun cardPills(entries: List<MenuEntry>, keys: List<String>): List<CardPill> {
    val actions = entries.filterIsInstance<MenuEntry.Action>().associateBy { it.key }
    return keys.mapNotNull { key ->
        val action = actions[key] ?: return@mapNotNull null
        val (label, kind) = when (key) {
            "signature" -> action.label to PillKind.Navy
            "delete" -> "Delete" to PillKind.Danger
            "edit" -> "Edit" to PillKind.Outline
            "remind" -> "Remind" to PillKind.Accent
            "approve" -> "Approve" to PillKind.Approve
            "reject" -> "Reject" to PillKind.Danger
            "publish" -> "Publish" to PillKind.Accent
            else -> action.label to PillKind.Navy
        }
        CardPill(label, action.icon, kind, tooltip = action.label, onClick = action.onClick)
    }
}
