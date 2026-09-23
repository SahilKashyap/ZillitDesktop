package com.zillit.desktop.feature.taxfiling.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.taxfiling.domain.CoaCode
import com.zillit.desktop.feature.taxfiling.domain.CoaCodes

/**
 * The ledger-codes picker — the web's `CoaCodeInput` in its add-to-list mode.
 *
 * Focus opens the chart; typing narrows it by code and by name, best match
 * first; Enter or a click adds the code and clears the field for the next one.
 * A code the chart does not have can still be added as typed — the web offers
 * the same "Use code — not in Chart of Accounts" row — and ↑/↓ move through
 * the list, Escape closes it.
 */
@Composable
internal fun MtdCodeInput(
    codes: List<CoaCode>,
    onCommit: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = str(S.desktop_add_code_ellipsis),
    enabled: Boolean = true,
    loading: Boolean = false,
    failed: Boolean = false,
    /** Already picked: offered all the same, marked, and a second add is a no-op upstream. */
    picked: List<String> = emptyList(),
) {
    val picker = remember { CodePicker() }
    var width by remember { mutableIntStateOf(0) }
    val ranked = remember(codes, picker.query) { CoaCodes.rank(codes, picker.query).take(MAX_ROWS) }
    val commit: (String) -> Unit = { code -> picker.commit(code, onCommit) }

    Box(modifier = modifier.onSizeChanged { width = it.width }) {
        CodeField(
            picker = picker,
            placeholder = placeholder,
            enabled = enabled,
            loading = loading,
            onKey = { event -> picker.onKey(event, ranked, onCommit) },
        )
        if (picker.open && picker.focused && enabled) {
            val typed = picker.typed
            DropdownPopup(widthPx = width, onDismiss = { picker.open = false }, focusable = false, maxHeight = 256.dp) {
                CodeList(
                    ranked = ranked,
                    highlight = if (picker.lit) picker.highlight else -1,
                    picked = picked,
                    unknownCode = typed.takeIf { it.isNotEmpty() && codes.none { c -> c.code.equals(it, true) } },
                    state = when {
                        loading -> ListState.Loading
                        failed -> ListState.Failed
                        codes.isEmpty() -> ListState.EmptyChart
                        else -> ListState.Ready
                    },
                    onPick = commit,
                )
            }
        }
    }
}

/**
 * The picker's typing and list state, and what each key does to it.
 *
 * Enter in an empty field adds nothing until a row has been chosen with the
 * arrows — [navigated] is what tells the two apart.
 */
@Stable
private class CodePicker {
    var query by mutableStateOf("")
    var focused by mutableStateOf(false)
    var open by mutableStateOf(false)
    var highlight by mutableIntStateOf(0)
    var navigated by mutableStateOf(false)

    val typed: String get() = query.trim()

    /** A row is lit once something is typed or the arrows have moved, never before. */
    val lit: Boolean get() = typed.isNotEmpty() || navigated

    fun type(next: String) {
        query = CoaCodes.sanitise(next)
        highlight = 0
        navigated = false
        open = true
    }

    fun focus(isFocused: Boolean) {
        focused = isFocused
        if (isFocused) open = true
    }

    fun commit(code: String, onCommit: (String) -> Unit) {
        if (code.isBlank()) return
        onCommit(code.trim())
        query = ""
        highlight = 0
        navigated = false
    }

    fun onKey(event: KeyEvent, ranked: List<CoaCode>, onCommit: (String) -> Unit): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        return when (event.key) {
            Key.DirectionDown -> down(ranked.size)
            Key.DirectionUp -> up()
            Key.Enter, Key.NumPadEnter -> {
                if (lit) commit(ranked.getOrNull(highlight)?.code ?: typed, onCommit)
                true
            }
            Key.Escape -> escape()
            else -> false
        }
    }

    /** The first ↓ lights the top row; later ones move down, stopping at the last. */
    private fun down(rows: Int): Boolean {
        open = true
        if (navigated) highlight = (highlight + 1).coerceAtMost((rows - 1).coerceAtLeast(0))
        navigated = true
        return true
    }

    private fun up(): Boolean {
        highlight = (highlight - 1).coerceAtLeast(0)
        navigated = true
        return true
    }

    /** Closes an open list and clears the field; a closed one lets Escape reach the dialog around it. */
    private fun escape(): Boolean {
        val wasOpen = open
        open = false
        query = ""
        return wasOpen
    }
}

