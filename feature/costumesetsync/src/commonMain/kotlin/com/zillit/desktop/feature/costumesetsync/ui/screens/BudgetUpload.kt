package com.zillit.desktop.feature.costumesetsync.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import com.zillit.desktop.feature.costumesetsync.domain.BudgetModel
import com.zillit.desktop.feature.costumesetsync.domain.Rec
import com.zillit.desktop.feature.costumesetsync.domain.fmtDate
import com.zillit.desktop.feature.costumesetsync.ui.FilterSelect
import com.zillit.desktop.feature.costumesetsync.ui.FormDialog
import com.zillit.desktop.feature.costumesetsync.ui.LocalSync
import com.zillit.desktop.feature.costumesetsync.ui.MutedText
import com.zillit.desktop.feature.costumesetsync.ui.body
import com.zillit.desktop.feature.costumesetsync.ui.enumOptions
import com.zillit.desktop.feature.costumesetsync.ui.t
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray

private val SHEET_EXTENSIONS = setOf("xlsx", "xlsm", "csv", "pdf")
private val DIALOG_WIDTH = 1100.dp
private val DEPARTMENT_WIDTH = 280.dp
private const val DESC_WEIGHT = 2.2f

/** One line of an uploaded sheet with the user's tick and the two fields they may correct before import. */
private data class SheetRow(val rec: Rec, val pick: Boolean, val description: String, val category: String, val noteKey: String?)

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
 * for the preview); the import itself is JSON `{ lines }`. The sheet-template download is not ported
 * (a raw file the desktop api client does not fetch).
 */
@Composable
internal fun BudgetUpload(open: Boolean, onClose: () -> Unit, onImported: () -> Unit, currency: String) {
    val ctx = LocalSync.current
    val scope = rememberCoroutineScope()
    var preview by remember(open) { mutableStateOf<Rec?>(null) }
    var rows by remember(open) { mutableStateOf(listOf<SheetRow>()) }
    var dept by remember(open) { mutableStateOf("") }
    var reading by remember(open) { mutableStateOf(false) }
    var saving by remember(open) { mutableStateOf(false) }
    val cats = ctx.metaList("expense_categories").ifEmpty { BudgetModel.EXPENSE_CATEGORY_FALLBACK }

    fun read() {
        scope.launch {
            val file = ctx.host.pick(SHEET_EXTENSIONS, false).firstOrNull() ?: return@launch
            reading = true
            when (val res = ctx.api.upload("/expenses/import/preview", file.name, file.bytes, file.mime)) {
                is ZillitResult.Failure -> ctx.toast(res.error.localised(), false)
                is ZillitResult.Success -> {
                    val d = res.data.rec ?: Rec.Empty
                    preview = d
                    rows = d.recs("lines").map { l -> SheetRow(l, noteKeyOf(l) == null, l.str("description"), l.str("category"), noteKeyOf(l)) }
                    // Costume teams mostly want their own department out of a whole-production budget.
                    dept = d.recs("lines").firstOrNull { Regex("wardrobe|costume", RegexOption.IGNORE_CASE).containsMatchIn(it.str("department")) }?.str("department").orEmpty()
                }
            }
            reading = false
        }
    }

    val inDept = { r: SheetRow -> dept.isEmpty() || r.rec.str("department") == dept }
    val picked = rows.filter { it.pick && inDept(it) }
    val bad = picked.firstOrNull { !it.rec.has("amount") || it.rec.double("amount") < 0 || it.description.isBlank() }
    val total = picked.sumOf { it.rec.double("amount") }
    val importCurrency = picked.firstOrNull { it.rec.str("currency").isNotEmpty() }?.rec?.str("currency") ?: currency
    val label = if (saving) t("csync_importing") else kitCount("csync_budget_import_n", picked.size).replace("{total}", BudgetModel.fmtAmount(total, importCurrency))

    FormDialog(
        open = open,
        title = t("csync_upload_budget_sheet"),
        onDismiss = onClose,
        confirmLabel = label,
        onConfirm = {
            saving = true
            scope.launch {
                val lines = picked.map { BudgetModel.toImportLine(it.rec, BudgetModel.ImportEdit(it.description, it.category), currency) }
                val done = ctx.write { ctx.api.post("/expenses/import", body("lines" to JsonArray(lines))) }
                saving = false
                if (done != null) {
                    onImported()
                    onClose()
                }
            }
        },
        confirmEnabled = preview != null && picked.isNotEmpty() && bad == null,
        busy = saving,
        width = DIALOG_WIDTH,
    ) {
        val current = preview
        if (current == null) {
            ZillitText(t("csync_budget_upload_formats"), style = ZillitTheme.typography.titleSmall)
            MutedText(t("csync_budget_upload_help"), maxLines = 3)
            ZillitButton(if (reading) t("csync_reading") else t("csync_choose_file"), onClick = ::read, enabled = !reading, loading = reading)
        } else {
            PreviewBody(current, rows, dept, cats, inDept, importCurrency, reading, onDept = { dept = it }, onRows = { rows = it }, onAnother = ::read)
        }
    }
}

