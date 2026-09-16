package com.zillit.desktop.feature.cardexpenses.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitAvatar
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitFileBadge
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.cardexpenses.domain.CardHistoryEntry
import com.zillit.desktop.feature.cardexpenses.ui.date

/**
 * A card's or a receipt's audit trail.
 *
 * Drawn as a spine with a node per entry rather than a table: the question it
 * answers is "what happened to this and in what order", and a table of three
 * columns makes the order look like an accident of sorting. Newest first,
 * because the last thing that happened is the thing being asked about.
 */
@Composable
fun CardHistoryTrail(
    entries: List<CardHistoryEntry>,
    modifier: Modifier = Modifier,
    emptyMessage: String = "Nothing has happened to this yet.",
) {
    val colors = ZillitTheme.colors
    if (entries.isEmpty()) {
        ZillitText(
            text = emptyMessage,
            style = ZillitTheme.typography.bodySmall,
            color = colors.textMuted,
            modifier = modifier,
        )
        return
    }

    Column(modifier = modifier.fillMaxWidth()) {
        entries.forEachIndexed { index, entry ->
            Row(modifier = Modifier.fillMaxWidth()) {
                // The spine: a node for this entry, and a rule down to the
                // next one. The last entry has no rule, so the trail visibly
                // ends rather than trailing off.
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.width(NODE_COLUMN),
                ) {
                    Spacer(Modifier.height(NODE_OFFSET))
                    Box(
                        Modifier.size(NODE_SIZE).clip(CircleShape)
                            .background(if (index == 0) colors.accent else colors.borderStrong),
                    )
                    if (index != entries.lastIndex) {
                        Box(Modifier.width(SPINE_WIDTH).height(SPINE_HEIGHT).background(colors.border))
                    }
                }
                Column(
                    modifier = Modifier.weight(1f).padding(bottom = ZillitTheme.spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
                ) {
                    ZillitText(
                        text = entry.action.humanised(),
                        style = ZillitTheme.typography.bodyMedium,
                    )
                    ZillitText(
                        text = listOfNotNull(
                            date(entry.at).takeIf { it != "—" },
                            entry.note?.takeIf { it.isNotBlank() },
                        ).joinToString(" · ").ifBlank { "—" },
                        style = ZillitTheme.typography.bodySmall,
                        color = colors.textSecondary,
                    )
                }
            }
        }
    }
}

/**
 * The file attached to something, or the button that attaches one.
 *
 * One control for both states: an attached file is shown with the badge that
 * says what kind it is and a way to take it off again, and an empty slot is
 * the button that fills it. Splitting them into "Attach" and "Attached: x.pdf"
 * left people hunting for how to replace the wrong file.
 */
@Composable
fun AttachmentSlot(
    fileName: String?,
    onPick: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    busy: Boolean = false,
    label: String = "Attach receipt",
    disabledHint: String = "This build cannot open a file picker.",
) {
    val colors = ZillitTheme.colors
    if (fileName.isNullOrBlank()) {
        Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
            ZillitButton(
                text = label,
                onClick = onPick,
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Upload,
                enabled = enabled && !busy,
                loading = busy,
            )
            if (!enabled) {
                ZillitText(
                    text = disabledHint,
                    style = ZillitTheme.typography.bodySmall,
                    color = colors.textMuted,
                )
            }
        }
        return
    }

    Row(
        modifier = modifier
            .clip(ZillitTheme.shapes.medium)
            .background(colors.surfaceSunken)
            .padding(horizontal = ZillitTheme.spacing.sm, vertical = ZillitTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitFileBadge(fileName = fileName)
        ZillitText(
            text = fileName,
            style = ZillitTheme.typography.bodySmall,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        ZillitButton(
            text = "Remove",
            onClick = onClear,
            variant = ButtonVariant.Tertiary,
            size = ButtonSize.Small,
        )
    }
}

/**
 * A person in a table cell: their picture and their name together.
 *
 * The avatar's colour is derived from the name, so one person is the same
 * colour in every list and every session — an identity cue that survives a
 * truncated name, which a narrow holder column will always produce.
 */
@Composable
fun PersonCell(name: String, modifier: Modifier = Modifier, userId: String? = null) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        // An em dash is "nobody could be named", not a person — drawing initials
        // for it would invent one.
        if (name != EM_DASH) ZillitAvatar(name = name, userId = userId, size = CELL_AVATAR)
        ZillitText(
            text = name,
            style = ZillitTheme.typography.bodyMedium,
            maxLines = 1,
        )
    }
}

/** A label and a figure on one line, the way a statement reads. */
@Composable
fun DetailLine(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    emphasised: Boolean = false,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitText(
            text = label,
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textSecondary,
            modifier = Modifier.weight(1f),
        )
        ZillitText(
            text = value,
            style = if (emphasised) ZillitTheme.typography.titleSmall else ZillitTheme.typography.numeric,
            maxLines = 1,
        )
    }
}

/** A form section's caption, above the fields it introduces. */
@Composable
fun FieldGroupLabel(text: String, modifier: Modifier = Modifier) {
    ZillitText(
        text = text.uppercase(),
        style = ZillitTheme.typography.labelSmall,
        color = ZillitTheme.colors.textMuted,
        modifier = modifier.padding(top = ZillitTheme.spacing.xs),
    )
}

/** The padding every scrolling detail pane uses, so they line up with each other. */
val DetailPanePadding = PaddingValues(20.dp)

/**
 * `submit_for_approval` → "Submit for approval".
 *
 * The trail's actions arrive as wire tokens, and showing those as-is is how a
 * screen ends up saying "bs_code_updated" at somebody.
 */
private fun String.humanised(): String = trim()
    .replace('_', ' ')
    .replace('-', ' ')
    .ifBlank { "Updated" }
    .replaceFirstChar { it.uppercase() }

private const val EM_DASH = "—"
private val CELL_AVATAR = 22.dp
private val NODE_COLUMN = 22.dp
private val NODE_SIZE = 8.dp
private val NODE_OFFSET = 5.dp
private val SPINE_WIDTH = 1.dp
private val SPINE_HEIGHT = 28.dp
