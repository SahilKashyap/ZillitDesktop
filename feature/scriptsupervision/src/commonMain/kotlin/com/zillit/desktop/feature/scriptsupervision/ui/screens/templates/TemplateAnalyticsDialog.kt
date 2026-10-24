package com.zillit.desktop.feature.scriptsupervision.ui.screens.templates

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ColumnWidth
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.TableColumn
import com.zillit.desktop.core.designsystem.component.ZillitDataTable
import com.zillit.desktop.core.designsystem.component.ZillitMeter
import com.zillit.desktop.core.designsystem.component.textColumn
import com.zillit.desktop.feature.scriptsupervision.domain.Rec
import com.zillit.desktop.feature.scriptsupervision.ui.Await
import com.zillit.desktop.feature.scriptsupervision.ui.FormDialog
import com.zillit.desktop.feature.scriptsupervision.ui.MutedText
import com.zillit.desktop.feature.scriptsupervision.ui.rememberResource
import com.zillit.desktop.feature.scriptsupervision.ui.t

/**
 * How often each of a template's fields is actually filled in — the web's
 * `TemplateAnalyticsModal`.
 *
 * It answers the question a template designer cannot answer by looking: a
 * field nobody ever fills is a field to drop, and one filled every time
 * might deserve to be required.
 *
 * ⚠ The service answers `{ totalLogs, fields: { "<section>_<field>": … } }` —
 * a MAP keyed by a joined string, not a list. The key is unpacked for its
 * section only through the row's own `section` value, never by splitting the
 * key: a field whose own key contains an underscore (most of them) would
 * split in the wrong place.
 */
@Composable
internal fun TemplateAnalyticsDialog(template: Rec, onClose: () -> Unit) {
    val analytics = rememberResource(template.id) {
        api.get("/quick-log/template-analytics/${api.project().orEmpty()}/${template.id}")
    }
    FormDialog(
        open = true,
        title = t("template_analytics"),
        onDismiss = onClose,
        // Nothing to confirm: the web's footer is empty, and the module's
        // dialog always has one button, so it is the way out.
        confirmLabel = t("close"),
        onConfirm = onClose,
        width = DIALOG_WIDTH,
    ) {
        Await(analytics) { answer ->
            val record = answer.rec
            val total = record?.int("totalLogs") ?: 0
            MutedText(t("based_on_logs", "count" to total))
            if (total == 0) {
                MutedText(t("no_usage_yet"))
                return@Await
            }
            val rows = record?.rec("fields")?.json.orEmpty().keys.mapNotNull { key ->
                record?.rec("fields")?.rec(key)?.let { key to it }
            }
            ZillitDataTable(
                rows = rows,
                columns = analyticsColumns(),
                key = { it.first },
                emptyTitle = t("no_usage_yet"),
                virtualised = false,
            )
        }
    }
}

@Composable
private fun analyticsColumns(): List<TableColumn<Pair<String, Rec>>> = listOf(
    TableColumn(t("field")) { (_, info) ->
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
            MutedText(info.str("label"))
            MutedText(info.str("section"))
        }
    },
    TableColumn(t("fill_rate"), ColumnWidth.Fixed(RATE_COL)) { (_, info) ->
        val percent = info.int("percentage")
        ZillitMeter(
            fraction = percent / FULL,
            // Mostly filled, sometimes, rarely — the web's own three bands.
            tone = when {
                percent > MOSTLY -> StatusTone.Ready
                percent > RARELY -> StatusTone.Progress
                else -> StatusTone.Rejected
            },
            modifier = Modifier.fillMaxWidth(),
        )
    },
    textColumn(t("filled"), ColumnWidth.Fixed(FILLED_COL)) { (_, info) ->
        "${info.int("filled")}/${info.int("total")}"
    },
)

private val DIALOG_WIDTH = 640.dp
private val RATE_COL = 200.dp
private val FILLED_COL = 96.dp
private const val FULL = 100f
private const val MOSTLY = 70
private const val RARELY = 30
