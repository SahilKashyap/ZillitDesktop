package com.zillit.desktop.feature.costumesetsync.ui.screens

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitActionMenu
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.copyTextToClipboard
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.costumesetsync.data.SyncEvents
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.costumesetsync.domain.PrintHtml
import com.zillit.desktop.feature.costumesetsync.domain.Rec
import com.zillit.desktop.feature.costumesetsync.domain.relativeTime
import com.zillit.desktop.feature.costumesetsync.ui.LocalSync
import com.zillit.desktop.feature.costumesetsync.ui.MutedText
import com.zillit.desktop.feature.costumesetsync.ui.SocketRefresh
import com.zillit.desktop.feature.costumesetsync.ui.SyncCtx
import com.zillit.desktop.feature.costumesetsync.ui.SyncDialogShell
import com.zillit.desktop.feature.costumesetsync.ui.TextInput
import com.zillit.desktop.feature.costumesetsync.ui.body
import com.zillit.desktop.feature.costumesetsync.ui.t
import kotlinx.coroutines.launch

private val CHAT_MAX_HEIGHT = 360.dp
private val CHAT_MIN_HEIGHT = 200.dp
private const val CHAT_WIDTH = 640

/**
 * A list's comment counts for one entity type (`GET /comments/counts`, id → count), kept current by the
 * comment socket events. Read once per list and hand each row its own count, as the web's callers do.
 */
@Composable
fun rememberCommentCounts(entityType: String): Map<String, Int> {
    val ctx = LocalSync.current
    var counts by remember(entityType) { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var tick by remember(entityType) { mutableStateOf(0) }
    LaunchedEffect(entityType, tick) {
        val rec = ((ctx.api.get(
            "/comments/counts",
            mapOf("entityType" to entityType),
        ) as? ZillitResult.Success)?.data)?.rec
        counts = rec?.json?.keys.orEmpty().associateWith { rec!!.int(it) }
    }
    SocketRefresh(SyncEvents.Comment) { tick++ }
    return counts
}

/** The page's comment counts (id → count), so a row's [RecordActions] needs no count of its own. */
val LocalRecordCounts = androidx.compose.runtime.compositionLocalOf<Map<String, Int>> { emptyMap() }

/**
 * The web's `RecordActions` minus the megaphone (each screen already opens its own Send a request):
 * Share and Chat for one record, with the chat's comment count beside the bubble. [summary] is what a
 * share carries. Share opens the Email composer with it, as the web's `RecordShareMenu` does
 * (`shareMessagesAsEmail`); Copy beside it puts the same text on the clipboard. The web's other entry,
 * Forward In App (the chat forward picker), is not ported.
 */
@Composable
fun RecordActions(
    entityType: String,
    entityId: String,
    title: String,
    summary: String,
    modifier: Modifier = Modifier,
    count: Int? = null,
) {
    var chatOpen by remember { mutableStateOf(false) }
    val listed = count ?: LocalRecordCounts.current[entityId] ?: 0
    var live by remember(listed) { mutableStateOf(listed) }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        ShareMenu(title, summary)
        ZillitIconButton(
            ZillitIcons.Chat,
            "${t("csync_chat")} — $title",
            onClick = { chatOpen = true },
            tint = ZillitTheme.colors.textSecondary,
            size = 28.dp,
        )
        if (live > 0) {
            ZillitText(
                live.toString(),
                Modifier.padding(end = 4.dp),
                style = ZillitTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold),
                color = ZillitTheme.colors.textSecondary,
            )
        }
    }
    if (chatOpen) RecordChatDialog(entityType, entityId, title, onClose = { chatOpen = false }, onCount = { live = it })
}

/** The share button and its two entries: Share (the Email composer) and Copy (the clipboard). */
@Composable
private fun ShareMenu(title: String, summary: String) {
    val ctx = LocalSync.current
    var open by remember { mutableStateOf(false) }
    Box {
        ZillitIconButton(
            com.zillit.desktop.core.designsystem.icon.AhIcons.Share,
            "${t("csync_share")} — $title",
            onClick = { open = true },
            tint = ZillitTheme.colors.textSecondary,
            size = 28.dp,
        )
        ZillitActionMenu(
            expanded = open,
            onDismissRequest = { open = false },
            entries = listOf(
                menuAction(t("csync_share")) {
                    open = false
                    val html = PrintHtml.esc(summary).replace("\n", "<br>")
                    if (!ctx.host.composeEmail(title, html)) ctx.toast(str(S.desktop_email_id_not_available), false)
                },
                menuAction(t("csync_copy")) {
                    open = false
                    copyTextToClipboard(summary)
                    ctx.toast(t("csync_copied"), true)
                },
            ),
        )
    }
}

