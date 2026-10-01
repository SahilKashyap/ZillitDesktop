package com.zillit.desktop.feature.costumesetsync.ui.screens

import com.zillit.desktop.feature.costumesetsync.ui.SyncCtx
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.costumesetsync.ui.SyncDialogShell
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.costumesetsync.domain.BudgetModel
import com.zillit.desktop.feature.costumesetsync.domain.Rec
import com.zillit.desktop.feature.costumesetsync.domain.fmtDate
import com.zillit.desktop.feature.costumesetsync.ui.FilterSelect
import com.zillit.desktop.feature.costumesetsync.ui.LocalSync
import com.zillit.desktop.feature.costumesetsync.ui.MutedText
import com.zillit.desktop.feature.costumesetsync.ui.body
import com.zillit.desktop.feature.costumesetsync.ui.enumOptions
import com.zillit.desktop.feature.costumesetsync.ui.t
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray

private val SHEET_EXTENSIONS = setOf("xlsx", "xlsm", "csv", "pdf")
private val DIALOG_WIDTH = 1100.dp
private val DEPARTMENT_WIDTH = 260.dp
private const val DESC_WEIGHT = 2.2f

/** One line of an uploaded sheet with the user's tick and the two fields they may correct before import. */
private data class SheetRow(
    val rec: Rec,
    val pick: Boolean,
    val description: String,
    val category: String,
    val noteKey: String?,
)

/** Why a line starts unticked; it can still be ticked once fixed. */
private fun noteKeyOf(l: Rec): String? = when {
    l.bool("is_total") -> "csync_budget_note_total"
    !l.has("amount") -> "csync_budget_note_no_amount"
    l.double("amount") < 0 -> "csync_budget_note_negative"
    else -> null
}

/**
 * Upload a whole budget sheet (.xlsx, .csv or a budget PDF): every line is previewed with a tick box,
 * matched to this production's scenes, characters and vendors by name, and only the ticked lines are
 * written — nothing is saved until Import. The file is sent to `/expenses/import/preview` (multipart, only
 * for the preview); the import itself is JSON `{ lines }`. Download template saves the blank sheet
 * (`GET /expenses/import/template`, an xlsx) through the host's raw GET and save dialog.
 */
@Composable
internal fun BudgetUpload(open: Boolean, onClose: () -> Unit, onImported: () -> Unit, currency: String) {
    val ctx = LocalSync.current
    val scope = rememberCoroutineScope()
    var preview by remember(open) { mutableStateOf<Rec?>(null) }
    var rows by remember(open) { mutableStateOf(listOf<SheetRow>()) }
    var dept by remember(open) { mutableStateOf("") }
    var reading by remember(open) { mutableStateOf(false) }
    var templating by remember(open) { mutableStateOf(false) }
    var saving by remember(open) { mutableStateOf(false) }
    val cats = ctx.metaList("expense_categories").ifEmpty { BudgetModel.EXPENSE_CATEGORY_FALLBACK }

    fun read() {
        scope.launch {
            uploadPreview(ctx, { reading = it }) { d ->
                preview = d
                rows = sheetRowsOf(d)
                dept = wardrobeDept(d)
            }
        }
    }

    val inDept = { r: SheetRow -> dept.isEmpty() || r.rec.str("department") == dept }
    val picked = rows.filter { it.pick && inDept(it) }
    val bad = picked.firstOrNull { !it.rec.has("amount") || it.rec.double("amount") < 0 || it.description.isBlank() }
    val total = picked.sumOf { it.rec.double("amount") }
    val importCurrency = picked.firstOrNull { it.rec.str("currency").isNotEmpty() }?.rec?.str("currency") ?: currency
    val label = importLabel(saving, picked.size, total, importCurrency)

    val ready = preview != null && picked.isNotEmpty() && bad == null && !saving
    val importNow: () -> Unit = {
        saving = true
        scope.launch {
            val done = importLines(ctx, picked, currency)
            saving = false
            if (done) {
                onImported()
                onClose()
            }
        }
    }
    SyncDialogShell(
        title = t("csync_upload_budget_sheet"),
        visible = open,
        onDismiss = onClose,
        width = DIALOG_WIDTH,
        actions = {
            ImportActions(onClose, preview != null, reading, label, ready, saving, ::read, importNow)
        },
    ) {
        val current = preview
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
            if (current == null) {
                UploadLanding(reading, templating, ::read) {
                    scope.launch { downloadTemplate(ctx) { templating = it } }
                }
            } else {
                PreviewBody(
                    current,
                    rows,
                    dept,
                    cats,
                    inDept,
                    importCurrency,
                    onDept = { dept = it },
                    onRows = { rows = it },
                )
            }
        }
    }
}

