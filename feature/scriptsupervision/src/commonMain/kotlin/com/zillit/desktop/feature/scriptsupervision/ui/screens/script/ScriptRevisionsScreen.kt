package com.zillit.desktop.feature.scriptsupervision.ui.screens.script

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.scriptsupervision.data.ContinuityCrud
import com.zillit.desktop.feature.scriptsupervision.domain.Rec
import com.zillit.desktop.feature.scriptsupervision.domain.Script
import com.zillit.desktop.feature.scriptsupervision.ui.Await
import com.zillit.desktop.feature.scriptsupervision.ui.DeleteDialog
import com.zillit.desktop.feature.scriptsupervision.ui.Field
import com.zillit.desktop.feature.scriptsupervision.ui.FormDialog
import com.zillit.desktop.feature.scriptsupervision.ui.FormGrid
import com.zillit.desktop.feature.scriptsupervision.ui.FormHalf
import com.zillit.desktop.feature.scriptsupervision.ui.FormWide
import com.zillit.desktop.feature.scriptsupervision.ui.LocalSs
import com.zillit.desktop.feature.scriptsupervision.ui.MultiPickInput
import com.zillit.desktop.feature.scriptsupervision.ui.MutedText
import com.zillit.desktop.feature.scriptsupervision.ui.PickInput
import com.zillit.desktop.feature.scriptsupervision.ui.RowActions
import com.zillit.desktop.feature.scriptsupervision.ui.SsCtx
import com.zillit.desktop.feature.scriptsupervision.ui.SsPageHeader
import com.zillit.desktop.feature.scriptsupervision.ui.SsTag
import com.zillit.desktop.feature.scriptsupervision.ui.TagInput
import com.zillit.desktop.feature.scriptsupervision.ui.TextInput
import com.zillit.desktop.feature.scriptsupervision.ui.body
import com.zillit.desktop.feature.scriptsupervision.ui.dropEmpty
import com.zillit.desktop.feature.scriptsupervision.ui.rememberRows
import com.zillit.desktop.feature.scriptsupervision.ui.scenes
import com.zillit.desktop.feature.scriptsupervision.ui.t
import kotlinx.coroutines.launch

/**
 * The script's revisions — the web's `ScriptRevisionsScreen`.
 *
 * A production's script is reissued on coloured paper, and the COLOUR is the
 * version: white, then blue, then pink, and so on in an order the whole
 * industry shares. That is why the colour is a pick-list from the service's
 * own enum and is drawn as the paper it names — a revision labelled "pink"
 * has to look pink, or a reader cannot match it to the pages in their hand.
 */
@Suppress("LongMethod") // Header, the list and two dialogs.
@Composable
internal fun ScriptRevisionsScreen() {
    val ctx = LocalSs.current
    var editing by remember { mutableStateOf<Rec?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Rec?>(null) }
    val crud = remember(ctx) { ContinuityCrud(ctx.api, "script-revisions") }
    val rows = rememberRows(ctx) { crud.list() }

    SsPageHeader(
        title = t("script_revisions"),
        actions = {
            ZillitButton(
                text = t("add_revision"),
                onClick = { ctx.whenPost { creating = true } },
                leadingIcon = ZillitIcons.Add,
            )
        },
    )
    Await(rows) { revisions ->
        if (revisions.isEmpty()) {
            ZillitEmptyState(
                title = t("no_revisions"),
                message = "",
                action = {
                    ZillitButton(
                        text = t("add_revision"),
                        onClick = { ctx.whenPost { creating = true } },
                        variant = ButtonVariant.Secondary,
                    )
                },
            )
        } else {
            revisions.forEach { revision ->
                RevisionCard(revision, onEdit = { editing = revision }, onDelete = { deleting = revision })
            }
        }
    }

    if (creating || editing != null) {
        RevisionForm(
            revision = editing,
            onClose = { creating = false; editing = null },
            onSaved = { rows.reload(silent = true) },
        )
    }
    deleting?.let { revision ->
        DeleteDialog(
            title = t("delete_revision_title"),
            name = revision.str("revision_number"),
            onClose = { deleting = null },
            onDone = { rows.reload(silent = true) },
            run = { crud.remove(revision.id) },
        )
    }
}

