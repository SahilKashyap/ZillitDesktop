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
        "Offline — showing what's saved on this computer; ${sync.pending.changes()} will be sent when you're back"
    !sync.online -> "Offline — showing what's saved on this computer; changes you make will be sent when you're back"
    else -> socket.statusLabel()
}

/** The status bar's clickable summary of the queue, or null when it is empty. */
fun syncStatusAction(sync: SyncStatus, onClick: () -> Unit): StatusAction? {
    val text = when {
        sync.syncing -> "Sending ${sync.pending.changes()}…"
        sync.failed > 0 -> "${sync.failed.changes()} need attention"
        sync.pending > 0 -> "${sync.pending.changes()} waiting to send"
        sync.elsewhere > 0 -> "${sync.elsewhere.changes()} waiting in other productions"
        else -> return null
    }
    return StatusAction(text = text, attention = sync.failed > 0, onClick = onClick)
}

private fun Int.changes(): String = if (this == 1) "1 change" else "$this changes"

private fun SocketConnectionState.statusLabel(): String = when (this) {
    is SocketConnectionState.Connected -> "Live"
    SocketConnectionState.Connecting -> "Connecting…"
    is SocketConnectionState.Reconnecting -> "Reconnecting…"
    is SocketConnectionState.Failed -> "Live updates paused"
    SocketConnectionState.Disconnected -> "Live updates off"
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
        title = "Pending changes",
        subtitle = if (status.online) "Sent in the order you made them." else "Waiting for a connection.",
        icon = ZillitIcons.Send,
        visible = visible,
        onDismiss = onDismiss,
        actions = {
            ZillitButton(
                text = "Sync now",
                variant = ButtonVariant.Secondary,
                onClick = {
                    scope.launch {
                        connectivity.checkNow()
                        engine.wake()
                    }
                },
            )
            ZillitButton(text = "Close", onClick = onDismiss)
        },
    ) {
        if (operations.isEmpty()) {
            ZillitEmptyState(title = "Nothing waiting", message = "Everything you did here has been sent.")
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
                SyncState.Failed -> operation.lastError ?: "Could not be sent."
                SyncState.Pending -> operation.lastError?.let { "Will retry — $it" } ?: "Waiting to send"
                SyncState.InFlight -> "Sending…"
                SyncState.Done -> "Sent"
            }
            ZillitText(text = detail, style = ZillitTheme.typography.labelSmall, color = ZillitTheme.colors.textMuted)
        }
        ZillitStatusPill(label = operation.state.pillLabel(), tone = operation.state.tone(), dot = true)
        if (operation.state == SyncState.Failed) {
            ZillitButton(text = "Retry", size = ButtonSize.Small, variant = ButtonVariant.Secondary, onClick = onRetry)
        }
        if (operation.state != SyncState.InFlight) {
            ZillitButton(
                text = "Discard",
                size = ButtonSize.Small,
                variant = ButtonVariant.Tertiary,
                onClick = onDiscard,
            )
        }
    }
}

private fun SyncState.pillLabel(): String = when (this) {
    SyncState.Pending -> "Waiting"
    SyncState.InFlight -> "Sending"
    SyncState.Failed -> "Failed"
    SyncState.Done -> "Sent"
}

private fun SyncState.tone(): StatusTone = when (this) {
    SyncState.Pending -> StatusTone.Pending
    SyncState.InFlight -> StatusTone.InTransit
    SyncState.Failed -> StatusTone.Rejected
    SyncState.Done -> StatusTone.Done
}
