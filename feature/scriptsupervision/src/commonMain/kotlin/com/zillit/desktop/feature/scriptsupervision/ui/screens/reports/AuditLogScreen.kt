package com.zillit.desktop.feature.scriptsupervision.ui.screens.reports

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
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
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ColumnWidth
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.TableColumn
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDataTable
import com.zillit.desktop.core.designsystem.component.ZillitSearchSelect
import com.zillit.desktop.core.designsystem.component.textColumn
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.scriptsupervision.domain.Rec
import com.zillit.desktop.feature.scriptsupervision.domain.Reports
import com.zillit.desktop.feature.scriptsupervision.domain.asRec
import com.zillit.desktop.feature.scriptsupervision.ui.Await
import com.zillit.desktop.feature.scriptsupervision.ui.FormDialog
import com.zillit.desktop.feature.scriptsupervision.ui.LocalSs
import com.zillit.desktop.feature.scriptsupervision.ui.MutedText
import com.zillit.desktop.feature.scriptsupervision.ui.SsPageHeader
import com.zillit.desktop.feature.scriptsupervision.ui.SsPager
import com.zillit.desktop.feature.scriptsupervision.ui.SsTag
import com.zillit.desktop.feature.scriptsupervision.ui.rememberResource
import com.zillit.desktop.feature.scriptsupervision.ui.t
import kotlinx.coroutines.launch

/**
 * The audit trail — the web's `AuditLogScreen`.
 *
 * Every change the service recorded, newest first, filtered by record kind
 * and by what was done. `GET /audit/{projectId}` is the one paged resource
 * here besides scenes, and it answers `{ logs, total }`, so the pager needs
 * the total that rides beside the rows.
 *
 * ⚠ Scenes and takes are NOT audited — they are absent from
 * [Reports.AUDIT_MODULES] — so looking for a scene's history here and finding
 * nothing is the service working as designed, not a broken trail.
 */
@Suppress("LongMethod") // Header, two filters, the table, the pager and the restore dialog.
@Composable
internal fun AuditLogScreen() {
    val ctx = LocalSs.current
    val project = ctx.api.project().orEmpty()
    var module by remember { mutableStateOf("") }
    var action by remember { mutableStateOf("") }
    var page by remember { mutableStateOf(0) }
    var restoring by remember { mutableStateOf<Rec?>(null) }
    // A narrower filter can leave the reader on a page that no longer exists.
    LaunchedEffect(module, action) { page = 0 }

    val rows = rememberResource(project, module, action, page) {
        api.get(
            "/audit/$project",
            mapOf(
                "limit" to PAGE_SIZE,
                // An OFFSET, not a page number: the only paged resource here
                // that counts in rows rather than pages.
                "offset" to page * PAGE_SIZE,
                "module" to module,
                "action" to action,
            ),
        ).let { answer ->
            when (answer) {
                is ZillitResult.Failure -> answer
                is ZillitResult.Success -> {
                    val record = answer.data.data.asRec()
                    ZillitResult.Success(
                        record?.recs("logs").orEmpty() to (record?.long("total") ?: 0L),
                    )
                }
            }
        }
    }

    SsPageHeader(title = t("audit_log"))
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val modules = Reports.AUDIT_MODULES.map { it to t("module_$it") }
        ZillitSearchSelect(
            value = modules.firstOrNull { it.first == module },
            options = modules,
            onSelect = { module = it.first },
            label = { it.second },
            placeholder = t("all_record_types"),
            onClear = { module = "" }.takeIf { module.isNotBlank() },
            modifier = Modifier.width(FILTER_WIDTH),
        )
        val actions = Reports.AUDIT_ACTIONS.map { it to t("action_$it") }
        ZillitSearchSelect(
            value = actions.firstOrNull { it.first == action },
            options = actions,
            onSelect = { action = it.first },
            label = { it.second },
            placeholder = t("all_actions"),
            onClear = { action = "" }.takeIf { action.isNotBlank() },
            modifier = Modifier.width(NARROW_FILTER),
        )
    }
    Await(rows) { (logs, total) ->
        ZillitDataTable(
            rows = logs,
            columns = auditColumns { restoring = it },
            key = { it.id },
            emptyTitle = t("no_activity"),
            virtualised = false,
        )
        SsPager(page = page, pageSize = PAGE_SIZE, total = total.toInt(), onPage = { page = it })
    }

    restoring?.let { entry ->
        RestoreDialog(
            entry = entry,
            onClose = { restoring = null },
            onRestored = { rows.reload(silent = true) },
        )
    }
}

