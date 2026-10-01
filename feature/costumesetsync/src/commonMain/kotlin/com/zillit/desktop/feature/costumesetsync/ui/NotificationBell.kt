package com.zillit.desktop.feature.costumesetsync.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.costumesetsync.domain.NotificationsModel
import kotlinx.coroutines.delay

private const val POLL_MS = 30_000L
private const val WRITE_SETTLE_MS = 800L

/**
 * The header bell, as the web's: opens the Notifications page, with a red pip for the unread count ("99+"
 * past 99). The count is read every 30 s, whenever the page changes, and just after any successful write
 * ([refreshTick] bumps) so opening a notification (which marks it read) clears the pip without a refresh.
 * Only unread ones are asked for. Nothing is asked until the tool may be called — the shell only composes
 * this behind its gate, so a 403 from this service can never bounce the user out of the project.
 */
@Composable
fun NotificationBell(refreshTick: Int = 0) {
    val ctx = LocalSync.current
    var unread by remember { mutableStateOf(0) }
    val route = ctx.nav.current

    LaunchedEffect(route, refreshTick) {
        // A write just landed: give the service a beat, as the web debounces `csync:changed`.
        if (refreshTick > 0) delay(WRITE_SETTLE_MS)
        while (true) {
            (ctx.api.get("/notifications", mapOf("unread" to "true")) as? ZillitResult.Success)?.let { unread = it.data.rec?.long("unread")?.toInt() ?: 0 }
            delay(POLL_MS)
        }
    }
    val title = t("csync_notifications_title")
    val label = if (unread > 0) "$title — ${t("csync_notifications_unread", "n" to unread)}" else title
    Box {
        ZillitIconButton(ZillitIcons.Bell, label, onClick = { ctx.nav.go("notifications") })
        if (unread > 0) {
            Box(Modifier.align(Alignment.TopEnd).background(ZillitTheme.colors.danger, RoundedCornerShape(PIP_RADIUS)).padding(horizontal = 4.dp)) {
                ZillitText(NotificationsModel.badge(unread), style = ZillitTheme.typography.labelSmall, color = ZillitTheme.colors.textOnAccent, maxLines = 1)
            }
        }
    }
}

private val PIP_RADIUS = 8.dp
