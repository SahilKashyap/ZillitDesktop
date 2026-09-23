package com.zillit.desktop.core.designsystem.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.ZillitDimens
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * A field holding a list of tokens as chips, with whatever is being typed
 * inline after them — the address rows of a mail composer, a tag editor.
 *
 * ## One callback, the whole value
 *
 * Every change — a keystroke, a comma, Enter, a chip's ✕, Backspace on an
 * empty input, focus leaving with text still typed — arrives as
 * [onValueChange] with the *complete* new value: the tokens and the text.
 * Two callbacks ("token added" then "input cleared") would leave the caller
 * reading a stale closure between them, which is how a chip appears twice.
 *
 * ## What commits a token
 *
 * A separator character (comma or semicolon by default), Enter, Tab, and
 * focus leaving the field with text in it — the set Android's composer uses
 * (`bottomNav/new_email/ui/email/ComposeActivity.kt:304-481`: IME Done,
 * hardware Enter/Tab, comma or semicolon typed, and focus loss). Pasting
 * `a, b, c` commits `a` and `b` and leaves `c` being typed, as a browser chip
 * input would.
 *
 * ## Folding
 *
 * While unfocused, more than [collapsedLimit] chips fold into "+N more" — the
 * phone's collapsed row (`collapseField`) — because three stacked address
 * rows each showing twelve chips push the message off the screen. Focusing,
 * or clicking the fold, shows them all.
 */
@Composable
fun ZillitTokenField(
    tokens: List<String>,
    input: String,
    onValueChange: (tokens: List<String>, input: String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    /** What a chip shows for a token — a name for `Name <addr>`, say. */
    tokenLabel: (String) -> String = { it },
    /** Invalid tokens are drawn as error chips rather than dropped: the user typed them. */
    isTokenValid: (String) -> Boolean = { true },
    onFocusChanged: (Boolean) -> Unit = {},
    /** Rides at the trailing edge, inside the border — the To row's Cc/Bcc control. */
    trailingContent: (@Composable () -> Unit)? = null,
    separators: Set<Char> = DEFAULT_SEPARATORS,
    /** Chips shown while unfocused before the rest fold into "+N more"; null never folds. */
    collapsedLimit: Int? = DEFAULT_COLLAPSED_LIMIT,
) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    var focused by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    val borderColor by animateColorAsState(
        when {
            focused -> colors.accent
            hovered -> colors.borderStrong
            else -> colors.border
        },
        label = "tokenFieldBorder",
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = ZillitDimens.controlHeight)
            .clip(ZillitTheme.shapes.medium)
            .background(colors.surface)
            .border(BORDER_WIDTH, borderColor, ZillitTheme.shapes.medium)
            .hoverable(interaction)
            // Anywhere in the field is the field: clicking the empty space
            // to the right of the last chip has to land the caret.
            .pointerInput(Unit) { detectTapGestures { focusRequester.requestFocus() } }
            .padding(horizontal = ZillitTheme.spacing.sm, vertical = ZillitTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        TokenRow(
            value = TokenValue(tokens, input, separators),
            onValueChange = onValueChange,
            placeholder = placeholder,
            tokenLabel = tokenLabel,
            isTokenValid = isTokenValid,
            folded = collapsedLimit != null && !focused && tokens.size > collapsedLimit,
            collapsedLimit = collapsedLimit ?: tokens.size,
            focusRequester = focusRequester,
            onFocus = { isFocused ->
                focused = isFocused
                onFocusChanged(isFocused)
            },
            modifier = Modifier.weight(1f),
        )

        trailingContent?.invoke()
    }
}