/** Picks a sheet, sends it for a preview and hands the answer over; a refusal is toasted in the server's words. */
private suspend fun uploadPreview(ctx: SyncCtx, onReading: (Boolean) -> Unit, onPreview: (Rec) -> Unit) {
    val file = ctx.host.pick(SHEET_EXTENSIONS, false).firstOrNull() ?: return
    onReading(true)
    when (val res = ctx.api.upload("/expenses/import/preview", file.name, file.bytes, file.mime)) {
        is ZillitResult.Failure -> ctx.toast(res.error.localised(), false)
        is ZillitResult.Success -> onPreview(res.data.rec ?: Rec.Empty)
    }
    onReading(false)
}

private fun sheetRowsOf(d: Rec): List<SheetRow> = d.recs("lines")
    .map { l -> SheetRow(l, noteKeyOf(l) == null, l.str("description"), l.str("category"), noteKeyOf(l)) }

/** Costume teams mostly want their own department out of a whole-production budget. */
private fun wardrobeDept(d: Rec): String = d.recs("lines")
    .firstOrNull { Regex("wardrobe|costume", RegexOption.IGNORE_CASE).containsMatchIn(it.str("department")) }
    ?.str("department")
    .orEmpty()

/** POSTs the ticked lines; true when the server took them. */
private suspend fun importLines(ctx: SyncCtx, picked: List<SheetRow>, currency: String): Boolean {
    val lines = picked.map {
        BudgetModel.toImportLine(it.rec, BudgetModel.ImportEdit(it.description, it.category), currency)
    }
    return ctx.write { ctx.api.post("/expenses/import", body("lines" to JsonArray(lines))) } != null
}

@Composable
private fun ImportActions(
    onClose: () -> Unit,
    hasPreview: Boolean,
    reading: Boolean,
    label: String,
    ready: Boolean,
    saving: Boolean,
    onRead: () -> Unit,
    onImport: () -> Unit,
) {
    ZillitButton(t("csync_cancel"), onClick = onClose, variant = ButtonVariant.Secondary)
    if (hasPreview) {
        ZillitButton(
            t("csync_upload_choose_another"),
            onClick = onRead,
            variant = ButtonVariant.Secondary,
            enabled = !reading,
        )
        ZillitButton(
            label,
            onClick = onImport,
            variant = if (ready) ButtonVariant.Primary else ButtonVariant.Secondary,
            enabled = ready,
            loading = saving,
        )
    }
}

/** `.csync-budget-upload`: centred, 10 apart, 24 of air; the sheet icon over the formats line. */
@Composable
private fun UploadLanding(reading: Boolean, templating: Boolean, onRead: () -> Unit, onTemplate: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 24.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ZillitIcon(ZillitIcons.Upload, tint = ZillitTheme.colors.textMuted, size = 36.dp)
        ZillitText(
            t("csync_budget_upload_formats"),
            style = ZillitTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        )
        ZillitText(
            t("csync_budget_upload_help"),
            Modifier.widthIn(max = 560.dp),
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textMuted,
            textAlign = TextAlign.Center,
        )
        // `.csync-inline-pair`: Download template beside Choose file.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            ZillitButton(
                t("csync_budget_download_template"),
                onClick = onTemplate,
                variant = ButtonVariant.Secondary,
                enabled = !templating,
                loading = templating,
                leadingIcon = ZillitIcons.Download,
            )
            ZillitButton(
                if (reading) t("csync_reading") else t("csync_choose_file"),
                onClick = onRead,
                enabled = !reading,
                loading = reading,
            )
        }
    }
}

/** The blank import sheet, saved where the user says; a refusal is toasted in the server's words. */
private suspend fun downloadTemplate(ctx: SyncCtx, onBusy: (Boolean) -> Unit) {
    onBusy(true)
    fetchTemplate(ctx)
    onBusy(false)
}