@Composable
private fun RevisionCard(revision: Rec, onEdit: () -> Unit, onDelete: () -> Unit) {
    val sceneState = scenes()
    val colour = revision.str("revision_color")
    ZillitSectionCard(
        modifier = Modifier.fillMaxWidth(),
        action = { RowActions(onEdit = onEdit, onDelete = onDelete) },
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Swatch(colour)
            Column(Modifier.weight(1f)) {
                ZillitText(
                    text = revision.str("revision_number").ifBlank { t("script_revisions") },
                    style = ZillitTheme.typography.titleSmall,
                )
                MutedText(
                    listOf(
                        colour.takeIf { it.isNotBlank() }?.let { t("revision_color_$it") },
                        revision.str("revision_date").takeIf { it.isNotBlank() },
                        revision.str("issued_by").takeIf { it.isNotBlank() },
                    ).filterNotNull().joinToString(" · "),
                )
            }
        }
        Field(t("summary"), revision.str("summary"))
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
            // The service stores 0 for a page count nobody entered; a tag for
            // a zero would read as a real "0 pages added".
            revision.int("pages_added").takeIf { it > 0 }?.let { SsTag("+$it") }
            revision.int("pages_removed").takeIf { it > 0 }?.let { SsTag("−$it") }
            revision.int("total_pages_after").takeIf { it > 0 }?.let { SsTag("$it ${t("pages")}") }
        }
        Field(t("pages_revised"), revision.strings("pages_revised").joinToString(", "))
        Field(
            t("scenes_affected"),
            revision.strings("scenes_affected")
                .mapNotNull { id -> sceneState.scene(id)?.str("scene_number") }
                .joinToString(", "),
        )
        Field(t("distributed_to"), revision.strings("distributed_to").joinToString(", "))
        Field(t("notes"), revision.str("notes"))
    }
}

/**
 * A revision colour's round swatch.
 *
 * Edged on purpose: white paper on a white card is the first revision of
 * every production, and without the edge it would be invisible.
 */
@Composable
private fun Swatch(colour: String) {
    val fill = Script.revisionHex(colour)?.let(::hexColor) ?: ZillitTheme.colors.surfaceHover
    Box(
        Modifier
            .size(SWATCH)
            .clip(CircleShape)
            .background(fill)
            .border(1.dp, hexColor(Script.SWATCH_EDGE), CircleShape),
    )
}

private fun hexColor(hex: String): Color {
    val rgb = hex.removePrefix("#").toLongOrNull(radix = HEX_RADIX) ?: return Color.Transparent
    return Color(rgb or OPAQUE)
}

