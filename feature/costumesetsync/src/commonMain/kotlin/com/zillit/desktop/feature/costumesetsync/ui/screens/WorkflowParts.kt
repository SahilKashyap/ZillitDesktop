package com.zillit.desktop.feature.costumesetsync.ui.screens

import androidx.compose.foundation.background
import com.zillit.desktop.feature.costumesetsync.ui.SyncDialogShell
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.costumesetsync.domain.Rec
import com.zillit.desktop.feature.costumesetsync.data.Answer
import com.zillit.desktop.feature.costumesetsync.domain.RequestDraft
import com.zillit.desktop.feature.costumesetsync.domain.Say
import com.zillit.desktop.feature.costumesetsync.ui.CostumeRow
import com.zillit.desktop.feature.costumesetsync.ui.DateInput
import com.zillit.desktop.feature.costumesetsync.ui.EmptyState
import com.zillit.desktop.feature.costumesetsync.ui.FormDialog
import com.zillit.desktop.feature.costumesetsync.ui.FormWide
import com.zillit.desktop.feature.costumesetsync.ui.LoadingView
import com.zillit.desktop.feature.costumesetsync.ui.LocalSync
import com.zillit.desktop.feature.costumesetsync.ui.MutedText
import com.zillit.desktop.feature.costumesetsync.ui.SyncCtx
import com.zillit.desktop.feature.costumesetsync.ui.TextInput
import com.zillit.desktop.feature.costumesetsync.ui.t
import com.zillit.desktop.feature.costumesetsync.ui.tEnum
import kotlinx.coroutines.delay

/**
 * Pieces the workflow-ticket screens (fittings, cleaning, alterations, damage,
 * missing) share. Everything here carries a `Wf` prefix so it can never clash
 * with a neighbouring screen's helper of the same idea.
 */

/** The translator the pure message builders in `WorkflowLogic` take: a web `csync_*` key to its words. */
internal val wfSay: Say = { key -> t(key) }

/** A dialog with a title, a scrolling body and a footer of the caller's own buttons (Cancel · Send · Save). */
@Composable
internal fun WfFormDialog(
    open: Boolean,
    title: String,
    onDismiss: () -> Unit,
    actions: @Composable RowScope.() -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 640.dp,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    SyncDialogShell(
        title = title,
        icon = icon,
        visible = open,
        onDismiss = onDismiss,
        modifier = modifier,
        width = width,
        actions = actions,
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md), content = content)
    }
}

/**
 * The footer the web's "Schedule / Schedule & send", "Request / Request & send" and "Report / Report & send"
 * forms share: Cancel, the Send variant (a megaphone in the web), and the plain save. Both stay disabled
 * until [canSave]; both wait while [busy].
 */
@Composable
internal fun RowScope.WfSaveActions(
    onCancel: () -> Unit,
    sendLabel: String,
    onSend: () -> Unit,
    saveLabel: String,
    onSave: () -> Unit,
    canSave: Boolean,
    busy: Boolean,
    danger: Boolean = false,
) {
    ZillitButton(t("csync_cancel"), onClick = onCancel, variant = ButtonVariant.Secondary, enabled = !busy)
    ZillitButton(sendLabel, onClick = onSend, variant = ButtonVariant.Secondary, leadingIcon = ZillitIcons.Send, enabled = canSave && !busy)
    ZillitButton(
        saveLabel,
        onClick = onSave,
        variant = if (danger) ButtonVariant.Danger else ButtonVariant.Primary,
        enabled = canSave && !busy,
        loading = busy,
    )
}

private const val PICK_DEBOUNCE_MS = 300L
private const val PICK_PAGE_SIZE = 100

/**
 * Pick a costume out of the inventory (the web's `CostumePicker`): server-side search over `/costumes`,
 * [exclude] hides pieces that cannot be chosen for the job, and [characterId] opens on that character's
 * pieces ("This character only" turns it off). Picking calls [onPick] and closes.
 */