private suspend fun fetchTemplate(ctx: SyncCtx) {
    val url = ctx.api.url("/expenses/import/template") ?: return ctx.toast(str(S.something_went_wrong), false)
    when (val res = ctx.host.downloadBytes(url)) {
        is ZillitResult.Failure -> ctx.toast(res.error.localised(), false)
        is ZillitResult.Success -> ctx.host.save(TEMPLATE_FILE, res.data)
    }
}

private const val TEMPLATE_FILE = "Budget sheet template.xlsx"


@Composable
private fun PreviewBody(
    preview: Rec,
    rows: List<SheetRow>,
    dept: String,
    cats: List<String>,
    inDept: (SheetRow) -> Boolean,
    importCurrency: String,
    onDept: (String) -> Unit,
    onRows: (List<SheetRow>) -> Unit,
) {
    val isPdf = preview.str("file_name").lowercase().endsWith(".pdf")
    val departments = rows.map { it.rec.str("department") }.filter { it.isNotEmpty() }.distinct()
    val sheets = preview.strings("sheets")
    val skipped = preview.strings("skipped_sheets")
    val shown = rows.withIndex().filter { inDept(it.value) }
    val head = buildString {
        append(preview.str("file_name")).append(" · ").append(kitCount("csync_budget_lines_found", rows.size))
        if (isPdf) append(" ").append(kitCount("csync_budget_across_departments", departments.size))
        else if (sheets.size > 1) append(" ").append(kitCount("csync_budget_in_sheets", sheets.size - skipped.size))
        if (skipped.isNotEmpty() && sheets.size > 1) append(" · ").append(
            t("csync_budget_skipped_sheets", "names" to skipped.joinToString(", ")),
        )
        append(". ").append(t("csync_budget_untick_hint"))
    }
    if (isPdf && departments.size > 1) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            ZillitText(
                t("csync_budget_department"),
                style = ZillitTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            )
            FilterSelect(
                dept,
                departments.map { it to it },
                t("csync_budget_all_departments", "n" to rows.size),
                onDept,
                Modifier.width(DEPARTMENT_WIDTH),
            )
            MutedText(
                kitCount("csync_budget_n_plain_lines", shown.size) + " · " + BudgetModel.fmtAmount(
                    shown.sumOf { it.value.rec.double("amount") },
                    importCurrency,
                ),
            )
        }
    }
    MutedText(head, maxLines = 4)
    val allOn = shown.isNotEmpty() && shown.all { it.value.pick }
    // `.csync-table` inside `.csync-budget-preview`: pinned header, the rows scroll in their own 55vh box.
    Column(
        Modifier.fillMaxWidth().border(1.dp, ZillitTheme.colors.border, RoundedCornerShape(8.dp)).clip(
            RoundedCornerShape(8.dp),
        ),
    ) {
        SheetHead(isPdf, allOn) { on -> onRows(rows.map { if (inDept(it)) it.copy(pick = on) else it }) }
        Column(Modifier.fillMaxWidth().heightIn(max = PREVIEW_MAX).verticalScroll(rememberScrollState())) {
            shown.forEach { (i, r) ->
                SheetLine(r, isPdf, sheets.size > 1, cats, importCurrency) { next ->
                    onRows(rows.mapIndexed { j, x -> if (j == i) next else x })
                }
            }
        }
    }
}

@Composable
private fun SheetHead(isPdf: Boolean, allOn: Boolean, onAll: (Boolean) -> Unit) {
    val head = ZillitTheme.typography.labelSmall.copy(
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.04.em,
    )
    Row(
        Modifier.fillMaxWidth().background(ZillitTheme.colors.surfaceSunken).padding(
            horizontal = 10.dp,
            vertical = 8.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(CHECK_COL)) { ZillitCheckbox(allOn, onAll) }
        @Composable
        fun th(text: String, modifier: Modifier) = ZillitText(
            text.uppercase(),
            modifier,
            style = head,
            color = ZillitTheme.colors.textMuted,
            maxLines = 1,
        )
        th(t(if (isPdf) "csync_budget_page" else "csync_budget_row"), Modifier.width(ROW_COL))
        th(t("csync_budget_account"), Modifier.width(ACCOUNT_COL))
        if (isPdf) th(t("csync_field_name"), Modifier.width(ACCOUNT_COL))
        th(t("csync_field_description"), Modifier.weight(DESC_WEIGHT))
        th(t("csync_field_category"), Modifier.width(CATEGORY_COL))
        th(t("csync_budget_amount"), Modifier.width(AMOUNT_COL))
        th(t("csync_field_date"), Modifier.width(DATE_COL))
        th(t("csync_field_scene"), Modifier.weight(MATCH_WEIGHT))
        th(t("csync_field_character"), Modifier.weight(MATCH_WEIGHT))
        th(t("csync_field_vendor"), Modifier.weight(MATCH_WEIGHT))
    }
}

