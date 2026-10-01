package com.zillit.desktop.feature.costumesetsync.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.feature.costumesetsync.domain.NotificationsModel
import com.zillit.desktop.feature.costumesetsync.domain.Rec
import com.zillit.desktop.feature.costumesetsync.ui.Await
import com.zillit.desktop.feature.costumesetsync.ui.EmptyState
import com.zillit.desktop.feature.costumesetsync.ui.ListRow
import com.zillit.desktop.feature.costumesetsync.ui.LocalSync
import com.zillit.desktop.feature.costumesetsync.ui.MutedText
import com.zillit.desktop.feature.costumesetsync.ui.PageHead
import com.zillit.desktop.feature.costumesetsync.ui.ReadinessDot
import com.zillit.desktop.feature.costumesetsync.ui.SectionCard
import com.zillit.desktop.feature.costumesetsync.ui.SyncCtx
import com.zillit.desktop.feature.costumesetsync.ui.body
import com.zillit.desktop.feature.costumesetsync.ui.rememberResource
import com.zillit.desktop.feature.costumesetsync.ui.t
import kotlinx.coroutines.launch

/**
 * My notifications for this production, newest first: unread ones tinted (the tint alone marks them, as
 * the web), "Mark all read", and a click marks one read and opens what it is about.
 *
 * Wire shape is Zillit's, snake_case: `{ items, unread }`, each item `{ _id, type, severity, title, body,
 * entity_type, entity_id, read, created }`. Every field is read defensively.
 */
@Composable
fun NotificationsScreen() {
    val ctx = LocalSync.current
    val data = rememberResource { api.get("/notifications") }
    var marking by remember { mutableStateOf(false) }

    val unread = data.value?.rec?.long("unread")?.toInt() ?: 0
    PageHead(
        title = t("csync_notifications_title"),
        sub = if (data.value != null) t("csync_notifications_unread", "n" to unread) else "",
        actions = {
            ZillitButton(
                t("csync_notifications_mark_all_read"),
                onClick = {
                    marking = true
                    // No ids: the service marks every one of mine read.
                    ctx.scope.launch {
                        ctx.write { ctx.api.post("/notifications/read") }?.let { data.reload(silent = true) }
                        marking = false
                    }
                },
                variant = ButtonVariant.Secondary,
                enabled = unread > 0 && !marking,
                loading = marking,
            )
        },
    )
    Await(data) { answer ->
        val items = answer.rec?.recs("items").orEmpty()
        SectionCard(flush = true) {
            if (items.isEmpty()) {
                EmptyState(t("csync_notifications_empty_title"))
            } else {
                items.forEach { n -> NotificationRow(ctx, n) { data.reload(silent = true) } }
            }
        }
    }
}

@Composable
private fun NotificationRow(ctx: SyncCtx, n: Rec, reload: () -> Unit) {
    val time = NotificationsModel.time(n)
    Row(Modifier.fillMaxWidth().then(if (n.bool("read")) Modifier else Modifier.background(ZillitTheme.colors.accentSoft))) {
        ListRow(
            onClick = { open(ctx, n, reload) },
            leading = { ReadinessDot(n.str("severity")) },
            end = { MutedText(kitRelativeTime(time, ctx.now())) },
        ) {
            ZillitText(n.str("title"), style = ZillitTheme.typography.titleSmall, maxLines = 2)
            if (n.str("body").isNotBlank()) MutedText(n.str("body"), maxLines = 3)
        }
    }
}

private fun open(ctx: SyncCtx, n: Rec, reload: () -> Unit) {
    val id = n.id
    val to = NotificationsModel.target(n)
    if (!n.bool("read") && id.isNotEmpty()) {
        // Incidental to opening it, so a success is silent (the pip clearing is the feedback); a failure still shows.
        ctx.scope.launch {
            when (val res = ctx.api.post("/notifications/read", body("ids" to listOf(id)))) {
                is ZillitResult.Failure -> ctx.toast(res.error.localised(), false)
                is ZillitResult.Success -> {
                    ctx.changed()
                    // Nowhere to go, so we are still on this page: show it read.
                    if (to == null) reload()
                }
            }
        }
    }
    if (to != null) ctx.nav.go(to)
}
