package com.zillit.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.core.socket.SocketConnectionState
import com.zillit.desktop.core.sync.ConnectivityMonitor
import com.zillit.desktop.core.sync.SyncEngine
import com.zillit.desktop.core.sync.SyncOperation
import com.zillit.desktop.core.sync.SyncState
import com.zillit.desktop.core.sync.SyncStatus
import com.zillit.desktop.feature.shell.StatusAction
import kotlinx.coroutines.launch

/**
 * The status bar's left-hand words: the network first, then the socket.
 *
 * "Offline" here means the API could not be reached, which is the fact the
 * user acts on; the socket's own state only matters once the network is there.
 */
fun statusText(socket: SocketConnectionState, sync: SyncStatus): String = when {
    !sync.online && sync.pending > 0 ->
        str(S.desktop_offline_pending, sync.pending.changes())
    !sync.online -> str(S.desktop_offline)
    else -> socket.statusLabel()
}

/** The status bar's clickable summary of the queue, or null when it is empty. */
fun syncStatusAction(sync: SyncStatus, onClick: () -> Unit): StatusAction? {
    val text = when {
        sync.syncing -> str(S.desktop_sync_sending, sync.pending.changes())
        sync.failed > 0 -> str(S.desktop_sync_attention, sync.failed.changes())
        sync.pending > 0 -> str(S.desktop_sync_waiting, sync.pending.changes())
        sync.elsewhere > 0 -> str(S.desktop_sync_elsewhere, sync.elsewhere.changes())
        else -> return null
    }
    return StatusAction(text = text, attention = sync.failed > 0, onClick = onClick)
}

private fun Int.changes(): String = if (this == 1) str(S.desktop_one_change) else str(S.desktop_n_changes, this)

private fun SocketConnectionState.statusLabel(): String = when (this) {
    is SocketConnectionState.Connected -> str(S.desktop_status_live)
    SocketConnectionState.Connecting -> str(S.txt_connecting)
    is SocketConnectionState.Reconnecting -> str(S.txt_reconnecting)
    is SocketConnectionState.Failed -> str(S.desktop_status_paused)
    SocketConnectionState.Disconnected -> str(S.desktop_status_off)
}

/**
 * The open production's queue: what is waiting, what went wrong, and the two
 * things a person can do about a parked change — try again, or let it go.
 */
@Composable
fun PendingChangesDialog(
    engine: SyncEngine,
    connectivity: ConnectivityMonitor,
    visible: Boolean,
    onDismiss: () -> Unit,
) {
    val status by engine.status.collectAsState()
    var operations by remember { mutableStateOf<List<SyncOperation>>(emptyList()) }
    val scope = rememberCoroutineScope()

    // Re-read on every status change: the list is small and the store is local.
    LaunchedEffect(visible, status) {
        if (visible) operations = engine.operations().filter { it.isOpen }
    }

    ZillitDialogShell(
        title = str(S.desktop_pending_changes),
        subtitle = if (status.online) str(S.desktop_pending_online) else str(S.desktop_pending_offline),
        icon = ZillitIcons.Send,
        visible = visible,
        onDismiss = onDismiss,
        actions = {
            ZillitButton(
                text = str(S.sync_action_sync_now),
                variant = ButtonVariant.Secondary,
                onClick = {
                    scope.launch {
                        connectivity.checkNow()
                        engine.wake()
                    }
                },
            )
            ZillitButton(text = str(S.close), onClick = onDismiss)
        },
    ) {
        if (operations.isEmpty()) {
            ZillitEmptyState(title = str(S.desktop_nothing_waiting), message = str(S.desktop_everything_sent))
        } else {
            operations.forEach { operation ->
                PendingChangeRow(
                    operation = operation,
                    onRetry = { scope.launch { engine.retry(operation.id) } },
                    onDiscard = { scope.launch { engine.discard(operation.id) } },
                )
            }
        }
    }
}

@Composable
private fun PendingChangeRow(operation: SyncOperation, onRetry: () -> Unit, onDiscard: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
            ZillitText(text = operation.label, style = ZillitTheme.typography.bodyMedium)
            val detail = when (operation.state) {
                SyncState.Failed -> operation.lastError ?: str(S.desktop_could_not_be_sent)
                SyncState.Pending -> operation.lastError?.let { str(S.desktop_will_retry, it) }
                    ?: str(S.desktop_waiting_to_send)
                SyncState.InFlight -> str(S.dd_busy_sending)
                SyncState.Done -> str(S.txt_sent)
            }
            ZillitText(text = detail, style = ZillitTheme.typography.labelSmall, color = ZillitTheme.colors.textMuted)
        }
        ZillitStatusPill(label = operation.state.pillLabel(), tone = operation.state.tone(), dot = true)
        if (operation.state == SyncState.Failed) {
            ZillitButton(
                text = str(S.retry),
                size = ButtonSize.Small,
                variant = ButtonVariant.Secondary,
                onClick = onRetry,
            )
        }
        if (operation.state != SyncState.InFlight) {
            ZillitButton(
                text = str(S.ah_discard),
                size = ButtonSize.Small,
                variant = ButtonVariant.Tertiary,
                onClick = onDiscard,
            )
        }
    }
}

private fun SyncState.pillLabel(): String = when (this) {
    SyncState.Pending -> str(S.ah_run_detail_tier_waiting)
    SyncState.InFlight -> str(S.dd_status_sending)
    SyncState.Failed -> str(S.dd_status_failed)
    SyncState.Done -> str(S.txt_sent)
}

private fun SyncState.tone(): StatusTone = when (this) {
    SyncState.Pending -> StatusTone.Pending
    SyncState.InFlight -> StatusTone.InTransit
    SyncState.Failed -> StatusTone.Rejected
    SyncState.Done -> StatusTone.Done
}
