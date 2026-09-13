@file:Suppress("LongMethod", "TooManyFunctions", "CyclomaticComplexMethod", "ComplexCondition")

package com.zillit.desktop.feature.esignature.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitProgressBar
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.esignature.domain.BulkJob
import com.zillit.desktop.feature.esignature.domain.BulkJobRow
import com.zillit.desktop.feature.esignature.domain.EsignFormat
import com.zillit.desktop.feature.esignature.domain.FieldType
import com.zillit.desktop.feature.esignature.ui.BULK_ROW_CAP
import com.zillit.desktop.feature.esignature.ui.BulkSendState
import com.zillit.desktop.feature.esignature.ui.EsignEvent
import com.zillit.desktop.feature.esignature.ui.EsignSurface
import com.zillit.desktop.feature.esignature.ui.EsignUiState
import com.zillit.desktop.feature.esignature.ui.components.BlockTitle
import com.zillit.desktop.feature.esignature.ui.components.EsignCard
import com.zillit.desktop.feature.esignature.ui.components.Hairline
import com.zillit.desktop.feature.esignature.ui.components.hoverRow

/** The bulk-sends dashboard — the web's `BulkJobsView`. */
@Composable
internal fun BulkPage(state: EsignUiState, onEvent: (EsignEvent) -> Unit) {
    val bulk = state.bulk
    val colors = ZillitTheme.colors
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                ZillitText("Bulk sends", style = ZillitTheme.typography.titleMedium)
                ZillitText(
                    "One template, a CSV of people, one envelope per row. Running jobs refresh every few seconds.",
                    style = ZillitTheme.typography.bodySmall,
                    color = colors.textMuted,
                )
            }
        }
        when {
            bulk.loading && !bulk.loaded -> Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) { ZillitSpinner() }
            bulk.jobs.isEmpty() -> Box(
                Modifier.fillMaxWidth().padding(vertical = 64.dp),
                contentAlignment = Alignment.Center,
            ) {
                ZillitEmptyState(
                    title = "No bulk sends yet",
                    message = "Open Templates and choose Bulk send on any template to start one.",
                    icon = ZillitIcons.Send,
                    action = {
                        ZillitButton(
                            "Go to templates",
                            onClick = { onEvent(EsignEvent.SwitchSurface(EsignSurface.Templates)) },
                            size = ButtonSize.Small,
                        )
                    },
                )
            }
            else -> Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                ZillitScrollColumn(Modifier.weight(2f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    bulk.jobs.forEach { job ->
                        JobCard(
                            job,
                            selected = job.id == bulk.open?.id,
                            busy = bulk.busyJobId == job.id,
                            onEvent = onEvent,
                        )
                    }
                }
                Box(Modifier.weight(3f).fillMaxSize()) {
                    val open = bulk.open
                    if (open == null) {
                        Box(
                            Modifier.fillMaxSize().clip(ZillitTheme.shapes.large).border(
                                1.dp,
                                colors.border,
                                ZillitTheme.shapes.large,
                            ),
                            contentAlignment = Alignment.Center,
                        ) {
                            ZillitText(
                                "Select a job to see every row.",
                                style = ZillitTheme.typography.bodySmall,
                                color = colors.textMuted,
                            )
                        }
                    } else {
                        JobRows(open, bulk.openLoading, onEvent)
                    }
                }
            }
        }
    }
    BulkSendDialog(state, onEvent)
}

@Composable
private fun JobCard(job: BulkJob, selected: Boolean, busy: Boolean, onEvent: (EsignEvent) -> Unit) {
    val colors = ZillitTheme.colors
    val tone = when {
        job.isRunning -> StatusTone.Progress
        job.status == "completed" && job.failed == 0 -> StatusTone.Done
        job.status == "completed" -> StatusTone.Pending
        job.status.isBlank() -> StatusTone.Neutral
        else -> StatusTone.Rejected
    }
    EsignCard(
        onClick = { onEvent(EsignEvent.OpenBulkJob(if (selected) null else job)) },
        accent = if (selected) colors.accent else null,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                ZillitText(job.displayName, style = ZillitTheme.typography.titleSmall, maxLines = 1)
                ZillitText(
                    listOfNotNull(
                        job.templateName.takeIf { it.isNotBlank() && it != job.displayName },
                        "sent ${EsignFormat.dateTime(job.created)}",
                    ).joinToString(" · "),
                    style = ZillitTheme.typography.labelSmall,
                    color = colors.textMuted,
                )
            }
            ZillitStatusPill(
                label = job.status.ifBlank { "queued" }.replaceFirstChar(Char::uppercase),
                tone = tone,
                dot = true,
            )
        }
        Spacer(Modifier.height(10.dp))
        ZillitProgressBar(
            fraction = job.progressFraction,
            modifier = Modifier.fillMaxWidth(),
            fillColor = if (job.failed > 0) colors.warning else colors.accent,
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Stat("${job.processed} / ${job.totalRows}", "processed")
            Stat("${job.succeeded}", "succeeded", colors.success)
            Stat("${job.failed}", "failed", if (job.failed > 0) colors.danger else null)
        }
        if (job.isTerminal) {
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ZillitButton(
                    "Remind outstanding",
                    onClick = { onEvent(EsignEvent.RemindOutstanding(job.id)) },
                    size = ButtonSize.Small,
                    variant = ButtonVariant.Secondary,
                    leadingIcon = ZillitIcons.Bell,
                    loading = busy,
                )
                if (job.failed > 0) {
                    ZillitButton(
                        "Retry failed (${job.failed})",
                        onClick = { onEvent(EsignEvent.RetryFailed(job.id)) },
                        size = ButtonSize.Small,
                        variant = ButtonVariant.Tertiary,
                        leadingIcon = ZillitIcons.Reload,
                        enabled = !busy,
                    )
                }
            }
        }
    }
}

