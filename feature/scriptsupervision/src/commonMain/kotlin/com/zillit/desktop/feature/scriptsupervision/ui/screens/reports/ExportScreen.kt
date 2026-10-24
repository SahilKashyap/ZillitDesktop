package com.zillit.desktop.feature.scriptsupervision.ui.screens.reports

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
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
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitSearchSelect
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitSectionLabel
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.feature.scriptsupervision.domain.Reports
import com.zillit.desktop.feature.scriptsupervision.ui.LocalSs
import com.zillit.desktop.feature.scriptsupervision.ui.MutedText
import com.zillit.desktop.feature.scriptsupervision.ui.SsCtx
import com.zillit.desktop.feature.scriptsupervision.ui.SsPageHeader
import com.zillit.desktop.feature.scriptsupervision.ui.SsTag
import com.zillit.desktop.feature.scriptsupervision.ui.scenes
import com.zillit.desktop.feature.scriptsupervision.ui.t
import kotlinx.coroutines.launch

/**
 * The export centre — the web's `ExportScreen`.
 *
 * Three groups, as the web has them: the data exports the cutting room and
 * the production office ask for (CSV, and the editor's ALE), the department
 * exports for costume, hair and make-up, and the PDFs for one shooting day.
 *
 * ## Downloading rather than saving a Blob
 *
 * The web hands a Blob to the browser, which saves it silently. Here each one
 * goes through the host's save dialog, so a cancel is an ordinary outcome and
 * is not reported as a failure. The routes answer the FILE, not an envelope —
 * a JSON body on the wire means the service declined — and the fetch is
 * SIGNED, which the signed-photo fetch deliberately is not.
 *
 * ## Rights
 *
 * Every button here is a download, so they are all behind the viewer's
 * download right rather than their posting right. A viewer without it sees
 * the page and is told, rather than pressing a button that 403s.
 */
@Composable
internal fun ExportScreen() {
    val ctx = LocalSs.current
    val sceneState = scenes()
    var busy by remember { mutableStateOf("") }

    SsPageHeader(title = t("export_center"), sub = t("export_subtitle"))
    if (!ctx.canDownload) {
        MutedText(t("no_access_hint"))
        return
    }

    ZillitSectionLabel(t("data_exports"))
    Reports.DATA_EXPORTS.forEach { item ->
        ExportRow(item, busy) { busy = it }
    }

    ZillitSectionLabel(t("department_exports"))
    MutedText(t("department_exports_hint"))
    Reports.DEPARTMENT_EXPORTS.forEach { item ->
        ExportRow(item, busy) { busy = it }
    }

    ZillitSectionLabel(t("by_shooting_day"))
    Reports.DAY_REPORTS.forEach { item ->
        DayReportRow(item, sceneState.days, sceneState.day, busy) { busy = it }
    }
}

/** One export: what it is, its format, and the button that fetches it. */
@Composable
private fun ExportRow(item: Reports.Export, busy: String, onBusy: (String) -> Unit) {
    val ctx = LocalSs.current
    ZillitSectionCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                ZillitText(t(item.labelKey), style = ZillitTheme.typography.titleSmall)
                MutedText(item.fileName)
            }
            SsTag(item.format, formatTone(item.format))
            ZillitButton(
                text = item.format,
                onClick = {
                    ctx.scope.launch {
                        onBusy(item.kind)
                        ctx.fetchExport("/export/${item.kind}/${ctx.api.project().orEmpty()}", item.fileName)
                        onBusy("")
                    }
                },
                variant = if (item.highlight) ButtonVariant.Primary else ButtonVariant.Secondary,
                leadingIcon = ZillitIcons.Download,
                loading = busy == item.kind,
                enabled = busy.isBlank(),
            )
        }
    }
}

/**
 * One day's report: the day to render it for, and the button.
 *
 * The day defaults to the one the side bar has picked, which is what the web
 * does — a reader filtering the tool to day 4 wants day 4's report.
 */
@Composable
private fun DayReportRow(
    item: Reports.Export,
    days: List<Int>,
    pickedDay: Int?,
    busy: String,
    onBusy: (String) -> Unit,
) {
    val ctx = LocalSs.current
    var day by remember(item.kind, pickedDay) { mutableStateOf(pickedDay) }
    val kind = Reports.reportLabelKey(item.kind)
    ZillitSectionCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                ZillitText(t(kind), style = ZillitTheme.typography.titleSmall)
                MutedText(t("${kind}_hint"))
            }
            val options = days.map { it.toString() to "${t("day")} $it" }
            ZillitSearchSelect(
                value = options.firstOrNull { it.first == day?.toString() },
                options = options,
                onSelect = { day = it.first.toIntOrNull() },
                label = { it.second },
                placeholder = t("pick_day"),
                modifier = Modifier.width(DAY_WIDTH),
            )
            ZillitButton(
                text = item.format,
                onClick = {
                    ctx.scope.launch {
                        onBusy(item.kind)
                        val project = ctx.api.project().orEmpty()
                        // The day is part of the PATH, and of the file name,
                        // so two days' reports do not overwrite each other.
                        ctx.fetchExport(
                            "/reports/${item.kind}/$project/${day ?: ""}",
                            "${item.fileName}${day ?: ""}.pdf",
                        )
                        onBusy("")
                    }
                },
                variant = ButtonVariant.Secondary,
                leadingIcon = ZillitIcons.Download,
                loading = busy == item.kind,
                enabled = busy.isBlank() && day != null,
            )
        }
    }
}

/**
 * Fetches one export and offers to save it.
 *
 * A refusal is toasted in the service's own words; a cancelled save is
 * silent, because the viewer meant it.
 */
private suspend fun SsCtx.fetchExport(path: String, fileName: String) {
    when (val answer = host.downloadBytes(api.url(path))) {
        is ZillitResult.Failure -> toast(answer.error.localised(), false)
        is ZillitResult.Success -> host.save(fileName, answer.data)
    }
}

/** The format badge's colour, by what the file is for. */
private fun formatTone(format: String): StatusTone = when (format) {
    "CSV" -> StatusTone.Done
    "ALE" -> StatusTone.Progress
    else -> StatusTone.Neutral
}

private val DAY_WIDTH = 160.dp