@Composable
internal fun WfCostumePicker(
    open: Boolean,
    onClose: () -> Unit,
    onPick: (Rec) -> Unit,
    exclude: (Rec) -> Boolean = { false },
    characterId: String = "",
    title: String = t("csync_pick_costume"),
) {
    if (!open) return
    val ctx = LocalSync.current
    var q by remember { mutableStateOf("") }
    var debounced by remember { mutableStateOf("") }
    var onlyCharacter by remember { mutableStateOf(characterId.isNotBlank()) }
    var items by remember { mutableStateOf<List<Rec>?>(null) }
    LaunchedEffect(q) {
        delay(PICK_DEBOUNCE_MS)
        debounced = q
    }
    LaunchedEffect(debounced, onlyCharacter) {
        val params = mapOf(
            "q" to debounced,
            "page" to 1,
            "pageSize" to PICK_PAGE_SIZE,
            "characterId" to characterId.takeIf { onlyCharacter && it.isNotBlank() },
        )
        items = (ctx.api.get("/costumes", params) as? ZillitResult.Success)?.data?.rows.orEmpty()
    }
    SyncDialogShell(
        title = title,
        visible = true,
        onDismiss = onClose,
        // The web's picker has no footer: its close cross is the way out. 520 is antd's default modal width.
        width = 520.dp,
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md), verticalAlignment = Alignment.CenterVertically) {
            SearchWithButton(q, { q = it }, t("csync_picker_search"), Modifier.weight(1f))
            if (characterId.isNotBlank()) ZillitCheckbox(onlyCharacter, { onlyCharacter = it }, label = t("csync_this_character_only"))
        }
        val shown = items?.filterNot(exclude)
        when {
            shown == null -> LoadingView()
            shown.isEmpty() -> EmptyState(t("csync_costumes_empty_title"))
            else -> Column(Modifier.fillMaxWidth()) {
                shown.forEach { c ->
                    CostumeRow(c, onClick = { onPick(c); onClose() })
                }
            }
        }
    }
}

/** The costume a form is about: a row (clicking changes it) or "Choose costume…"; [locked] once the record is filed. */
@Composable
internal fun WfCostumeField(costume: Rec?, onOpen: () -> Unit, locked: Boolean = false) {
    Column(FormWide, verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        ZillitText(t("csync_field_costume"), style = ZillitTheme.typography.label, color = ZillitTheme.colors.textSecondary)
        if (costume != null) {
            CostumeRow(
                costume,
                onClick = if (locked) null else onOpen,
                end = { MutedText(t("csync_change_lower")) },
            )
        } else {
            Row { ZillitButton(t("csync_choose_costume"), onClick = onOpen, variant = ButtonVariant.Secondary, size = ButtonSize.Small) }
        }
    }
}

/**
 * The ordered stages of a ticket as a stepper (the web's `Pipeline`): stages before [current] are ticked,
 * the current one is highlighted, the rest numbered. An empty [current] (a cancelled ticket) ticks nothing.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun WfPipeline(steps: List<String>, current: String, modifier: Modifier = Modifier) {
    val index = if (current.isBlank()) -1 else steps.indexOf(current)
    val colors = ZillitTheme.colors
    androidx.compose.foundation.layout.FlowRow(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        steps.forEachIndexed { i, step ->
            val done = i < index
            val now = i == index
            val last = i == steps.lastIndex
            val line = if (done) colors.success else colors.border
            // The web's step: a node over its label, joined to the next by a 2dp line (centre + 14 to next centre - 12).
            Column(
                Modifier
                    .widthIn(min = 72.dp)
                    .drawBehind {
                        if (!last) {
                            drawRect(line, Offset(size.width / 2 + 14.dp.toPx(), 10.dp.toPx()), Size(size.width - 26.dp.toPx(), 2.dp.toPx()))
                        }
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Box(
                    Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(if (done) colors.success else if (now) colors.textPrimary else colors.surface)
                        .border(1.5.dp, if (done) colors.success else if (now) colors.textPrimary else colors.border, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    ZillitText(
                        if (done) "\u2713" else (i + 1).toString(),
                        style = ZillitTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
                        color = if (done) androidx.compose.ui.graphics.Color.White else if (now) colors.surface else colors.textMuted,
                    )
                }
                ZillitText(
                    tEnum(step),
                    style = ZillitTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = if (done || now) FontWeight.SemiBold else FontWeight.Normal),
                    color = if (done || now) colors.textPrimary else colors.textMuted,
                    maxLines = 2,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }
}

/**
 * The web's `.csync-notice`: a 14sp box, 10/14 padding, 10dp radius. [info] is the blue one (a fitting's notes), [warn]
 * the amber one; otherwise it is the quiet grey well.
 */
