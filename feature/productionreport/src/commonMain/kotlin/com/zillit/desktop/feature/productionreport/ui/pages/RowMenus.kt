package com.zillit.desktop.feature.productionreport.ui.pages

import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.productionreport.domain.BadgeKind
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
 * their gates. Comment counts ride the kebab; REPORT counts sit beside the
 * name (`unreadFor` / `reportUnreadFor`).
 */

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
        if (canPost) MenuEntry.Action("edit", str(S.edit), ZillitIcons.Edit) { onEvent(ListEvent.Edit(row)) } else null,
        if (send.readComments) commentAction(row, unread, readOnly = false, onEvent) else null,
        if (canPost && send.sendForSignature) {
            MenuEntry.Action("signature", str(S.cs_send_for_signature), ZillitIcons.Send, MenuTone.Primary) {
                onEvent(WorkflowEvent.SendForSignature(row))
            }
        } else {
            null
        },
        if (canPost && send.sendForComments) {
            MenuEntry.Action(
                "comments",
                str(S.pr_send_for_comments),
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
        // Not gated on posting rights: anyone who can see the row may share its PDF.
        sendForChatAction(row, onEvent),
        if (canPost) deleteAction(row, onEvent) else null,
    )
}

internal fun sentMenu(state: ReportUiState, row: ReportSummary, onEvent: (ReportEvent) -> Unit): List<MenuEntry> {
    val unread = unreadFor(state, row)
    return listOfNotNull(
        viewAction(row, onEvent),
        historyAction(state, row, str(S.desktop_approval_history), onEvent),
        if (row.status !in NO_COMMENT || unread > 0) commentAction(row, unread, readOnly = false, onEvent) else null,
        sendForChatAction(row, onEvent),
        MenuEntry.Divider,
        if (!row.status.locked) MenuEntry.Action(
            "edit",
            str(S.edit),
            ZillitIcons.Edit,
        ) { onEvent(ListEvent.Edit(row)) } else null,
        if (row.status == ReportStatus.PendingApproval) {
            MenuEntry.Action(
                "remind",
                str(S.pr_send_reminder),
                ZillitIcons.Bell,
                MenuTone.Primary,
            ) { onEvent(WorkflowEvent.OpenReminder(row)) }
        } else {
            null
        },
        // Files under the MAIN tool root — a document out for signature had no way into the library otherwise.
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
                str(S.desktop_view_reminder),
                ZillitIcons.Bell,
                MenuTone.Primary,
            ) { onEvent(WorkflowEvent.ViewReminders(row)) }
        } else {
            null
        },
        viewAction(row, onEvent),
        historyAction(state, row, str(S.desktop_approval_history), onEvent),
        if (row.status !in NO_COMMENT || unread > 0) commentAction(row, unread, readOnly = false, onEvent) else null,
        sendForChatAction(row, onEvent),
        if (actionable) MenuEntry.Divider else null,
        if (actionable) MenuEntry.Action(
            "approve",
            str(S.approve),
            ZillitIcons.Check,
            MenuTone.Approve,
        ) { onEvent(WorkflowEvent.OpenApprove(row)) } else null,
        if (actionable) MenuEntry.Action(
            "reject",
            str(S.reject),
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
        historyAction(state, row, str(S.history), onEvent),
        commentAction(row, unread, readOnly = true, onEvent),
        if (publishable) MenuEntry.Divider else null,
        if (publishable) {
            MenuEntry.Action(
                "publish",
                str(S.publish),
                ReportIcons.FileUpload,
                MenuTone.Primary,
            ) { onEvent(WorkflowEvent.OpenPublish(row)) }
        } else {
            null
        },
    )
}

private fun viewAction(row: ReportSummary, onEvent: (ReportEvent) -> Unit) =
    MenuEntry.Action("view", str(S.view), ZillitIcons.Eye, MenuTone.Primary) { onEvent(ListEvent.View(row)) }

