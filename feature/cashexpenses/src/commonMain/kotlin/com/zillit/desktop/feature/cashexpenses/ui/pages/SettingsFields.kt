package com.zillit.desktop.feature.cashexpenses.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitSwitch
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.cashexpenses.domain.CashAccount
import com.zillit.desktop.feature.cashexpenses.domain.commaList

/**
 * A chart-code field — the web's `BsCodeInput` / `CoaCodeInput` with
 * `pickableLevels="nominals"`: postable leaves are offered as the person
 * types, and a code not in the chart is kept as typed.
 */
@Suppress("LongMethod") // The field and its suggestion list.
@Composable
internal fun CashCodeField(
    value: String,
    onValueChange: (String) -> Unit,
    accounts: List<CashAccount>,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    helperText: String? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val pool = remember(accounts) { accounts.filter { it.postable && it.leaf } }
    val trimmed = value.trim()
    val known = pool.firstOrNull { it.code.equals(trimmed, ignoreCase = true) }
    val suggestions = remember(trimmed, pool) {
        val starts = pool.filter { it.code.startsWith(trimmed, ignoreCase = true) }
        val contains = pool.filter {
            it !in starts && (it.code.contains(trimmed, true) || it.name.contains(trimmed, true))
        }
        (starts + contains).take(SUGGESTIONS)
    }
    val colors = ZillitTheme.colors
    Box(modifier = modifier) {
        ZillitTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = placeholder,
            helperText = when {
                known != null -> known.name.ifBlank { null } ?: helperText
                trimmed.isNotEmpty() && pool.isNotEmpty() -> str(S.desktop_pc_not_in_chart)
                else -> helperText
            },
            modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
        )
        if (focused && known == null && suggestions.isNotEmpty()) {
            Popup(offset = IntOffset(0, CODE_DROP), onDismissRequest = { focused = false }) {
                Column(
                    modifier = Modifier
                        .width(POPUP_WIDTH)
                        .shadow(POPUP_ELEVATION, ZillitTheme.shapes.large)
                        .clip(ZillitTheme.shapes.large)
                        .background(colors.surfaceRaised)
                        .border(1.dp, colors.border, ZillitTheme.shapes.large)
                        .padding(ZillitTheme.spacing.xs),
                ) {
                    suggestions.forEach { account ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(ZillitTheme.shapes.medium)
                                .clickable {
                                    onValueChange(account.code)
                                    focused = false
                                }
                                .padding(horizontal = ZillitTheme.spacing.sm, vertical = ZillitTheme.spacing.xs),
                            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                        ) {
                            ZillitText(
                                text = account.code,
                                style = ZillitTheme.typography.numeric.copy(fontFamily = FontFamily.Monospace),
                            )
                            ZillitText(
                                text = account.name,
                                style = ZillitTheme.typography.bodySmall,
                                color = colors.textSecondary,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** The accounts a balance-sheet field offers — the chart's balance-sheet rows, or all of it when none is marked. */
internal fun List<CashAccount>.balanceSheetCodes(): List<CashAccount> =
    filter { it.balanceSheet }.ifEmpty { this }

/** The accounts a nominal field offers — the P&L rows, or all of it when none is marked. */
internal fun List<CashAccount>.nominalCodes(): List<CashAccount> =
    filterNot { it.balanceSheet }.ifEmpty { this }

/**
 * Comma-separated words kept as typed — the web's `keywordStrings`. The list
 * behind it is the parsed text; the text itself is held here, so a trailing
 * comma is not eaten before the next word is typed.
 */
@Composable
internal fun CommaListField(
    values: List<String>,
    onChange: (List<String>) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
) {
    // Re-seeded only when the list changes from outside the typing.
    var text by remember(values) { mutableStateOf(values.joinToString(", ")) }
    ZillitTextField(
        value = text,
        onValueChange = { typed ->
            text = typed
            val parsed = typed.commaList()
            if (parsed != values) onChange(parsed)
        },
        placeholder = placeholder,
        modifier = modifier,
    )
}

/** A label and its explanation beside a switch — the web's toggle rows. */
@Composable
internal fun SettingToggleRow(
    label: String,
    detail: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            ZillitText(
                text = label,
                style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            )
            ZillitText(
                text = detail,
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textSecondary,
            )
        }
        ZillitSwitch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

/** A section's Save — shown only while the section differs from what is stored, as on the web. */
@Composable
internal fun SectionSave(dirty: Boolean, saving: Boolean, enabled: Boolean = true, onSave: () -> Unit) {
    if (!dirty && !saving) return
    ZillitButton(
        text = if (saving) str(S.desktop_pc_saving) else str(S.save),
        onClick = onSave,
        size = ButtonSize.Small,
        enabled = enabled && !saving,
    )
}

/** A section's "+ Add …" button. */
@Composable
internal fun SectionAdd(text: String, onClick: () -> Unit) {
    ZillitButton(
        text = text,
        onClick = onClick,
        variant = ButtonVariant.Tertiary,
        size = ButtonSize.Small,
        leadingIcon = com.zillit.desktop.core.designsystem.icon.ZillitIcons.Add,
    )
}

/** A field's caption above it — the web's small uppercase labels. */
@Composable
internal fun FieldCaption(text: String, modifier: Modifier = Modifier) {
    ZillitText(
        text = text.uppercase(),
        style = ZillitTheme.typography.labelSmall,
        color = ZillitTheme.colors.textMuted,
        modifier = modifier,
    )
}

/** A line of quiet text — an empty section, a hint. */
@Composable
internal fun QuietLine(text: String, modifier: Modifier = Modifier) {
    ZillitText(
        text = text,
        style = ZillitTheme.typography.bodySmall,
        color = ZillitTheme.colors.textMuted,
        modifier = modifier,
    )
}

private const val SUGGESTIONS = 8
private const val CODE_DROP = 60
private val POPUP_WIDTH = 320.dp
private val POPUP_ELEVATION = 8.dp