@Composable
internal fun WfNotice(text: String, modifier: Modifier = Modifier, info: Boolean = false, warn: Boolean = false, ok: Boolean = false, trailing: (@Composable () -> Unit)? = null) {
    val colors = ZillitTheme.colors
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp)
    val (bg, edge) = when {
        info -> colors.infoSoft to colors.info.copy(alpha = 0.25f)
        warn -> colors.accentSoft to colors.accent.copy(alpha = 0.35f)
        ok -> colors.successSoft to colors.success.copy(alpha = 0.3f)
        else -> colors.surfaceSunken to androidx.compose.ui.graphics.Color.Transparent
    }
    Box(modifier.fillMaxWidth().clip(shape).background(bg).border(1.dp, edge, shape).padding(horizontal = 14.dp, vertical = 10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            ZillitText(text, Modifier.weight(1f, fill = false), style = ZillitTheme.typography.bodyMedium.copy(fontSize = 14.sp), color = if (ok) colors.success else colors.textPrimary)
            trailing?.invoke()
        }
    }
}

/** A text-only button in a chosen colour (antd `type="text"`, e.g. the red Cancel request). */
@Composable
internal fun WfTextButton(text: String, color: androidx.compose.ui.graphics.Color, onClick: () -> Unit, enabled: Boolean = true) {
    ZillitText(
        text,
        Modifier.defaultMinSize(minHeight = 32.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp)).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 15.dp, vertical = 6.dp),
        style = ZillitTheme.typography.button,
        color = if (enabled) color else color.copy(alpha = 0.4f),
    )
}

/** The 56dp initials tile of a detail page's title (`csync-avatar--lg`: 14dp radius, 18sp). */
@Composable
internal fun WfLargeAvatar(text: String) {
    val colors = ZillitTheme.colors
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)
    Box(Modifier.size(56.dp).clip(shape).background(colors.surfaceSunken).border(1.dp, colors.border, shape), contentAlignment = Alignment.Center) {
        ZillitText(text.ifBlank { "\u2013" }, style = ZillitTheme.typography.bodyMedium.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold), color = colors.textMuted, maxLines = 1)
    }
}

/** A small button that reads solid ink while it is the current choice (the web's `csync-btn-ink`), white otherwise. */
@Composable
internal fun WfInkButton(
    text: String,
    onClick: () -> Unit,
    on: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    enabled: Boolean = true,
    large: Boolean = false,
) {
    val colors = ZillitTheme.colors
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp)
    val fg = if (on) colors.surface else colors.textPrimary
    Row(
        Modifier
            .defaultMinSize(minHeight = if (large) 32.dp else 26.dp)
            .clip(shape)
            .background(if (on) colors.textPrimary else colors.surface)
            .border(1.dp, if (on) colors.textPrimary else colors.border, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = if (large) 15.dp else 8.dp),
        horizontalArrangement = Arrangement.spacedBy(if (large) 8.dp else 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let { com.zillit.desktop.core.designsystem.component.ZillitIcon(it, tint = fg, size = if (large) 15.dp else 14.dp) }
        ZillitText(text, style = ZillitTheme.typography.button, color = fg, maxLines = 1)
    }
}

