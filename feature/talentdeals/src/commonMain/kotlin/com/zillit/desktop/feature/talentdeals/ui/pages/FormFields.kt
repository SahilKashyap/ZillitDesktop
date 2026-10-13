package com.zillit.desktop.feature.talentdeals.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.talentdeals.domain.TalentFileSlot

/** A form field: the label (with the amber star when required), the control, and its error under it. */
@Composable
internal fun FormField(
    label: String,
    modifier: Modifier = Modifier,
    required: Boolean = false,
    error: String? = null,
    content: @Composable () -> Unit,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        Row {
            ZillitText(
                text = label,
                style = ZillitTheme.typography.label.copy(fontWeight = FontWeight.SemiBold),
                color = ZillitTheme.colors.textSecondary,
            )
            if (required) ZillitText(" *", style = ZillitTheme.typography.label, color = ZillitTheme.colors.danger)
        }
        content()
        if (error != null) FieldError(error)
    }
}

@Composable
internal fun FieldError(text: String) {
    ZillitText(text, style = ZillitTheme.typography.bodySmall, color = ZillitTheme.colors.danger,
        maxLines = Int.MAX_VALUE)
}

/** A titled section of the form's card: a sunken title strip, then the body. */
@Composable
internal fun FormSection(
    title: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val colors = ZillitTheme.colors
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().background(colors.surfaceSunken).padding(horizontal = ZillitTheme.spacing.xl,
                vertical = ZillitTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitText(
                text = title.uppercase(),
                style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = colors.accentText,
                modifier = Modifier.weight(1f),
            )
            action?.invoke()
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
        Column(
            Modifier.fillMaxWidth().padding(ZillitTheme.spacing.xl),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
        ) { content() }
    }
}

/** A nested group under a checkbox, indented behind a hairline. */
@Composable
internal fun Nested(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(start = ZillitTheme.spacing.xl, top = ZillitTheme.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) { content() }
}

/**
 * An attachment that sits INLINE with the other fields, so it keeps to a
 * single control height rather than opening a drop area the size of a card.
 *
 * [file] is EITHER a file picked just now OR a stored envelope when the form is
 * editing a saved contract. Both show as "uploaded" — name, Replace, remove —
 * and only a pick carries a size. Empty, it is the SAME row with the prompt
 * where the name goes, so Add and Edit look alike and picking a file only fills
 * the row in.
 */
@Composable
internal fun FileField(
    label: String,
    cta: String,
    file: TalentFileSlot?,
    onPick: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    required: Boolean = false,
    error: String? = null,
) {
    val colors = ZillitTheme.colors
    FormField(label = label, modifier = modifier, required = required, error = error) {
        val borderColor = if (error != null) colors.danger else colors.border
        Row(
            Modifier
                .fillMaxWidth()
                .height(FIELD_HEIGHT)
                .clip(ZillitTheme.shapes.medium)
                .background(colors.surface)
                .border(1.dp, borderColor, ZillitTheme.shapes.medium)
                .then(if (file == null) Modifier.clickable(onClickLabel = cta, onClick = onPick) else Modifier)
                .padding(horizontal = ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitIcon(icon = if (file == null) ZillitIcons.Upload else ZillitIcons.File, size = 16.dp)
            ZillitText(
                text = file?.name ?: cta,
                style = ZillitTheme.typography.bodyMedium,
                color = if (file == null) colors.textMuted else colors.textPrimary,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            if (file == null) {
                ZillitText(str(S.desktop_td_browse), style = ZillitTheme.typography.label, color = colors.accentText)
            } else {
                ZillitText(
                    text = (file as? TalentFileSlot.Picked)?.let { sizeLabel(it.file.size) } ?: str(S.sides_uploaded),
                    style = ZillitTheme.typography.labelSmall,
                    color = colors.textMuted,
                )
                ZillitButton(
                    text = str(S.replace),
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                    onClick = onPick,
                )
                ZillitIconButton(
                    icon = ZillitIcons.Trash,
                    contentDescription = str(S.bs_chip_remove, label.lowercase()),
                    onClick = onRemove,
                    tint = colors.danger,
                )
            }
        }
    }
}

private fun sizeLabel(bytes: Int): String {
    val kb = bytes / BYTES_PER_KB
    if (kb < BYTES_PER_KB) return "${kb.roundToInt()} KB"
    val mb = ((kb / BYTES_PER_KB) * TENTHS).roundToInt() / TENTHS
    return "$mb MB"
}

/** The width of a short field in the form's rows. */
internal val FIELD_WIDTH: Dp = 240.dp
private val FIELD_HEIGHT = 36.dp
private const val BYTES_PER_KB = 1024.0

private const val TENTHS = 10.0
