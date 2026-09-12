package com.zillit.desktop.feature.bankrec.ui.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.feature.bankrec.ui.BankRecEvent
import com.zillit.desktop.feature.bankrec.ui.BankRecUiState
import com.zillit.desktop.feature.bankrec.ui.components.BrFieldLabel
import com.zillit.desktop.feature.bankrec.ui.components.BrTone
import com.zillit.desktop.feature.bankrec.ui.components.bg
import com.zillit.desktop.feature.bankrec.ui.components.edge
import com.zillit.desktop.feature.bankrec.ui.components.eyebrow
import com.zillit.desktop.feature.bankrec.ui.components.fg
import com.zillit.desktop.feature.bankrec.ui.components.mono

/**
 * Every dialog the module opens, over whichever tab is showing.
 *
 * Each stays composed and is driven by its own slice of state, so closing one
 * plays its exit rather than cutting it off: the shell keeps the last state it
 * drew while it fades, and only a dialog that has never opened draws nothing.
 */
@Composable
fun BankRecDialogs(state: BankRecUiState, onEvent: (BankRecEvent) -> Unit) {
    DeletePeriodsDialog(state, onEvent)
    ExportPdfDialog(state, onEvent)
    PeriodDetailDialog(state, onEvent)
    ImportDialog(state, onEvent)
    ConfirmMatchDialog(state, onEvent)
    ManualMatchDialog(state, onEvent)
    SignOffDialog(state, onEvent)
    ExceptionQuickAddDialog(state, onEvent)
    AuditLogDialog(state, onEvent)
    FxPostDialog(state, onEvent)
    PortalLinkDialog(state, onEvent)
}

/** Holds the last non-null value without writing snapshot state during composition. */
private class Last<T : Any> {
    var value: T? = null
}

/**
 * [value], or the last non-null one — what a closing dialog keeps drawing
 * while it animates out.
 */
@Composable
internal fun <T : Any> rememberLast(value: T?): T? {
    val holder = remember { Last<T>() }
    if (value != null) holder.value = value
    return value ?: holder.value
}

/** A form field: the web's small uppercase label, then the control. */
@Composable
internal fun Field(
    label: String,
    modifier: Modifier = Modifier,
    required: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BrFieldLabel(label, trailing = if (required) "*" else null)
        content()
    }
}

/** Two fields side by side, sharing the width. */
@Composable
internal fun FieldPair(left: @Composable ColumnScope.() -> Unit, right: @Composable ColumnScope.() -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(Modifier.weight(1f), content = left)
        Column(Modifier.weight(1f), content = right)
    }
}

/**
 * One side of a match, as both confirmations show it: which side it is, what
 * it is, its reference, the amount and the date.
 */
@Composable
internal fun RowScope.SideCard(
    heading: String,
    title: String,
    reference: String,
    amount: String,
    date: String?,
    tone: BrTone? = null,
) {
    val colors = ZillitTheme.colors
    val shape = RoundedCornerShape(8.dp)
    Column(
        Modifier.weight(1f).fillMaxHeight().clip(shape).background(tone?.bg() ?: colors.surfaceSunken)
            .border(1.dp, tone?.edge() ?: colors.border, shape).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        ZillitText(heading.uppercase(), style = eyebrow(10.sp), color = tone?.fg() ?: colors.textMuted)
        ZillitText(
            title,
            style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            maxLines = 2,
        )
        if (reference.isNotBlank()) {
            ZillitText(reference, style = mono(11.sp), color = colors.textMuted, maxLines = 1)
        }
        ZillitText(amount, style = mono(14.sp, FontWeight.Bold), modifier = Modifier.padding(top = 4.dp))
        date?.let { ZillitText(it, style = ZillitTheme.typography.labelSmall, color = colors.textMuted) }
    }
}