@Composable
private fun SheetLine(
    r: SheetRow,
    isPdf: Boolean,
    multiSheet: Boolean,
    cats: List<String>,
    importCurrency: String,
    onChange: (SheetRow) -> Unit,
) {
    val l = r.rec
    Row(
        Modifier.fillMaxWidth().alpha(if (r.pick) 1f else UNTICKED_ALPHA).padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(CHECK_COL)) { ZillitCheckbox(r.pick, { onChange(r.copy(pick = it)) }) }
        Column(Modifier.width(ROW_COL)) {
            MutedText((if (!isPdf && multiSheet) "${l.str("sheet")} · " else "") + l.str("row"))
            r.noteKey?.let {
                ZillitText(
                    t(it),
                    style = ZillitTheme.typography.bodySmall.copy(fontSize = 11.sp),
                    color = ZillitTheme.colors.warning,
                    maxLines = 2,
                )
            }
        }
        Column(Modifier.width(ACCOUNT_COL)) {
            ZillitText(
                l.str("account_code").ifBlank { "—" },
                style = ZillitTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                maxLines = 1,
            )
            MutedText(l.str("account_name"))
        }
        if (isPdf) ZillitText(
            l.str("payee").ifBlank { "—" },
            Modifier.width(ACCOUNT_COL),
            style = ZillitTheme.typography.bodySmall,
            maxLines = 1,
        )
        ZillitTextField(
            r.description,
            { onChange(r.copy(description = it)) },
            Modifier.weight(DESC_WEIGHT),
            placeholder = t("csync_field_description"),
        )
        FilterSelect(
            r.category,
            enumOptions(cats),
            "—",
            { onChange(r.copy(category = it.ifEmpty { r.category })) },
            Modifier.width(CATEGORY_COL),
        )
        SheetLineTail(l, importCurrency)
    }
}

/** The amount, the date and the three matched names, to the right of the editable cells. */
@Composable
private fun RowScope.SheetLineTail(l: Rec, importCurrency: String) {
    ZillitText(
        if (l.has("amount")) {
            BudgetModel.fmtAmount(l.double("amount"), l.str("currency").ifEmpty { importCurrency })
        } else {
            "—"
        },
        Modifier.width(AMOUNT_COL),
        style = ZillitTheme.typography.titleSmall.copy(textAlign = TextAlign.End),
        maxLines = 1,
    )
    MutedText(
        if (l.long("date") != 0L) fmtDate(l.long("date")) else t("csync_when_today"),
        Modifier.width(DATE_COL),
    )
    Box(Modifier.weight(MATCH_WEIGHT)) { Matched(l.str("scene"), l.str("scene_id")) }
    Box(Modifier.weight(MATCH_WEIGHT)) { Matched(l.str("character"), l.str("character_id")) }
    Box(Modifier.weight(MATCH_WEIGHT)) { Matched(l.str("vendor"), l.str("vendor_id")) }
}

/** A name the sheet gave that this production does not have is shown, but will not be linked. */
@Composable
private fun Matched(text: String, id: String) {
    when {
        text.isBlank() -> MutedText("—")
        id.isNotEmpty() -> ZillitText(text, style = ZillitTheme.typography.bodySmall, maxLines = 1)
        else -> MutedText("$text · ${t("csync_budget_not_found")}", maxLines = 2)
    }
}

private val CHECK_COL = 28.dp
private val ROW_COL = 70.dp
private val ACCOUNT_COL = 100.dp
private val CATEGORY_COL = 140.dp
private val AMOUNT_COL = 90.dp
private val DATE_COL = 80.dp
private val PREVIEW_MAX = 420.dp
private const val MATCH_WEIGHT = 1f
private const val UNTICKED_ALPHA = 0.55f

/** The Import button's text: the count and total of what is ticked, or "Importing" while it saves. */
private fun importLabel(saving: Boolean, count: Int, total: Double, currency: String): String =
    if (saving) {
        t("csync_importing")
    } else {
        kitCount("csync_budget_import_n", count).replace("{total}", BudgetModel.fmtAmount(total, currency))
    }