@Composable
private fun auditColumns(onRestore: (Rec) -> Unit): List<TableColumn<Rec>> = listOf(
    textColumn(t("time"), ColumnWidth.Fixed(TIME_COL)) { it.str("timestamp") },
    TableColumn(t("action"), ColumnWidth.Fixed(ACTION_COL)) { entry ->
        val action = entry.str("action")
        SsTag(t("action_$action"), actionTone(action))
    },
    textColumn(t("record_type"), ColumnWidth.Fixed(MODULE_COL)) { t("module_${it.str("module")}") },
    // The id's tail, as the web shows it: enough to match two rows about
    // the same record, without a column full of ObjectIds.
    textColumn(t("record"), ColumnWidth.Fixed(RECORD_COL)) { it.str("record_id").takeLast(RECORD_TAIL) },
    textColumn(t("user")) { it.str("user_name") },
    textColumn(t("description")) { it.str("description") },
    TableColumn(header = "", width = ColumnWidth.Fixed(RESTORE_COL)) { entry ->
        // Offered only where the service can actually do it: it needs the
        // record's previous state AND a model for that kind.
        if (Reports.canRestore(entry)) {
            ZillitButton(
                text = t("restore"),
                onClick = { onRestore(entry) },
                variant = ButtonVariant.Secondary,
                leadingIcon = ZillitIcons.Restore,
            )
        }
    },
)

/** How a change reads: made, changed, gone, or put back. */
private fun actionTone(action: String): StatusTone = when (action) {
    "create" -> StatusTone.Done
    "update" -> StatusTone.Progress
    "delete" -> StatusTone.Escalated
    else -> StatusTone.Neutral
}

/**
 * Putting a record back.
 *
 * Confirmed rather than done on the press, because it writes over whatever is
 * there now: restoring a record that somebody has since edited discards their
 * edit. The fields it will change are listed, which is the only way to see
 * that before agreeing to it.
 */
@Composable
private fun RestoreDialog(entry: Rec, onClose: () -> Unit, onRestored: () -> Unit) {
    val ctx = LocalSs.current
    var busy by remember { mutableStateOf(false) }
    val changes = Reports.changes(entry)
    FormDialog(
        open = true,
        title = t("restore_previous"),
        onDismiss = onClose,
        confirmLabel = t("restore"),
        onConfirm = {
            busy = true
            ctx.scope.launch {
                val done = ctx.write {
                    ctx.api.post("/audit/restore/${ctx.api.project().orEmpty()}/${entry.id}")
                }
                busy = false
                if (done != null) {
                    onClose()
                    onRestored()
                }
            }
        },
        busy = busy,
        width = DIALOG_WIDTH,
    ) {
        MutedText(
            listOf(t("module_${entry.str("module")}"), entry.str("description"))
                .filter { it.isNotBlank() }
                .joinToString(" · "),
        )
        if (changes.isEmpty()) {
            MutedText(t("no_activity"))
        } else {
            changes.forEach { change ->
                MutedText("${change.field}: ${change.from} → ${change.to}")
            }
        }
    }
}

private const val PAGE_SIZE = 50
private val FILTER_WIDTH = 220.dp
private val NARROW_FILTER = 160.dp
private val TIME_COL = 170.dp
private val ACTION_COL = 110.dp
private val MODULE_COL = 170.dp
private val RECORD_COL = 110.dp
private const val RECORD_TAIL = 8
private val RESTORE_COL = 120.dp
private val DIALOG_WIDTH = 560.dp
