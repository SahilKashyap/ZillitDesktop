package com.zillit.desktop.feature.email.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.ZillitColors
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.copyTextToClipboard
import com.zillit.desktop.feature.email.domain.BodySpan
import com.zillit.desktop.feature.email.domain.BodyStyle
import com.zillit.desktop.feature.email.domain.contrastingTextOn
import com.zillit.desktop.feature.email.domain.isWebUrl
import com.zillit.desktop.feature.email.domain.vanishesOnTheme

/**
 * A mail body as native text: the spans `htmlToSpans` produced, drawn with the
 * theme's type and colours.
 *
 * ## What the theme decides, and what the sender does
 *
 * Android renders the body in a WebView with a body colour picked by theme
 * (`EmailTrailAdapter.kt:426-483`: `#E6E6E6` at night, `#1A1A1A` by day) and
 * links in the accent orange. The same split here: the sender's colours are
 * honoured, but a colour that would vanish against this theme's background —
 * black text from a light-mode composer, read in dark mode — falls back to the
 * theme's text, which is what made "the colour I set shows as black" invisible.
 * Links always take the accent, so they read in both modes.
 *
 * ## Links
 *
 * Clickable, through [onOpenLink]. Only web links go there; anything else —
 * `file:`, custom schemes — is copied to the clipboard instead, because a
 * mail body is untrusted content and `Desktop.browse` will hand any URI to
 * the OS.
 */
@Composable
internal fun HtmlBody(
    spans: List<BodySpan>,
    onOpenLink: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ZillitTheme.colors
    val style = ZillitTheme.typography.bodyMedium
    val open by rememberUpdatedState(onOpenLink)

    // Rebuilt on a theme change, not on every recomposition: a long
    // newsletter is thousands of spans.
    val text = remember(spans, colors.isDark, style) {
        spans.toAnnotatedString(BodyPalette(colors, style.fontSize)) { href ->
            if (href.isWebUrl()) open(href) else copyTextToClipboard(href)
        }
    }

    ZillitText(text = text, style = style, color = colors.textPrimary, modifier = modifier)
}

/** What the renderer needs from the theme, gathered so the builder is a plain function. */
internal class BodyPalette(
    val isDark: Boolean,
    val textPrimary: Color,
    val textMuted: Color,
    val accent: Color,
    val baseSize: TextUnit,
) {
    constructor(colors: ZillitColors, baseSize: TextUnit) : this(
        isDark = colors.isDark,
        textPrimary = colors.textPrimary,
        textMuted = colors.textMuted,
        accent = colors.accent,
        baseSize = baseSize,
    )
}

/**
 * The spans as one string.
 *
 * Quoted blocks become indented paragraphs. A paragraph boundary is itself a
 * line break, so the newline that opens a block after the first is dropped —
 * otherwise every quote sits under a doubled blank line.
 */
internal fun List<BodySpan>.toAnnotatedString(
    palette: BodyPalette,
    onLinkClick: (String) -> Unit,
): AnnotatedString = buildAnnotatedString {
    var isFirstBlock = true
    quoteBlocks().forEach { (depth, block) ->
        val spans = if (isFirstBlock) block else block.dropLeadingBreak()
        isFirstBlock = false

        if (depth == 0) {
            appendSpans(spans, palette, onLinkClick)
        } else {
            val indent = QUOTE_INDENT * depth
            withStyle(ParagraphStyle(textIndent = TextIndent(firstLine = indent, restLine = indent))) {
                appendSpans(spans, palette, onLinkClick)
            }
        }
    }
}

/** Runs of consecutive spans at one quote depth, in order. */
private fun List<BodySpan>.quoteBlocks(): List<Pair<Int, List<BodySpan>>> {
    val blocks = mutableListOf<Pair<Int, MutableList<BodySpan>>>()
    forEach { span ->
        val depth = span.style.quoteDepth
        val last = blocks.lastOrNull()
        if (last != null && last.first == depth) last.second += span else blocks += depth to mutableListOf(span)
    }
    return blocks
}

private fun List<BodySpan>.dropLeadingBreak(): List<BodySpan> {
    val head = firstOrNull() ?: return this
    if (!head.text.startsWith('\n')) return this
    val trimmed = head.copy(text = head.text.drop(1))
    return if (trimmed.text.isEmpty()) drop(1) else listOf(trimmed) + drop(1)
}

private fun AnnotatedString.Builder.appendSpans(
    spans: List<BodySpan>,
    palette: BodyPalette,
    onLinkClick: (String) -> Unit,
) {
    spans.forEach { span ->
        val href = span.href
        if (href == null) {
            withStyle(span.style.toSpanStyle(palette)) { append(span.text) }
        } else {
            // The link's own colour wins over an inline one, as Android's
            // `a { color: #FC9404 }` does: a link has to look like a link.
            val link = LinkAnnotation.Clickable(
                tag = href,
                styles = TextLinkStyles(SpanStyle(color = palette.accent, textDecoration = TextDecoration.Underline)),
                linkInteractionListener = { onLinkClick(href) },
            )
            withLink(link) { withStyle(span.style.toSpanStyle(palette)) { append(span.text) } }
        }
    }
}

private fun BodyStyle.toSpanStyle(palette: BodyPalette): SpanStyle = SpanStyle(
    color = textColor(palette),
    background = background?.toColor() ?: Color.Unspecified,
    fontWeight = if (bold) FontWeight.Bold else null,
    fontStyle = if (italic) FontStyle.Italic else null,
    textDecoration = decoration(),
    fontFamily = if (monospace) FontFamily.Monospace else null,
    fontSize = if (sizeScale == 1f) TextUnit.Unspecified else palette.baseSize * sizeScale,
)

/**
 * The colour a run is drawn in.
 *
 * An explicit colour stands unless it would vanish on this theme — and only
 * then when the run has no background of its own, because black on a yellow
 * highlight is fine in either mode. A highlight without a colour takes a
 * contrasting text so the theme's light text never lands on a light highlight.
 * Quoted text is muted, like the web's `.email-body blockquote { color: #666 }`.
 */
private fun BodyStyle.textColor(palette: BodyPalette): Color {
    val explicit = color
    return when {
        explicit != null && background != null -> explicit.toColor() ?: Color.Unspecified
        explicit != null && !vanishesOnTheme(explicit, palette.isDark) -> explicit.toColor() ?: Color.Unspecified
        background != null -> contrastingTextOn(checkNotNull(background)).toColor() ?: Color.Unspecified
        quoteDepth > 0 -> palette.textMuted
        else -> Color.Unspecified
    }
}

private fun BodyStyle.decoration(): TextDecoration? = when {
    underline && strike -> TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough))
    underline -> TextDecoration.Underline
    strike -> TextDecoration.LineThrough
    else -> null
}

/** `#rrggbb` as a Compose colour; null for anything else, rather than a guess. */
internal fun String.toColor(): Color? {
    if (length != HEX_LENGTH || this[0] != '#') return null
    val rgb = substring(1).toLongOrNull(HEX_RADIX) ?: return null
    return Color(OPAQUE or rgb)
}

private val QUOTE_INDENT = 16.sp
private const val HEX_LENGTH = 7
private const val HEX_RADIX = 16
private const val OPAQUE = 0xFF000000L