@Composable
private fun ChatMessages(messages: List<Rec>?, scroll: ScrollState, onAskDelete: (String) -> Unit) {
    val ctx = LocalSync.current
    Column(
        Modifier.fillMaxWidth().heightIn(min = CHAT_MIN_HEIGHT, max = CHAT_MAX_HEIGHT).verticalScroll(scroll),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val list = messages
        when {
            list == null -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                MutedText("…")
            }
            list.isEmpty() -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                MutedText(t("csync_chat_empty"), maxLines = 3)
            }
            else -> list.forEach { m -> ChatMessage(m, ctx.currentUserId, ctx.now()) { onAskDelete(m.id) } }
        }
    }
}

@Composable
private fun ChatComposer(text: String, onText: (String) -> Unit, canSend: Boolean, onSend: () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        TextInput(
            text,
            onText,
            label = "",
            modifier = Modifier.weight(1f).onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && e.key == Key.Enter && !e.isShiftPressed) {
                    onSend()
                    true
                } else {
                    false
                }
            },
            placeholder = t("csync_chat_placeholder"),
            multiline = true,
        )
        ZillitIconButton(ZillitIcons.Send, t("csync_send"), onSend, enabled = canSend, size = 36.dp, filled = canSend)
    }
}

private suspend fun SyncCtx.loadComments(entityType: String, entityId: String): List<Rec> =
    (api.get("/comments", mapOf("entityType" to entityType, "entityId" to entityId)) as? ZillitResult.Success)
        ?.data?.rows.orEmpty()

private suspend fun SyncCtx.postComment(entityType: String, entityId: String, text: String): Boolean =
    write {
        api.post("/comments", body("entity_type" to entityType, "entity_id" to entityId, "body" to text))
    } != null

@Composable
internal fun RecordChatDialog(
    entityType: String,
    entityId: String,
    title: String,
    onClose: () -> Unit,
    onCount: (Int) -> Unit,
) {
    val ctx = LocalSync.current
    var messages by remember { mutableStateOf<List<Rec>?>(null) }
    var text by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var tick by remember { mutableStateOf(0) }
    var confirm by remember { mutableStateOf<String?>(null) }
    val scroll = rememberScrollState()

    LaunchedEffect(tick) {
        val list = ctx.loadComments(entityType, entityId)
        messages = list
        onCount(list.size)
    }
    LaunchedEffect(messages?.size) { scroll.scrollTo(scroll.maxValue) }
    SocketRefresh(
        SyncEvents.Comment,
        predicate = { it.str("entity_id").let { id -> id.isBlank() || id == entityId } },
    ) {
        tick++
    }

    val canSend = text.isNotBlank() && !sending
    val send: () -> Unit = {
        if (canSend) {
            sending = true
            ctx.scope.launch {
                val ok = ctx.postComment(entityType, entityId, text.trim())
                sending = false
                if (ok) {
                    text = ""
                    tick++
                }
            }
        }
    }

    SyncDialogShell(
        title = "${t("csync_chat")} · $title",
        visible = true,
        onDismiss = onClose,
        width = CHAT_WIDTH.dp,
        scrollable = false,
    ) {
        ChatMessages(messages, scroll) { confirm = it }
        Box(Modifier.fillMaxWidth().padding(vertical = 12.dp).size(1.dp).background(ZillitTheme.colors.border))
        ChatComposer(text, { text = it }, canSend, send)
    }
    WfConfirm(
        open = confirm != null,
        title = t("csync_delete_message_confirm"),
        body = "",
        confirmLabel = t("csync_delete"),
        onConfirm = {
            val id = confirm.orEmpty()
            confirm = null
            ctx.scope.launch { if (ctx.write { ctx.api.delete("/comments/$id") } != null) tick++ }
        },
        onDismiss = { confirm = null },
    )
}

@Composable
private fun ChatMessage(m: Rec, me: String, now: Long, onDelete: () -> Unit) {
    val colors = ZillitTheme.colors
    val author = m.str("user_id").ifBlank { m.str("created_by") }
    val mine = me.isNotBlank() && author == me
    val name = m.str("user_name").ifBlank { m.str("created_by_name") }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top,
    ) {
        if (!mine) {
            SquareAvatar(wfInitials(name))
            Box(Modifier.size(8.dp))
        }
        Column(
            Modifier.widthIn(max = 460.dp).clip(RoundedCornerShape(10.dp)).background(
                if (mine) colors.accentSoft else colors.surfaceSunken,
            ).padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                ZillitText(
                    (if (mine) t("csync_you") else name) + " · " + relativeTime(m.long("created"), now, ::t),
                    style = ZillitTheme.typography.labelSmall.copy(fontSize = 11.sp),
                    color = colors.textMuted,
                )
                if (mine) ZillitIconButton(
                    ZillitIcons.Trash,
                    t("csync_delete"),
                    onDelete,
                    tint = colors.textMuted,
                    size = 20.dp,
                )
            }
            ZillitText(m.str("body"), style = ZillitTheme.typography.bodyMedium)
        }
    }
}