/** The chips and the input, wrapping onto as many lines as they need. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TokenRow(
    value: TokenValue,
    onValueChange: (List<String>, String) -> Unit,
    placeholder: String?,
    tokenLabel: (String) -> String,
    isTokenValid: (String) -> Boolean,
    folded: Boolean,
    collapsedLimit: Int,
    focusRequester: FocusRequester,
    onFocus: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Read through state holders so the key handler and the blur commit see
    // the latest value rather than the one they were composed with.
    val latest by rememberUpdatedState(value)
    val change by rememberUpdatedState(onValueChange)
    val shown = if (folded) value.tokens.take(collapsedLimit) else value.tokens

    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        shown.forEachIndexed { index, token ->
            TokenChip(
                label = tokenLabel(token),
                valid = isTokenValid(token),
                onRemove = { change(latest.tokens.filterIndexed { at, _ -> at != index }, latest.input) },
            )
        }
        if (folded) {
            FoldChip(count = value.tokens.size - shown.size, onClick = { focusRequester.requestFocus() })
        }

        TokenInput(
            value = value.input,
            placeholder = placeholder.takeIf { value.tokens.isEmpty() },
            onEdit = { typed -> latest.edited(typed)?.let { (next, rest) -> change(next, rest) } },
            onKey = { event ->
                val handled = latest.keyed(event)
                if (handled != null) change(handled.first, handled.second)
                handled != null
            },
            onFocus = { isFocused ->
                // Leaving with text still typed commits it, as the phone does
                // on focus loss — a half-typed address that quietly vanishes
                // when the user clicks Subject reads as lost.
                if (!isFocused) latest.committed()?.let { (next, rest) -> change(next, rest) }
                onFocus(isFocused)
            },
            focusRequester = focusRequester,
            modifier = Modifier.weight(1f).widthIn(min = INPUT_MIN_WIDTH),
        )
    }
}

/** The tokens and text as one value, with the edits the field can make to it. */
private data class TokenValue(val tokens: List<String>, val input: String, val separators: Set<Char>) {

    /** The value after typing [typed]; null when nothing changed. */
    fun edited(typed: String): Pair<List<String>, String>? {
        if (typed == input) return null
        if (typed.none { it in separators }) return tokens to typed
        // Everything before the last separator becomes chips; the tail is
        // what is still being typed — so a pasted "a, b, c" yields two chips
        // and leaves "c" in the input.
        val cut = typed.indexOfLast { it in separators }
        val pieces = typed.substring(0, cut)
            .split { it in separators }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        return tokens.plusDistinct(pieces) to typed.substring(cut + 1).trimStart()
    }

    /** The value after [event]; null when the key is not one this field handles. */
    fun keyed(event: KeyEvent): Pair<List<String>, String>? {
        if (event.type != KeyEventType.KeyDown) return null
        return when (event.key) {
            Key.Enter, Key.NumPadEnter, Key.Tab -> committed()
            // Backspace on an empty input takes the last chip back, which is
            // the only way to remove one from the keyboard.
            Key.Backspace -> if (input.isEmpty() && tokens.isNotEmpty()) tokens.dropLast(1) to "" else null
            else -> null
        }
    }

    /** The value with the typed text made a token; null when there is nothing to commit. */
    fun committed(): Pair<List<String>, String>? =
        input.trim().takeIf { it.isNotEmpty() }?.let { tokens.plusDistinct(listOf(it)) to "" }
}

/** Splits on any character [isSeparator] accepts — `split(vararg Char)` would copy the array per call. */
private fun String.split(isSeparator: (Char) -> Boolean): List<String> {
    val pieces = mutableListOf<String>()
    var start = 0
    forEachIndexed { index, character ->
        if (isSeparator(character)) {
            pieces += substring(start, index)
            start = index + 1
        }
    }
    pieces += substring(start)
    return pieces
}

/** Case-insensitively new tokens only: committing an address twice is a slip, not a request. */
private fun List<String>.plusDistinct(more: List<String>): List<String> {
    val seen = mapTo(mutableSetOf()) { it.lowercase() }
    return this + more.filter { seen.add(it.lowercase()) }
}