@Composable
private fun CodeField(
    picker: CodePicker,
    placeholder: String,
    enabled: Boolean,
    loading: Boolean,
    onKey: (KeyEvent) -> Boolean,
) {
    val palette = mtdPalette()
    val edge by animateColorAsState(if (picker.focused) palette.accent else palette.border, label = "codeEdge")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = FieldHeight)
            .clip(FieldShape)
            .background(if (enabled) palette.surface else palette.surface3)
            .border(1.dp, edge, FieldShape)
            .padding(start = 12.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.weight(1f)) {
            if (picker.query.isEmpty()) {
                ZillitText(text = placeholder, style = mtdText(13.5.sp), color = palette.muted, maxLines = 1)
            }
            BasicTextField(
                value = picker.query,
                onValueChange = picker::type,
                enabled = enabled,
                singleLine = true,
                textStyle = mtdText(13.5.sp, FontWeight.SemiBold, mono = true).copy(color = palette.ink),
                cursorBrush = SolidColor(palette.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { picker.focus(it.isFocused) }
                    .onPreviewKeyEvent(onKey),
            )
        }
        if (loading) {
            ZillitSpinner(size = 13.dp, color = palette.muted)
        } else {
            ZillitIcon(icon = ZillitIcons.ChevronDown, tint = palette.muted, size = 14.dp)
        }
    }
}

private enum class ListState { Loading, Failed, EmptyChart, Ready }

@Composable
private fun CodeList(
    ranked: List<CoaCode>,
    highlight: Int,
    picked: List<String>,
    unknownCode: String?,
    state: ListState,
    onPick: (String) -> Unit,
) {
    val palette = mtdPalette()
    when (state) {
        ListState.Loading -> ListNote(str(S.desktop_dm_loading_chart_of_accounts))
        ListState.Failed -> ListNote(str(S.desktop_tax_coa_failed), palette.red)
        ListState.EmptyChart, ListState.Ready -> {
            ranked.forEachIndexed { index, code ->
                CodeRow(code = code, highlighted = index == highlight, picked = code.code in picked) {
                    onPick(code.code)
                }
            }
            if (unknownCode != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .pickOnPress { onPick(unknownCode) }
                        .padding(horizontal = 11.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ZillitText(
                        text = unknownCode,
                        style = mtdText(12.sp, FontWeight.SemiBold, mono = true),
                        color = palette.accentText,
                    )
                    ZillitText(
                        text = str(S.desktop_dm_use_code_not_in_chart_of_accounts),
                        style = mtdText(12.sp),
                        color = palette.muted,
                        maxLines = 1,
                    )
                }
            }
            if (ranked.isEmpty() && unknownCode == null) {
                ListNote(
                    if (state == ListState.EmptyChart) {
                        str(S.desktop_dm_this_projects_chart_of_accounts_is_empty)
                    } else {
                        str(S.desktop_dm_type_to_filter)
                    },
                )
            }
        }
    }
}

@Composable
private fun CodeRow(code: CoaCode, highlighted: Boolean, picked: Boolean, onPick: () -> Unit) {
    val palette = mtdPalette()
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(
                when {
                    highlighted -> palette.accentWash
                    hovered -> palette.surface3
                    else -> Color.Transparent
                },
            )
            .hoverable(interaction)
            .pickOnPress(onPick)
            .padding(horizontal = 11.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitText(
            text = code.code,
            style = mtdText(12.sp, if (picked) FontWeight.Bold else FontWeight.Medium, mono = true),
            color = if (picked || highlighted) palette.accentText else palette.ink2,
        )
        ZillitText(
            text = code.name,
            style = mtdText(12.sp),
            color = palette.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (picked) ZillitIcon(icon = ZillitIcons.Check, tint = palette.accentText, size = 13.dp)
    }
}

/**
 * Picks on the press rather than the release, as the web's `onMouseDown` does:
 * the field keeps its focus through the press, and a list that closes when
 * focus moves has not yet closed when the pick lands.
 */
private fun Modifier.pickOnPress(onPick: () -> Unit): Modifier = pointerInput(onPick) {
    detectTapGestures(onPress = { onPick() })
}

@Composable
private fun ListNote(text: String, tint: Color? = null) {
    ZillitText(
        text = text,
        style = mtdText(12.5.sp),
        color = tint ?: mtdPalette().muted,
        modifier = Modifier.padding(horizontal = 11.dp, vertical = 10.dp),
    )
}

/** The chart can run to thousands of rows; the best matches are what a picker is for. */
private const val MAX_ROWS = 80
