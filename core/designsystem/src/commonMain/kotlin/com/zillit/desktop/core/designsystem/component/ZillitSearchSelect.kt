package com.zillit.desktop.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * A select whose list can be searched — the web's `RichSelect` with
 * `typeable`: the closed field shows the pick (or [placeholder]), and opening
 * it puts a search box above the options, filtered as it is typed.
 *
 * With [onCreate] set, a search that matches nothing offers
 * `Create "<typed>"` instead of "No results" — the vendor quick-add. Picking
 * it hands the trimmed text back; creating the record is the caller's
 * business (the invoice forms defer it to their own submit).
 *
 * [value] null (or not among [options]) shows [placeholder]. [subtitle]
 * gives an option a muted second line; [searchText] widens what the search
 * matches beyond the label. [isError] draws the danger border, as a field
 * with a validation error does.
 */
@Suppress("LongParameterList", "LongMethod")
@Composable
fun <T> ZillitSearchSelect(
    value: T?,
    options: List<T>,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    enabled: Boolean = true,
    isError: Boolean = false,
    searchText: (T) -> String = label,
    subtitle: ((T) -> String?)? = null,
    onCreate: ((String) -> Unit)? = null,
    dropdownWidth: Dp? = null,
) {
    val colors = ZillitTheme.colors
    var expanded by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val shown = value?.takeIf { it in options }
    val needle = query.trim().lowercase()
    val filtered = if (needle.isEmpty()) {
        options
    } else {
        options.filter { searchText(it).lowercase().contains(needle) }
    }

    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .defaultMinSize(minHeight = FIELD_HEIGHT)
                .widthIn(min = MIN_WIDTH)
                .fillMaxWidth()
                .clip(ZillitTheme.shapes.medium)
                .background(colors.surfaceSunken)
                .border(HAIRLINE, if (isError) colors.danger else colors.border, ZillitTheme.shapes.medium)
                .clickable(enabled = enabled) {
                    query = ""
                    expanded = true
                }
                .padding(horizontal = ZillitTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitText(
                text = shown?.let(label) ?: placeholder,
                style = ZillitTheme.typography.bodyMedium,
                color = when {
                    !enabled -> colors.textDisabled
                    shown == null -> colors.textMuted
                    else -> colors.textPrimary
                },
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            ZillitIcon(icon = ZillitIcons.ChevronDown, tint = colors.textMuted, size = ICON_SIZE)
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier
                .background(colors.surfaceRaised, RoundedCornerShape(MENU_RADIUS))
                .let { if (dropdownWidth != null) it.width(dropdownWidth) else it.widthIn(min = MIN_WIDTH) },
        ) {
            val focus = remember { FocusRequester() }
            val create = onCreate?.takeIf { filtered.isEmpty() && query.isNotBlank() }
            ZillitTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = str(S.search),
                leadingIcon = ZillitIcons.Search,
                imeAction = ImeAction.Done,
                onImeAction = {
                    when {
                        filtered.size == 1 -> {
                            onSelect(filtered.single())
                            expanded = false
                        }
                        create != null -> {
                            create(query.trim())
                            expanded = false
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = ZillitTheme.spacing.sm, vertical = ZillitTheme.spacing.xs)
                    .focusRequester(focus),
            )
            LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
            when {
                create != null -> DropdownMenuItem(
                    onClick = {
                        create(query.trim())
                        expanded = false
                    },
                    leadingIcon = { ZillitIcon(icon = ZillitIcons.Add, tint = colors.accentText, size = ICON_SIZE) },
                    text = {
                        ZillitText(
                            text = str(S.desktop_create_quoted, query.trim()),
                            style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = colors.accentText,
                        )
                    },
                )
                filtered.isEmpty() -> ZillitText(
                    text = str(S.drive_no_results_for_format, query.trim()),
                    style = ZillitTheme.typography.bodySmall,
                    color = colors.textMuted,
                    modifier = Modifier.padding(ZillitTheme.spacing.md),
                )
                else -> filtered.take(MAX_SHOWN).forEach { option ->
                    val selected = option == shown
                    DropdownMenuItem(
                        onClick = {
                            expanded = false
                            onSelect(option)
                        },
                        modifier = Modifier.background(if (selected) colors.surfaceSelected else colors.surfaceRaised),
                        text = {
                            Column {
                                ZillitText(
                                    text = label(option),
                                    style = ZillitTheme.typography.bodyMedium,
                                    color = if (selected) colors.accentText else colors.textPrimary,
                                    maxLines = 1,
                                )
                                subtitle?.invoke(option)?.takeIf { it.isNotBlank() }?.let {
                                    ZillitText(
                                        text = it,
                                        style = ZillitTheme.typography.labelSmall,
                                        color = colors.textMuted,
                                        maxLines = 1,
                                    )
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

private val FIELD_HEIGHT = 36.dp
private val MIN_WIDTH = 170.dp
private val ICON_SIZE = 16.dp
private val HAIRLINE = 1.dp
private val MENU_RADIUS = 8.dp

/** A long list is searched, not scrolled; this keeps the popup a sane height. */
private const val MAX_SHOWN = 200