/** The inline text input: no border of its own, the field's border is around everything. */
@Composable
private fun TokenInput(
    value: String,
    placeholder: String?,
    onEdit: (String) -> Unit,
    onKey: (KeyEvent) -> Boolean,
    onFocus: (Boolean) -> Unit,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    val colors = ZillitTheme.colors

    // The caret has to land at the end when the text is replaced from outside
    // — after a commit clears it, or a suggestion fills it — the same mirror
    // ZillitTextField keeps for the same reason.
    var fieldValue by remember { mutableStateOf(TextFieldValue(value)) }
    if (fieldValue.text != value) fieldValue = TextFieldValue(value, TextRange(value.length))

    Box(modifier = modifier, contentAlignment = Alignment.CenterStart) {
        if (value.isEmpty() && placeholder != null) {
            ZillitText(placeholder, style = ZillitTheme.typography.bodyMedium, color = colors.textMuted)
        }
        BasicTextField(
            value = fieldValue,
            onValueChange = { next ->
                fieldValue = next
                onEdit(next.text)
            },
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .onFocusChanged { onFocus(it.isFocused) }
                .onPreviewKeyEvent(onKey),
            singleLine = true,
            textStyle = ZillitTheme.typography.bodyMedium.copy(color = colors.textPrimary),
            cursorBrush = SolidColor(colors.accent),
        )
    }
}

/**
 * One token: an initial in a coloured circle, the label, and its ✕.
 *
 * The phone's chip (`item_recipient_chip`: avatar initial, name, close). Invalid
 * ones go red rather than disappearing — the user typed the thing, and a chip
 * that silently vanishes on a typo is worse than one that shows the typo.
 */
@Composable
private fun TokenChip(label: String, valid: Boolean, onRemove: () -> Unit) {
    val colors = ZillitTheme.colors
    val initial = label.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"

    Row(
        modifier = Modifier
            .clip(ZillitTheme.shapes.pill)
            .background(if (valid) colors.surfaceSunken else colors.dangerSoft)
            .border(BORDER_WIDTH, if (valid) colors.border else colors.danger, ZillitTheme.shapes.pill)
            .padding(ZillitTheme.spacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        Box(
            modifier = Modifier
                .size(CHIP_CIRCLE)
                .clip(CircleShape)
                .background(if (valid) avatarHue(label) else colors.danger),
            contentAlignment = Alignment.Center,
        ) {
            ZillitText(
                text = initial,
                style = ZillitTheme.typography.labelSmall.copy(
                    fontSize = CHIP_INITIAL,
                    fontWeight = FontWeight.SemiBold,
                ),
                color = Color.White,
                maxLines = 1,
            )
        }
        ZillitText(
            text = label,
            style = ZillitTheme.typography.labelSmall,
            color = if (valid) colors.textSecondary else colors.danger,
            maxLines = 1,
        )
        Box(
            modifier = Modifier
                .size(CHIP_CIRCLE)
                .clip(CircleShape)
                .clickable(onClickLabel = str(S.bs_chip_remove, label), onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) {
            ZillitIcon(
                icon = ZillitIcons.Close,
                contentDescription = str(S.bs_chip_remove, label),
                tint = if (valid) colors.textMuted else colors.danger,
                size = CHIP_CLOSE,
            )
        }
    }
}

/** "+3 more" — the fold the phone's collapsed row shows; clicking it unfolds. */
@Composable
private fun FoldChip(count: Int, onClick: () -> Unit) {
    val colors = ZillitTheme.colors
    ZillitText(
        text = str(S.desktop_n_more, count),
        style = ZillitTheme.typography.labelSmall,
        color = colors.textSecondary,
        modifier = Modifier
            .clip(ZillitTheme.shapes.pill)
            .background(colors.surfaceHover)
            .clickable(onClick = onClick)
            .padding(horizontal = ZillitTheme.spacing.sm, vertical = ZillitTheme.spacing.xs),
    )
}

private val DEFAULT_SEPARATORS = setOf(',', ';')
private const val DEFAULT_COLLAPSED_LIMIT = 3

private val BORDER_WIDTH = 1.dp
private val CHIP_CIRCLE = 18.dp
private val CHIP_CLOSE = 12.dp
private val CHIP_INITIAL = 10.sp
private val INPUT_MIN_WIDTH = 96.dp