@Suppress("LongMethod") // One revision's twelve fields.
@Composable
private fun RevisionForm(revision: Rec?, onClose: () -> Unit, onSaved: () -> Unit) {
    val ctx = LocalSs.current
    val sceneState = scenes()
    var number by remember(revision) { mutableStateOf(revision?.str("revision_number").orEmpty()) }
    var colour by remember(revision) {
        mutableStateOf(revision?.str("revision_color").orEmpty().ifBlank { Script.REVISION_COLORS.first() })
    }
    var date by remember(revision) { mutableStateOf(revision?.str("revision_date").orEmpty()) }
    var issuedBy by remember(revision) { mutableStateOf(revision?.str("issued_by").orEmpty()) }
    var summary by remember(revision) { mutableStateOf(revision?.str("summary").orEmpty()) }
    var pagesRevised by remember(revision) { mutableStateOf(revision?.strings("pages_revised").orEmpty()) }
    var scenesAffected by remember(revision) { mutableStateOf(revision?.strings("scenes_affected").orEmpty()) }
    var pagesAdded by remember(revision) {
        mutableStateOf(revision?.int("pages_added")?.takeIf { it > 0 }?.toString().orEmpty())
    }
    var pagesRemoved by remember(revision) {
        mutableStateOf(revision?.int("pages_removed")?.takeIf { it > 0 }?.toString().orEmpty())
    }
    var totalAfter by remember(revision) {
        mutableStateOf(revision?.int("total_pages_after")?.takeIf { it > 0 }?.toString().orEmpty())
    }
    var distributedTo by remember(revision) { mutableStateOf(revision?.strings("distributed_to").orEmpty()) }
    var notes by remember(revision) { mutableStateOf(revision?.str("notes").orEmpty()) }
    var saving by remember { mutableStateOf(false) }

    FormDialog(
        open = true,
        title = t(if (revision == null) "add_revision" else "edit_revision"),
        onDismiss = onClose,
        confirmLabel = t("save"),
        onConfirm = {
            saving = true
            ctx.scope.launch {
                val payload = body(
                    "revision_number" to number.trim(),
                    "revision_color" to colour,
                    "revision_date" to date.trim(),
                    "issued_by" to issuedBy.trim(),
                    "summary" to summary,
                    "pages_revised" to pagesRevised,
                    "scenes_affected" to scenesAffected,
                    // 0 is what the service stores for "nobody entered one".
                    "pages_added" to (pagesAdded.trim().toIntOrNull() ?: 0),
                    "pages_removed" to (pagesRemoved.trim().toIntOrNull() ?: 0),
                    "total_pages_after" to (totalAfter.trim().toIntOrNull() ?: 0),
                    "distributed_to" to distributedTo,
                    "notes" to notes,
                )
                val done = ctx.write {
                    if (revision == null) {
                        crudOf(ctx).create(payload.dropEmpty())
                    } else {
                        crudOf(ctx).update(revision.id, payload)
                    }
                }
                saving = false
                if (done != null) {
                    onClose()
                    onSaved()
                }
            }
        },
        confirmEnabled = number.isNotBlank() && date.isNotBlank(),
        busy = saving,
        width = FORM_WIDTH,
    ) {
        FormGrid {
            TextInput(number, { number = it }, t("revision_no"), FormHalf)
            PickInput(
                value = colour,
                options = Script.REVISION_COLORS.map { it to t("revision_color_$it") },
                onChange = { colour = it },
                label = t("revision_color"),
            )
            // The service keeps the date as text, as the story date is kept.
            TextInput(date, { date = it }, t("date"), placeholder = DATE_HINT)
            TextInput(issuedBy, { issuedBy = it }, t("issued_by"))
            TextInput(summary, { summary = it }, t("summary"), FormWide, multiline = true)
            TagInput(
                tokens = pagesRevised,
                onChange = { pagesRevised = it },
                label = t("pages_revised"),
                modifier = FormWide,
            )
            MultiPickInput(
                values = scenesAffected,
                options = sceneState.options,
                onChange = { scenesAffected = it },
                label = t("scenes_affected"),
                modifier = FormWide,
            )
            TextInput(pagesAdded, { pagesAdded = it }, t("pages_added"), FormHalf, number = true)
            TextInput(pagesRemoved, { pagesRemoved = it }, t("pages_removed"), FormHalf, number = true)
            TextInput(totalAfter, { totalAfter = it }, t("total_pages_after"), FormHalf, number = true)
            TagInput(
                tokens = distributedTo,
                onChange = { distributedTo = it },
                label = t("distributed_to"),
                modifier = FormWide,
            )
            TextInput(notes, { notes = it }, t("notes"), FormWide, multiline = true)
        }
    }
}

private fun crudOf(ctx: SsCtx) = ContinuityCrud(ctx.api, "script-revisions")

private const val DATE_HINT = "Oct 5, 2026"
private const val HEX_RADIX = 16
private const val OPAQUE = 0xFF000000L

private val SWATCH = 20.dp
private val FORM_WIDTH = 720.dp