@Composable
private fun Stat(value: String, label: String, tint: Color? = null) {
    val colors = ZillitTheme.colors
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        ZillitText(
            value,
            style = ZillitTheme.typography.label.copy(fontWeight = FontWeight.SemiBold),
            color = tint ?: colors.textPrimary,
        )
        ZillitText(label, style = ZillitTheme.typography.labelSmall, color = colors.textMuted)
    }
}

@Composable
private fun JobRows(job: BulkJob, loading: Boolean, onEvent: (EsignEvent) -> Unit) {
    val colors = ZillitTheme.colors
    Column(
        Modifier.fillMaxSize().clip(ZillitTheme.shapes.large).background(colors.surface)
            .border(1.dp, colors.border, ZillitTheme.shapes.large),
    ) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                ZillitText(job.displayName, style = ZillitTheme.typography.titleSmall)
                ZillitText(
                    "${job.rows.size} row${if (job.rows.size == 1) "" else "s"} · click a row to open its envelope",
                    style = ZillitTheme.typography.labelSmall,
                    color = colors.textMuted,
                )
            }
            if (loading) ZillitSpinner()
        }
        Hairline()
        if (job.rows.isEmpty() && !loading) {
            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                ZillitText(
                    "Rows are not available for this job yet.",
                    style = ZillitTheme.typography.bodySmall,
                    color = colors.textMuted,
                )
            }
        }
        ZillitScrollColumn(Modifier.fillMaxSize()) {
            job.rows.forEach { row -> RowLine(row, onEvent) }
        }
    }
}

@Composable
private fun RowLine(row: BulkJobRow, onEvent: (EsignEvent) -> Unit) {
    val colors = ZillitTheme.colors
    val tone = when (row.stage) {
        "signed" -> StatusTone.Done
        "declined", "failed" -> StatusTone.Rejected
        "viewed" -> StatusTone.Progress
        "pending" -> StatusTone.Neutral
        else -> StatusTone.Pending
    }
    val openable = row.envelopeId.isNotBlank()
    Column {
        Row(
            Modifier.fillMaxWidth()
                .hoverRow(if (openable) ({ onEvent(EsignEvent.OpenDetail(row.envelopeId)) }) else null)
                .padding(
                horizontal = 14.dp,
                vertical = 10.dp,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                Modifier.size(24.dp).clip(CircleShape).background(colors.surfaceSunken),
                contentAlignment = Alignment.Center,
            ) {
                ZillitText(
                    "${row.rowIndex + 1}",
                    style = ZillitTheme.typography.labelSmall,
                    color = colors.textSecondary,
                )
            }
            Column(Modifier.weight(1f)) {
                ZillitText(
                    row.name.ifBlank { row.email.substringBefore('@').ifBlank { "Recipient" } },
                    style = ZillitTheme.typography.bodyMedium,
                    maxLines = 1,
                )
                ZillitText(row.email, style = ZillitTheme.typography.labelSmall, color = colors.textMuted, maxLines = 1)
            }
            if (row.error.isNotBlank()) {
                ZillitText(
                    row.error,
                    style = ZillitTheme.typography.labelSmall,
                    color = colors.danger,
                    maxLines = 1,
                    modifier = Modifier.width(200.dp),
                )
            }
            row.lastRemindedOn?.let {
                ZillitText(
                    "Reminded ${EsignFormat.relative(it)}",
                    style = ZillitTheme.typography.labelSmall,
                    color = colors.textMuted,
                )
            }
            ZillitStatusPill(label = row.stage.replaceFirstChar(Char::uppercase), tone = tone, dot = true)
            if (openable) ZillitIcon(ZillitIcons.ChevronRight, tint = colors.textMuted, size = 14.dp)
        }
        Hairline()
    }
}

