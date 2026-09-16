package com.zillit.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.common.ZillitLog
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitAvatar
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitLazyColumn
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitToast
import com.zillit.desktop.core.designsystem.component.ZillitToastTone
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.permissions.RightsApprover
import com.zillit.desktop.core.permissions.RightsRequest
import com.zillit.desktop.core.permissions.RightsRequestBus
import com.zillit.desktop.core.permissions.rightsRequestMessage
import kotlinx.coroutines.launch

/**
 * Asking an admin for posting or download rights.
 *
 * ## Why the frame owns this and not the modules
 *
 * There is no permission-request endpoint on any client — Android, iOS and the
 * web all send the admin a **chat message** and let them work the rights grid
 * by hand. Doing that needs the production's admin list, the chat socket and
 * somewhere to float a dialog over a tool window, none of which a feature
 * module has. So a module raises a [RightsRequest] in its own words on
 * [RightsRequestBus] and this answers it, the same way the call overlay and
 * the pending-changes dialog are hosted here.
 *
 * ## The flow, as the phones run it
 *
 * Confirm ("you don't have this right — ask an admin?"), then pick which
 * admin, then the message goes to that admin's direct thread. The dialog is
 * two steps rather than one because a production runs several admins and
 * sending to the wrong one is a request that quietly never gets actioned.
 */
@Composable
internal fun RightsRequestSurface(ready: AppGraph.Ready, bus: RightsRequestBus) {
    var asking by remember { mutableStateOf<RightsRequest?>(null) }
    var choosing by remember { mutableStateOf<RightsRequest?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(bus) { bus.requests.collect { asking = it } }

    // Tools hosting a heavyweight browser view step aside while any of this is
    // on screen; otherwise the view paints straight over it.
    val onScreen = asking != null || choosing != null || notice != null
    LaunchedEffect(bus, onScreen) { bus.setShowing(onScreen) }

    val approvers = remember(asking, choosing) { ready.rightsApprovers() }

    asking?.let { request ->
        ConfirmDialog(
            request = request,
            onDismiss = { asking = null },
            onProceed = {
                asking = null
                // Nobody to ask is its own answer: a picker with an empty list
                // reads as a failure to load, and the reader is left unsure
                // whether the request went anywhere.
                if (approvers.isEmpty()) {
                    notice = "This project has no other administrator to ask."
                } else {
                    choosing = request
                }
            },
        )
    }

    choosing?.let { request ->
        ApproverDialog(
            request = request,
            approvers = approvers,
            onDismiss = { choosing = null },
            onPick = { approver ->
                choosing = null
                scope.launch {
                    notice = ready.sendRightsRequest(request, approver)
                }
            },
        )
    }

    ZillitToast(
        message = notice,
        onDismiss = { notice = null },
        tone = ZillitToastTone.Success,
    )
}

/**
 * The production's admins, minus the person asking.
 *
 * Reading the crew list the session already holds rather than asking the
 * server: the same list the chat directory shows, and an admin who is not on
 * it is one this client could not message anyway.
 */
private fun AppGraph.Ready.rightsApprovers(): List<RightsApprover> {
    val context = projectContext?.context?.value ?: return emptyList()
    val me = context.profile?.userId
    return context.users
        .filter { it.isAdmin && it.userId != me && it.hasJoined() }
        .map { RightsApprover(it.userId, it.fullName, it.designationText()) }
        .sortedBy { it.name.lowercase() }
}

/**
 * Sends the request, and says what happened in words the reader can act on.
 *
 * The message rides the ordinary chat send — it *is* an ordinary message, and
 * appears in the admin's thread like any other. Failure is reported rather
 * than swallowed: someone who thinks they have asked will wait instead of
 * asking again.
 */
private suspend fun AppGraph.Ready.sendRightsRequest(
    request: RightsRequest,
    approver: RightsApprover,
): String {
    val sent = chatRepository.send(
        receiverId = approver.userId,
        body = rightsRequestMessage(request),
        uniqueId = randomUniqueId(),
        nowMillis = System.currentTimeMillis(),
    )
    return when (sent) {
        is ZillitResult.Success ->
            "Asked ${approver.name} for ${request.kind.verb} rights on ${request.moduleLabel}."

        is ZillitResult.Failure -> {
            ZillitLog.w(TAG) { "rights request not sent: ${sent.error.technical ?: sent.error.userMessage}" }
            "Could not send that request. ${sent.error.userMessage}"
        }
    }
}

/** Android's `dd_no_posting_rights_on_module`, in this app's voice. */
@Composable
private fun ConfirmDialog(request: RightsRequest, onDismiss: () -> Unit, onProceed: () -> Unit) {
    ZillitDialogShell(
        title = "Ask for ${request.kind.verb} rights?",
        subtitle = request.moduleLabel,
        icon = ZillitIcons.Shield,
        visible = true,
        onDismiss = onDismiss,
        width = DIALOG_WIDTH,
        actions = {
            ZillitButton(text = "No", onClick = onDismiss, variant = ButtonVariant.Tertiary)
            ZillitButton(text = "Proceed", onClick = onProceed)
        },
    ) {
        ZillitText(
            text = "You do not have ${request.kind.verb} rights on ${request.moduleLabel}. " +
                "Would you like to ask one of this project's administrators for them?",
            style = ZillitTheme.typography.bodyMedium,
            color = ZillitTheme.colors.textSecondary,
        )
    }
}

/** Android's `select_admin_from_below`: one admin, then the message goes. */
@Composable
private fun ApproverDialog(
    request: RightsRequest,
    approvers: List<RightsApprover>,
    onDismiss: () -> Unit,
    onPick: (RightsApprover) -> Unit,
) {
    ZillitDialogShell(
        title = "Which administrator?",
        subtitle = "They receive this as a message from you",
        icon = ZillitIcons.Shield,
        visible = true,
        onDismiss = onDismiss,
        width = DIALOG_WIDTH,
        actions = {
            ZillitButton(text = "Cancel", onClick = onDismiss, variant = ButtonVariant.Tertiary)
        },
    ) {
        ZillitLazyColumn(
            modifier = Modifier.heightIn(max = LIST_MAX_HEIGHT),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
        ) {
            items(approvers, key = RightsApprover::userId) { approver ->
                ApproverRow(approver) { onPick(approver) }
            }
        }
        // Shown before it is sent, because it is sent verbatim and carries the
        // reader's name — nobody should discover what they said afterwards.
        ZillitText(
            text = rightsRequestMessage(request),
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textMuted,
        )
    }
}

@Composable
private fun ApproverRow(approver: RightsApprover, onPick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.medium)
            .clickable(onClick = onPick)
            .padding(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitAvatar(name = approver.name, userId = approver.userId, size = ROW_AVATAR)
        Column(Modifier.weight(1f)) {
            ZillitText(text = approver.name, style = ZillitTheme.typography.bodyMedium, maxLines = 1)
            approver.designation?.takeIf { it.isNotBlank() }?.let {
                ZillitText(
                    text = it,
                    style = ZillitTheme.typography.labelSmall,
                    color = ZillitTheme.colors.textMuted,
                    maxLines = 1,
                )
            }
        }
    }
}

private fun randomUniqueId(): String = java.util.UUID.randomUUID().toString()

private const val TAG = "Rights"
private val DIALOG_WIDTH = 440.dp
private val LIST_MAX_HEIGHT = 260.dp
private val ROW_AVATAR = 32.dp
