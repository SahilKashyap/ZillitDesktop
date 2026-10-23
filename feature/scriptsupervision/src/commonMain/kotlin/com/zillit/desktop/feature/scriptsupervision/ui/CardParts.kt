package com.zillit.desktop.feature.scriptsupervision.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitText

/**
 * The card-and-grid parts the character and look books are built from — the
 * web's `looks/parts.jsx` (`Field`, `NumberChip`, `EmptyState`) and the
 * `<Row gutter>`/`<Col xs sm md xl>` grid its screens repeat.
 *
 * The web states these as breakpoints per screen; here one grid measures the
 * space it has, because the tool is a window the reader resizes rather than a
 * page at a device width.
 */

/**
 * Cards in as many columns as the width allows, each column at least
 * [minWidth] wide.
 *
 * The web's own counts at its breakpoints (four up on a wide screen, three at
 * `xl`, two at `md`, one on a phone) come out of the same arithmetic at the
 * widths those breakpoints name, so the two read the same without a second
 * table of numbers here.
 */
@Composable
fun <T> CardGrid(items: List<T>, minWidth: Dp = CARD_MIN, card: @Composable (T) -> Unit) {
    if (items.isEmpty()) return
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val gap = ZillitTheme.spacing.md
        val columns = ((maxWidth + gap) / (minWidth + gap)).toInt().coerceAtLeast(1)
        val cell = (maxWidth - gap * (columns - 1)) / columns
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(gap),
            verticalArrangement = Arrangement.spacedBy(gap),
        ) {
            items.forEach { item ->
                Column(Modifier.width(cell)) { card(item) }
            }
        }
    }
}

/**
 * A labelled block inside a card (ALIASES, DESCRIPTION, LOCATION …) — the
 * web's `Field`, which draws NOTHING for an empty value rather than a heading
 * over a blank.
 */
@Composable
fun Field(label: String, value: String, modifier: Modifier = Modifier) {
    if (value.isBlank()) return
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        ZillitText(
            text = label.uppercase(),
            style = ZillitTheme.typography.labelSmall,
            color = ZillitTheme.colors.textSecondary,
        )
        ZillitText(text = value, style = ZillitTheme.typography.bodySmall)
    }
}

/**
 * The scene / department chip: a small caption over a big value (SCENE 12,
 * HAIR #3) — the web's `NumberChip`.
 */
@Composable
fun NumberChip(caption: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .width(CHIP_WIDTH),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        ZillitText(
            text = caption.uppercase(),
            style = ZillitTheme.typography.labelSmall,
            color = ZillitTheme.colors.textSecondary,
        )
        ZillitText(
            text = value,
            style = ZillitTheme.typography.titleSmall,
            maxLines = 1,
        )
    }
}

/** A card's heading row: a title, its tags, and the row's actions at the end. */
@Composable
fun CardTitleRow(
    title: String,
    modifier: Modifier = Modifier,
    tags: (@Composable FlowRowShim.() -> Unit)? = null,
    actions: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitText(
            text = title,
            style = ZillitTheme.typography.titleSmall,
            maxLines = 1,
            modifier = Modifier.weight(1f, fill = false),
        )
        tags?.let {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                modifier = Modifier.weight(1f),
            ) { FlowRowShim.it() }
        }
        actions?.invoke()
    }
}

/** The receiver [CardTitleRow]'s tags get; see [FlowRowScopeShim] for why it is an object. */
object FlowRowShim

/**
 * A count in a sentence — the web's `plural(t, base, n)`.
 *
 * The service counts in three forms and the catalogue carries all three
 * (`<base>_none`, `_one`, `_other`), because "0 characters" and "1 character"
 * are different sentences in most of the languages this app ships in. `{n}`
 * and `{name}` are filled as the web fills them.
 */
fun count(base: String, n: Int, name: String = ""): String {
    val form = when (n) {
        // Not every base HAS a zero form: the web's `scenes_with` is one/other
        // only, and reads "0 scenes with RAVI" out of the other form. Asking
        // for a row that is not there would print the key itself on screen.
        0 -> if (SsText.containsKey("${base}_none")) "none" else "other"
        1 -> "one"
        else -> "other"
    }
    return t("${base}_$form", "n" to n, "name" to name)
}

private val CARD_MIN = 300.dp
private val CHIP_WIDTH = 58.dp