private fun historyAction(
    state: ReportUiState,
    row: ReportSummary,
    title: String,
    onEvent: (ReportEvent) -> Unit,
): MenuEntry.Action {
    val loading = state.historyLoadingId == row.id
    return MenuEntry.Action(
        key = "history",
        label = if (loading) str(S.pr_loading) else str(S.cs_action_view_history),
        icon = ReportIcons.History,
        tone = MenuTone.Info,
        enabled = state.historyLoadingId == null,
    ) { onEvent(ListEvent.OpenHistory(row, title)) }
}

private fun commentAction(row: ReportSummary, unread: Int, readOnly: Boolean, onEvent: (ReportEvent) -> Unit) =
    MenuEntry.Action("comment", str(S.cs_action_comment), ReportIcons.Comment, badge = unread) {
        onEvent(ListEvent.OpenComments(row, readOnly = readOnly || row.status == ReportStatus.ApprovedForPublish))
    }

/** ZL-21415: the PDF into a 1:1 chat — never once final-approved or published. */
private fun sendForChatAction(row: ReportSummary, onEvent: (ReportEvent) -> Unit): MenuEntry.Action? =
    if (row.status.locked) {
        null
    } else {
        MenuEntry.Action("sendChat", str(S.cs_action_send_for_chat), ZillitIcons.Chat, MenuTone.Info) {
            onEvent(ListEvent.SendForChat(row))
        }
    }

private fun docDistAction(row: ReportSummary, fromDraft: Boolean, onEvent: (ReportEvent) -> Unit) =
    MenuEntry.Action("docdist", str(S.cs_action_send_to_dd), ZillitIcons.Upload) {
        onEvent(WorkflowEvent.SendToDocDist(row, fromDraft))
    }

private fun deleteAction(row: ReportSummary, onEvent: (ReportEvent) -> Unit) =
    MenuEntry.Action("delete", str(S.delete), ZillitIcons.Trash, MenuTone.Danger) { onEvent(ListEvent.Delete(row)) }

/** A card's text links: every action that is not one of its pills. */
internal fun cardLinks(entries: List<MenuEntry>, primaryKeys: Set<String>): List<CardLink> =
    entries.filterIsInstance<MenuEntry.Action>()
        .filter { it.key !in primaryKeys }
        .map { action ->
            CardLink(
                label = when (action.key) {
                    "history" -> if (action.label == str(S.pr_loading)) action.label else str(S.history)
                    // The card's link row has a sixth of the width; the menu keeps the full wording.
                    "docdist" -> str(S.dd_distribute_short)
                    "sendChat" -> str(S.chat)
                    else -> action.label
                },
                icon = if (action.key == "sendChat") ZillitIcons.Chat else null,
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
                "signature" -> Triple(str(S.cs_send_for_signature), PillKind.Navy, str(S.cs_send_for_signature))
                "delete" -> Triple(str(S.delete), PillKind.Danger, str(S.desktop_delete_report))
                "remind" -> Triple(str(S.docusign_action_remind), PillKind.Accent, str(S.pr_send_reminder))
                "reminder" -> Triple(str(S.reminder), PillKind.Accent, str(S.desktop_view_reminder))
                "approve" -> Triple(str(S.approve), PillKind.Approve, str(S.desktop_approve_this_report))
                "reject" -> Triple(str(S.reject), PillKind.Danger, str(S.desktop_reject_this_report))
                "publish" -> Triple(str(S.publish), PillKind.Navy, str(S.desktop_publish_this_report))
                else -> Triple(action.label, PillKind.Navy, action.label)
            }
            CardPill(label, action.icon, kind, tooltip, wide = action.key != "reminder", onClick = action.onClick)
        }

/** A row's unread COMMENT count on the open list — the kebab's badge. */
internal fun unreadFor(state: ReportUiState, row: ReportSummary): Int = state.rowBadge(row, BadgeKind.Comment)

/** A row's unread REPORT count on the open list — the number beside its name. */
internal fun reportUnreadFor(state: ReportUiState, row: ReportSummary): Int = state.rowBadge(row, BadgeKind.Report)