/** First letters of the first two words, upper-case (the web's `initials`). */
internal fun wfInitials(name: String): String =
    name.split(Regex("\\s+")).filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }

/**
 * A follow-up write whose success needs no toast of its own (adding each piece to a freshly booked
 * fitting): a refusal is toasted in the server's words, success is silent. True when it went through.
 */
internal suspend fun SyncCtx.quietWrite(call: suspend () -> ZillitResult<Answer>): Boolean = when (val result = call()) {
    is ZillitResult.Success -> true
    is ZillitResult.Failure -> {
        toast(result.error.localised(), false)
        false
    }
}

/** The row-level "Send request" (the web's megaphone in `RecordActions`); only people who can post see it. */
@Composable
internal fun WfSendRequestButton(onClick: () -> Unit) {
    if (!LocalSync.current.canPost) return
    // Icon only, as the web's RecordActions megaphone; the words are its tooltip / accessible name.
    com.zillit.desktop.core.designsystem.component.ZillitIconButton(
        ZillitIcons.Send,
        t("csync_send_request"),
        onClick,
        tint = ZillitTheme.colors.textSecondary,
        size = 28.dp,
    )
}

/** The "Send request" dialog driven by a [draft] (null = closed), as every screen of the tickets uses it. */
@Composable
internal fun WfDraftRequestDialog(draft: RequestDraft?, entityType: String, title: String, onClose: () -> Unit) {
    SendRequestDialog(
        open = draft != null,
        onClose = onClose,
        title = title,
        entityType = entityType,
        entityId = draft?.entityId,
        defaultTitle = draft?.title.orEmpty(),
        defaultBody = draft?.body.orEmpty(),
    )
}

/** A short yes/no question in a dialog — discard a note, cancel a ticket, write a piece off. */
@Composable
internal fun WfConfirm(open: Boolean, title: String, body: String, confirmLabel: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    FormDialog(
        open = open,
        title = title,
        onDismiss = onDismiss,
        confirmLabel = confirmLabel,
        onConfirm = onConfirm,
        danger = true,
        width = 440.dp,
    ) {
        MutedText(body, maxLines = 4)
    }
}

/** The sink's view switch: one 24dp icon button, solid ink when it is the one showing (the web's `csync-btn-ink`). */
@Composable
internal fun WfViewToggle(icon: androidx.compose.ui.graphics.vector.ImageVector, on: Boolean, onClick: () -> Unit) {
    val colors = ZillitTheme.colors
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp)
    Box(
        Modifier
            .size(24.dp)
            .clip(shape)
            .background(if (on) colors.textPrimary else colors.surface)
            .border(1.dp, if (on) colors.textPrimary else colors.border, shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        com.zillit.desktop.core.designsystem.component.ZillitIcon(icon, tint = if (on) colors.surface else colors.textPrimary, size = 14.dp)
    }
}

/**
 * The web's `csync-prio` pill: one colour per priority (urgent red, high amber, normal blue, low grey),
 * 11sp semibold, with the siren in front of an Emergency.
 */
@Composable
internal fun WfPriorityPill(priority: String, label: String, emergency: Boolean) {
    val colors = ZillitTheme.colors
    val (fg, bg, edge) = when (priority.uppercase()) {
        "URGENT" -> Triple(colors.danger, colors.dangerSoft, colors.danger)
        "HIGH" -> Triple(colors.accentText, colors.accentSoft, colors.accent.copy(alpha = 0.4f))
        "LOW" -> Triple(colors.textMuted, colors.surfaceSunken, colors.border)
        else -> Triple(colors.info, colors.infoSoft, androidx.compose.ui.graphics.Color.Transparent)
    }
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(999.dp)
    Row(
        Modifier.clip(shape).background(bg).border(1.dp, edge, shape).padding(horizontal = 8.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (emergency) com.zillit.desktop.core.designsystem.component.ZillitIcon(ZillitIcons.Siren, tint = fg, size = 12.dp)
        ZillitText(label, style = ZillitTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold), color = fg, maxLines = 1)
    }
}
