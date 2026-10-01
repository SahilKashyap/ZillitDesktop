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
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
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
    content: @Composable ColumnScope.() -> Unit,
) {
    SyncDialogShell(
        title = title,
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

private val DATE_WIDTH = 300.dp
private val TIME_WIDTH = 150.dp

/** A date plus an `HH:mm` time — the web's date-time picker as two fields on one row. */
@Composable
internal fun WfDateTimeInput(
    date: String,
    time: String,
    onDate: (String) -> Unit,
    onTime: (String) -> Unit,
    label: String,
    modifier: Modifier = FormWide,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
        DateInput(date, onDate, label, Modifier.width(DATE_WIDTH))
        TextInput(time, onTime, "HH:mm", Modifier.width(TIME_WIDTH), placeholder = "HH:mm")
    }
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
        actions = { ZillitButton(t("csync_cancel"), onClick = onClose, variant = ButtonVariant.Secondary) },
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md), verticalAlignment = Alignment.CenterVertically) {
            ZillitSearchField(q, { q = it }, Modifier.weight(1f), placeholder = t("csync_picker_search"))
            if (characterId.isNotBlank()) ZillitCheckbox(onlyCharacter, { onlyCharacter = it }, label = t("csync_this_character_only"))
        }
        val shown = items?.filterNot(exclude)
        when {
            shown == null -> LoadingView()
            shown.isEmpty() -> EmptyState(t("csync_costumes_empty_title"))
            else -> Column(Modifier.fillMaxWidth()) {
                shown.forEach { c ->
                    CostumeRow(c, onClick = { onPick(c); onClose() }, end = { ZillitText("+", style = ZillitTheme.typography.titleMedium) })
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

private val STEP_DOT = 24.dp

/**
 * The ordered stages of a ticket as a stepper (the web's `Pipeline`): stages before [current] are ticked,
 * the current one is highlighted, the rest numbered. An empty [current] (a cancelled ticket) ticks nothing.
 */
@Composable
internal fun WfPipeline(steps: List<String>, current: String, modifier: Modifier = Modifier) {
    val index = if (current.isBlank()) -1 else steps.indexOf(current)
    val colors = ZillitTheme.colors
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
        steps.forEachIndexed { i, step ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
                val fill = when {
                    i == index -> colors.accent
                    i < index -> colors.success
                    else -> colors.surfaceSunken
                }
                Box(Modifier.size(STEP_DOT).clip(CircleShape).background(fill), contentAlignment = Alignment.Center) {
                    ZillitText(
                        if (i < index) "✓" else (i + 1).toString(),
                        style = ZillitTheme.typography.labelSmall,
                        color = if (i <= index) colors.textOnAccent else colors.textSecondary,
                    )
                }
                ZillitText(
                    tEnum(step),
                    style = ZillitTheme.typography.labelSmall.copy(fontWeight = if (i == index) FontWeight.SemiBold else FontWeight.Medium),
                    color = if (i == index) colors.textPrimary else colors.textSecondary,
                    maxLines = 2,
                )
            }
        }
    }
}

/** A thin padded block of one muted line under a card's title. */
@Composable
internal fun WfNotice(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().clip(ZillitTheme.shapes.medium).background(ZillitTheme.colors.surfaceSunken).padding(ZillitTheme.spacing.md)) {
        ZillitText(text, style = ZillitTheme.typography.bodyMedium)
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
    ZillitButton(t("csync_send_request"), onClick = onClick, variant = ButtonVariant.Tertiary, size = ButtonSize.Small, leadingIcon = ZillitIcons.Send)
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