@Composable
private fun PreviewBody(
    preview: Rec,
    rows: List<SheetRow>,
    dept: String,
    cats: List<String>,
    inDept: (SheetRow) -> Boolean,
    importCurrency: String,
    reading: Boolean,
    onDept: (String) -> Unit,
    onRows: (List<SheetRow>) -> Unit,
    onAnother: () -> Unit,
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
        if (skipped.isNotEmpty() && sheets.size > 1) append(" · ").append(t("csync_budget_skipped_sheets", "names" to skipped.joinToString(", ")))
        append(". ").append(t("csync_budget_untick_hint"))
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md), verticalAlignment = Alignment.CenterVertically) {
        MutedText(head, Modifier.weight(1f), maxLines = 3)
        ZillitButton(t("csync_upload_choose_another"), onClick = onAnother, variant = ButtonVariant.Secondary, size = ButtonSize.Small, enabled = !reading)
    }
    if (isPdf && departments.size > 1) {
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md), verticalAlignment = Alignment.CenterVertically) {
            ZillitText(t("csync_budget_department"), style = ZillitTheme.typography.titleSmall)
            FilterSelect(dept, departments.map { it to it }, t("csync_budget_all_departments", "n" to rows.size), onDept, Modifier.width(DEPARTMENT_WIDTH))
            MutedText(kitCount("csync_budget_n_plain_lines", shown.size) + " · " + BudgetModel.fmtAmount(shown.sumOf { it.value.rec.double("amount") }, importCurrency))
        }
    }
    val allOn = shown.isNotEmpty() && shown.all { it.value.pick }
    Row(Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.xs), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
        ZillitCheckbox(allOn, { on -> onRows(rows.map { if (inDept(it)) it.copy(pick = on) else it }) }, label = t("csync_tick_all"))
    }
    shown.forEach { (i, r) -> SheetLine(r, isPdf, sheets.size > 1, cats, importCurrency) { next -> onRows(rows.mapIndexed { j, x -> if (j == i) next else x }) } }
}

@Composable
private fun SheetLine(r: SheetRow, isPdf: Boolean, multiSheet: Boolean, cats: List<String>, importCurrency: String, onChange: (SheetRow) -> Unit) {
    val l = r.rec
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm), verticalAlignment = Alignment.CenterVertically) {
        ZillitCheckbox(r.pick, { onChange(r.copy(pick = it)) })
        Column(Modifier.width(ROW_COL)) {
            MutedText((if (!isPdf && multiSheet) "${l.str("sheet")} · " else "") + l.str("row"))
            r.noteKey?.let { MutedText(t(it), maxLines = 2) }
        }
        Column(Modifier.width(ACCOUNT_COL)) {
            ZillitText(l.str("account_code").ifBlank { "—" }, style = ZillitTheme.typography.bodySmall, maxLines = 1)
            MutedText(l.str("account_name"))
        }
        if (isPdf) MutedText(l.str("payee").ifBlank { "—" }, Modifier.width(ACCOUNT_COL))
        ZillitTextField(r.description, { onChange(r.copy(description = it)) }, Modifier.weight(DESC_WEIGHT), placeholder = t("csync_field_description"))
        FilterSelect(r.category, enumOptions(cats), "—", { onChange(r.copy(category = it.ifEmpty { r.category })) }, Modifier.width(CATEGORY_COL))
        ZillitText(
            if (l.has("amount")) BudgetModel.fmtAmount(l.double("amount"), l.str("currency").ifEmpty { importCurrency }) else "—",
            Modifier.width(AMOUNT_COL),
            style = ZillitTheme.typography.titleSmall,
            maxLines = 1,
        )
        MutedText(if (l.long("date") != 0L) fmtDate(l.long("date")) else t("csync_when_today"), Modifier.width(DATE_COL))
        Box(Modifier.weight(1f)) { Matched(l.str("scene"), l.str("scene_id"), l.str("character"), l.str("character_id"), l.str("vendor"), l.str("vendor_id")) }
    }
}

/** A name the sheet gave that this production does not have is shown, but will not be linked. */
@Composable
private fun Matched(scene: String, sceneId: String, character: String, characterId: String, vendor: String, vendorId: String) {
    fun part(text: String, id: String) = if (text.isBlank()) null else if (id.isNotEmpty()) text else "$text · ${t("csync_budget_not_found")}"
    MutedText(listOfNotNull(part(scene, sceneId), part(character, characterId), part(vendor, vendorId)).joinToString(" | ").ifEmpty { "—" }, maxLines = 2)
}

private val ROW_COL = 80.dp
private val ACCOUNT_COL = 110.dp
private val CATEGORY_COL = 150.dp
private val AMOUNT_COL = 100.dp
private val DATE_COL = 90.dp