// ---------------------------------------------------------------- the send

@Composable
private fun BulkSendDialog(state: EsignUiState, onEvent: (EsignEvent) -> Unit) {
    val send = state.bulk.send
    ZillitDialogShell(
        title = "Bulk send",
        subtitle = send?.template?.name,
        visible = send != null,
        onDismiss = { onEvent(EsignEvent.CancelBulkSend) },
        scrollable = false,
        icon = ZillitIcons.Send,
        width = 680.dp,
        actions = {
            if (send != null) {
                ZillitButton(
                    "Cancel",
                    onClick = { onEvent(EsignEvent.CancelBulkSend) },
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                )
                if (send.step > 1) ZillitButton(
                    "Back",
                    onClick = { onEvent(EsignEvent.BulkStep(send.step - 1)) },
                    variant = ButtonVariant.Secondary,
                    size = ButtonSize.Small,
                )
                when (send.step) {
                    1 -> ZillitButton(
                        "Choose CSV",
                        onClick = { onEvent(EsignEvent.PickBulkCsv) },
                        size = ButtonSize.Small,
                        leadingIcon = ZillitIcons.Upload,
                    )
                    2 -> ZillitButton(
                        "Next",
                        onClick = { onEvent(EsignEvent.BulkStep(3)) },
                        size = ButtonSize.Small,
                        enabled = send.parsed?.hasRequiredColumns == true && send.validCount > 0 && !send.overCap,
                    )
                    else -> ZillitButton(
                        "Send ${send.validCount} envelope${if (send.validCount == 1) "" else "s"}",
                        onClick = { onEvent(EsignEvent.ConfirmBulkSend) },
                        size = ButtonSize.Small,
                        leadingIcon = ZillitIcons.Send,
                        loading = send.submitting,
                        enabled = send.validCount > 0,
                    )
                }
            }
        },
    ) {
        if (send == null) return@ZillitDialogShell
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Steps(send.step)
            when (send.step) {
                1 -> UploadStep(send, onEvent)
                2 -> PreviewStep(send)
                else -> ConfirmStep(send, onEvent)
            }
        }
    }
}

@Composable
private fun Steps(step: Int) {
    val colors = ZillitTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("Upload CSV", "Preview", "Confirm").forEachIndexed { i, label ->
            val n = i + 1
            val active = n == step
            val done = n < step
            Box(
                Modifier.size(22.dp)
                    .clip(CircleShape)
                    .background(if (active || done) colors.accent else colors.surfaceSunken),
                contentAlignment = Alignment.Center,
            ) {
                if (done) ZillitIcon(ZillitIcons.Check, tint = Color.White, size = 12.dp) else ZillitText(
                    "$n",
                    style = ZillitTheme.typography.labelSmall,
                    color = if (active) Color.White else colors.textSecondary,
                )
            }
            ZillitText(
                label,
                style = ZillitTheme.typography.label,
                color = if (active) colors.textPrimary else colors.textMuted,
            )
            if (i < 2) Box(Modifier.width(24.dp).height(1.dp).background(colors.border))
        }
    }
}

@Composable
private fun UploadStep(send: BulkSendState, onEvent: (EsignEvent) -> Unit) {
    val colors = ZillitTheme.colors
    val template = send.template
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(
            Modifier.fillMaxWidth().clip(ZillitTheme.shapes.medium).border(
                2.dp,
                colors.accent.copy(alpha = 0.5f),
                ZillitTheme.shapes.medium,
            )
                .background(colors.accentSoft.copy(alpha = 0.35f))
                .clickable { onEvent(EsignEvent.PickBulkCsv) }
                .padding(vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ZillitIcon(ZillitIcons.Upload, tint = colors.accent, size = 26.dp)
            ZillitText(send.fileName.ifBlank { "Choose a CSV" }, style = ZillitTheme.typography.titleSmall)
            ZillitText(
                "One row per recipient · up to $BULK_ROW_CAP rows",
                style = ZillitTheme.typography.bodySmall,
                color = colors.textMuted,
            )
        }
        BlockTitle("CSV format")
        ZillitText(
            "Required columns: name, email. Any other column whose header matches one of the template's field " +
                "labels pre-fills that field. Comma, semicolon and tab separators are all read.",
            style = ZillitTheme.typography.bodySmall,
            color = colors.textSecondary,
        )
        val fillable = template.fields
            .filter { it.type.isTyped || it.type == FieldType.Date }
            .map { it.label }
            .filter { it.isNotBlank() }
            .distinct()
        Box(Modifier.fillMaxWidth().clip(ZillitTheme.shapes.medium).background(colors.surfaceSunken).padding(10.dp)) {
            ZillitText(
                (listOf("name", "email") + fillable).joinToString(",") +
                    "\nJane Doe,jane@example.com" + ",…".repeat(fillable.size),
                style = ZillitTheme.typography.bodySmall.copy(fontFamily = ZillitTheme.fonts.mono),
                color = colors.textSecondary,
            )
        }
    }
}

