package com.zillit.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.zillit.desktop.core.designsystem.component.ZillitErrorToast
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.core.socket.SocketConnectionState
import com.zillit.desktop.core.sync.SyncEngine
import com.zillit.desktop.core.sync.SyncStatus

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

private fun Int.changes(): String = if (this == 1) str(S.desktop_one_change) else str(S.desktop_n_changes, this)

private fun SocketConnectionState.statusLabel(): String = when (this) {
    is SocketConnectionState.Connected -> str(S.desktop_status_live)
    SocketConnectionState.Connecting -> str(S.txt_connecting)
    is SocketConnectionState.Reconnecting -> str(S.txt_reconnecting)
    is SocketConnectionState.Failed -> str(S.desktop_status_paused)
    SocketConnectionState.Disconnected -> str(S.desktop_status_off)
}

/**
 * The queue itself is silent — a connectivity failure retries itself the
 * moment the network returns, no dialog needed. This only speaks up for what
 * won't fix itself: an operation the server refused outright.
 */
@Composable
fun SyncFailureToast(engine: SyncEngine) {
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(engine) {
        engine.failures.collect { failure ->
            message = str(S.desktop_sync_failed_toast, failure.label, failure.message)
        }
    }
    ZillitErrorToast(message = message, onDismiss = { message = null })
}
