package com.zillit.desktop.feature.cashexpenses.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.cashexpenses.domain.CashCurrencies

// Small pieces the crew pages share: the web's titled Notice, its numbered
// form-step card, labelled fields and the inline error and info boxes.

/** The web's `Notice` with a title — "Crew Portal — Ada (Gaffer)" over one line. */
@Composable
internal fun CrewNotice(
    title: String,
    body: String,
    icon: ImageVector = ZillitIcons.User,
    tone: StatusTone = StatusTone.Pending,
    modifier: Modifier = Modifier,
) {
    val colors = ZillitTheme.colors
    val (background, content) = when (tone) {
        StatusTone.Progress -> colors.infoSoft to colors.info
        else -> colors.accentSoft to colors.accentText
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.large)
            .background(background)
            .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.md),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        verticalAlignment = Alignment.Top,
    ) {
        ZillitIcon(icon = icon, tint = content, size = NOTICE_ICON)
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
            ZillitText(text = title, style = ZillitTheme.typography.titleSmall, color = colors.textPrimary)
            if (body.isNotBlank()) {
                ZillitText(text = body, style = ZillitTheme.typography.bodySmall, color = colors.textSecondary)
            }
        }
    }
}

/** A white card headed by a dark numbered circle — the web's `FormStepCard`. */
@Composable
internal fun StepCard(
    number: Int,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = ZillitTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.large)
            .background(colors.surface)
            .border(CREW_HAIRLINE, colors.border, ZillitTheme.shapes.large)
            .padding(CARD_PADDING),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(STEP_DOT).clip(CircleShape).background(colors.textPrimary),
                contentAlignment = Alignment.Center,
            ) {
                ZillitText(text = number.toString(), style = ZillitTheme.typography.label, color = colors.surface)
            }
            Column {
                ZillitText(text = title, style = ZillitTheme.typography.titleSmall)
                ZillitText(text = subtitle, style = ZillitTheme.typography.bodySmall, color = colors.textMuted)
            }
        }
        content()
    }
}

/** A field label with the web's orange asterisk, or "(optional)". */
@Composable
internal fun CrewLabel(text: String, required: Boolean = false, optional: Boolean = false) {
    val colors = ZillitTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
        ZillitText(text = text.uppercase(), style = ZillitTheme.typography.labelSmall, color = colors.textMuted)
        if (required) ZillitText(text = "*", style = ZillitTheme.typography.labelSmall, color = colors.accentText)
        if (optional) {
            ZillitText(
                text = str(S.desktop_optional_tail),
                style = ZillitTheme.typography.labelSmall,
                color = colors.textMuted,
            )
        }
    }
}

/** A labelled column: the label, the control, then an error or a helper line. */
@Composable
internal fun CrewField(
    label: String,
    modifier: Modifier = Modifier,
    required: Boolean = false,
    optional: Boolean = false,
    error: String? = null,
    helper: String? = null,
    control: @Composable () -> Unit,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        CrewLabel(label, required, optional)
        control()
        if (error != null) {
            ZillitText(text = error, style = ZillitTheme.typography.labelSmall, color = ZillitTheme.colors.danger)
        }
        if (helper != null) {
            ZillitText(text = helper, style = ZillitTheme.typography.labelSmall, color = ZillitTheme.colors.textMuted)
        }
    }
}

/** The red box a refused submit shows above its button. */
@Composable
internal fun CrewErrorBox(message: String?) {
    message ?: return
    val colors = ZillitTheme.colors
    ZillitText(
        text = message,
        style = ZillitTheme.typography.bodySmall,
        color = colors.danger,
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.medium)
            .background(colors.dangerSoft)
            .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.sm),
    )
}

/** The web's blue info strip. */
@Composable
internal fun CrewInfoBox(text: String, modifier: Modifier = Modifier, title: String? = null) {
    val colors = ZillitTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.large)
            .background(colors.infoSoft)
            .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.md),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitIcon(icon = ZillitIcons.Info, tint = colors.info, size = INFO_ICON)
        Column {
            if (title != null) ZillitText(text = title, style = ZillitTheme.typography.label, color = colors.info)
            ZillitText(text = text, style = ZillitTheme.typography.bodySmall, color = colors.info)
        }
    }
}

/** A small uppercase section heading. */
@Composable
internal fun CrewHeading(text: String, modifier: Modifier = Modifier) {
    ZillitText(
        text = text.uppercase(),
        style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
        color = ZillitTheme.colors.textMuted,
        modifier = modifier,
    )
}

/** Project currencies as a picker — the web's currency `RichSelect`, never clearable. */
@Composable
internal fun CurrencyPicker(currencies: CashCurrencies, value: String, onPick: (String) -> Unit, modifier: Modifier) {
    val selected = value.ifBlank { currencies.default }
    val codes = (currencies.currencies.map { it.code } + selected).distinct()
    ZillitSelect(
        value = selected,
        options = codes,
        onSelect = onPick,
        label = { code -> currencies.symbolFor(code).takeIf { it.isNotBlank() }?.let { "$it $code" } ?: code },
        modifier = modifier,
    )
}

/**
 * A text field that suggests as it is typed — the web's `CoaCodeInput`:
 * any text is kept, and a suggestion only fills the field.
 */
@Composable
internal fun <T> SuggestField(
    value: String,
    onValueChange: (String) -> Unit,
    suggestions: List<T>,
    text: (T) -> String,
    pick: (T) -> String,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    var dismissed by remember(value) { mutableStateOf(false) }
    val needle = value.trim().lowercase()
    val matches = if (needle.isEmpty()) {
        emptyList()
    } else {
        suggestions.filter { text(it).lowercase().contains(needle) && pick(it) != value }.take(MAX_SUGGESTIONS)
    }
    Box(modifier = modifier) {
        ZillitTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = placeholder,
            modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
        )
        DropdownMenu(
            expanded = focused && !dismissed && matches.isNotEmpty(),
            onDismissRequest = { dismissed = true },
            // Not focusable: typing carries on in the field underneath.
            properties = PopupProperties(focusable = false),
            modifier = Modifier.width(SUGGEST_WIDTH).background(ZillitTheme.colors.surfaceRaised),
        ) {
            matches.forEach { option ->
                DropdownMenuItem(
                    text = { ZillitText(text = text(option), style = ZillitTheme.typography.bodyMedium, maxLines = 1) },
                    onClick = {
                        onValueChange(pick(option))
                        dismissed = true
                    },
                )
            }
        }
    }
}

internal val CREW_HAIRLINE = 1.dp
internal val CARD_PADDING = 20.dp
private val STEP_DOT = 26.dp
private val NOTICE_ICON = 16.dp
private val INFO_ICON = 14.dp
private val SUGGEST_WIDTH = 360.dp
private const val MAX_SUGGESTIONS = 8