@Composable
private fun PreviewStep(send: BulkSendState) {
    val colors = ZillitTheme.colors
    val parsed = send.parsed
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (parsed == null) {
            ZillitText("Nothing parsed yet.", color = colors.textMuted)
            return
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Stat("${parsed.rows.size}", "rows detected")
            Stat("${send.validCount}", "sendable", colors.success)
            Stat("${send.invalidCount}", "skipped", if (send.invalidCount > 0) colors.warning else null)
            Stat(if (parsed.delimiter == '\t') "tab" else "'${parsed.delimiter}'", "separator")
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Check(
                parsed.hasRequiredColumns,
                if (parsed.hasRequiredColumns) {
                    "Required columns present (name, email)"
                } else {
                    "Missing a name or email column"
                },
            )
            Check(
                send.validCount == parsed.rows.size,
                "${send.validCount}/${parsed.rows.size} rows have a name and a valid email",
            )
            if (parsed.extraColumns.isNotEmpty()) Check(
                true,
                "Extra columns: ${parsed.extraColumns.joinToString()} — matched to field labels where names agree",
            )
            if (send.overCap) Check(false, "Over the $BULK_ROW_CAP-row cap — split the file")
        }
        Hairline()
        ZillitScrollColumn(Modifier.fillMaxWidth().height(220.dp)) {
            parsed.rows.take(PREVIEW_ROWS).forEachIndexed { i, row ->
                val ok = parsed.rowValid(row)
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ZillitText(
                        "${i + 1}",
                        style = ZillitTheme.typography.labelSmall,
                        color = colors.textMuted,
                        modifier = Modifier.width(24.dp),
                    )
                    ZillitText(
                        parsed.nameKey?.let { row[it] }.orEmpty(),
                        style = ZillitTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                    )
                    ZillitText(
                        parsed.emailKey?.let { row[it] }.orEmpty(),
                        style = ZillitTheme.typography.bodySmall,
                        color = if (ok) colors.textSecondary else colors.danger,
                        modifier = Modifier.weight(1.4f),
                        maxLines = 1,
                    )
                    ZillitStatusPill(
                        label = if (ok) "OK" else "Skip",
                        tone = if (ok) StatusTone.Done else StatusTone.Rejected,
                    )
                }
            }
            if (parsed.rows.size > PREVIEW_ROWS) ZillitText(
                "… and ${parsed.rows.size - PREVIEW_ROWS} more",
                style = ZillitTheme.typography.labelSmall,
                color = colors.textMuted,
            )
        }
    }
}

@Composable
private fun Check(ok: Boolean, text: String) {
    val colors = ZillitTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ZillitIcon(
            if (ok) ZillitIcons.Check else ZillitIcons.Warning,
            tint = if (ok) colors.success else colors.warning,
            size = 14.dp,
        )
        ZillitText(text, style = ZillitTheme.typography.bodySmall)
    }
}

@Composable
private fun ConfirmStep(send: BulkSendState, onEvent: (EsignEvent) -> Unit) {
    val colors = ZillitTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ZillitTextField(
            value = send.batchName,
            onValueChange = { onEvent(EsignEvent.EditBatchName(it)) },
            label = "Batch name (optional)",
            placeholder = "${send.template.name} · ${EsignFormat.today()}",
            helperText = "Shown on the Bulk Sends dashboard.",
        )
        Box(Modifier.fillMaxWidth().clip(ZillitTheme.shapes.medium).background(colors.surfaceSunken).padding(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ZillitText(
                    "${send.validCount} envelope${if (send.validCount == 1) "" else "s"} will be created from " +
                        "“${send.template.name}” and sent immediately.",
                    style = ZillitTheme.typography.bodyMedium,
                )
                if (send.invalidCount > 0) ZillitText(
                    "${send.invalidCount} row${if (send.invalidCount == 1) "" else "s"} skipped for a missing name " +
                        "or invalid email.",
                    style = ZillitTheme.typography.bodySmall,
                    color = colors.warning,
                )
                ZillitText(
                    "Each recipient gets their own envelope, notification and audit trail.",
                    style = ZillitTheme.typography.bodySmall,
                    color = colors.textMuted,
                )
            }
        }
    }
}

private const val PREVIEW_ROWS = 50
