package com.zillit.desktop.core.workspace.forward

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitAvatar
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitLazyColumn
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * The one forward picker, shared by every surface that can forward — the
 * desktop's `ForwardMsgModal`.
 *
 * The web's modal is a vertical menu of destination *kinds*, each opening its
 * own list or modal. One list is better here: a desktop dialog has the room,
 * and the question "where does this go" is answered once, with search across
 * everything and any number of destinations ticked. Entries that must ask
 * something first ([ForwardStep] — a tool's form, the mailbox composer) stay
 * click-through, as they are on the web, and a [ForwardGroup] opens its
 * children in place, which is what the web's submenus do.
 */
@Composable
@Suppress("LongParameterList", "LongMethod") // The picker: search, sections, the counter, the empty note.
fun ForwardPickerDialog(
    visible: Boolean,
    /** What is being forwarded, in a line — the words, the file's name, the place. */
    subtitle: String?,
    sections: List<ForwardSection>,
    onSend: (targetIds: List<String>) -> Unit,
    onStep: (targetId: String) -> Unit,
    onDismiss: () -> Unit,
    /** Resets the ticks whenever what is being forwarded changes. */
    resetKey: Any? = null,
) {
    var query by remember(resetKey) { mutableStateOf("") }
    var picked by remember(resetKey) { mutableStateOf(emptySet<String>()) }
    var opened by remember(resetKey) { mutableStateOf(emptySet<String>()) }
    val shown = remember(sections, query) { sections.merged().matching(query) }

    ZillitDialogShell(
        title = str(S.desktop_email_forward_to),
        subtitle = subtitle,
        icon = ZillitIcons.Forward,
        visible = visible,
        onDismiss = onDismiss,
        width = PICKER_WIDTH,
        scrollable = false,
        actions = {
            ZillitText(
                text = if (picked.isEmpty()) "" else str(S.dd_n_selected, picked.size),
                style = ZillitTheme.typography.labelSmall,
                color = ZillitTheme.colors.textMuted,
            )
            Spacer(Modifier.weight(1f))
            ZillitButton(
                text = str(S.cancel),
                onClick = onDismiss,
                variant = ButtonVariant.Secondary,
            )
            ZillitButton(
                text = str(S.send),
                enabled = picked.isNotEmpty(),
                onClick = { onSend(picked.toList()) },
            )
        },
    ) {
        ZillitSearchField(
            value = query,
            onValueChange = { query = it },
            placeholder = str(S.desktop_forward_search),
            modifier = Modifier.fillMaxWidth(),
        )
        val listState = rememberLazyListState()
        ZillitLazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().heightIn(max = PICKER_LIST_HEIGHT),
        ) {
            shown.forEach { section ->
                item(key = "h-${section.title}") { SectionHeading(section.title) }
                // Flattened, so an open list of two hundred crew is two
                // hundred lazy items rather than one item that draws them
                // all whether they are on screen or not.
                section.entries.flatMap { entry ->
                    // A search opens every list it matched inside: leaving
                    // them shut would hide the very rows the query found.
                    entry.rows(open = { it in opened || query.isNotBlank() })
                }.forEach { row ->
                    item(key = "e-${row.entry.id}") {
                        EntryRow(
                            entry = row.entry,
                            picked = picked,
                            opened = row.opened,
                            onToggle = { id -> picked = picked.toggled(id) },
                            onOpen = { opened = opened.toggled(row.entry.id) },
                            onStep = onStep,
                            indent = row.indent,
                        )
                    }
                }
            }
            if (shown.isEmpty()) {
                item(key = "empty") {
                    ZillitText(
                        text = str(S.desktop_forward_nothing_matches),
                        style = ZillitTheme.typography.bodySmall,
                        color = ZillitTheme.colors.textMuted,
                        modifier = Modifier.padding(ZillitTheme.spacing.sm),
                    )
                }
            }
        }
    }
}

/** A section's heading, in the margin's grey. */
@Composable
private fun SectionHeading(label: String) {
    ZillitText(
        text = label.uppercase(),
        style = ZillitTheme.typography.labelSmall,
        color = ZillitTheme.colors.textMuted,
        modifier = Modifier.padding(top = ZillitTheme.spacing.sm, bottom = ZillitTheme.spacing.xxs),
    )
}

/** One destination: a tick-box, a hand-off, or a heading that opens its own. */
@Composable
private fun EntryRow(
    entry: ForwardEntry,
    picked: Set<String>,
    opened: Boolean,
    onToggle: (String) -> Unit,
    onOpen: () -> Unit,
    onStep: (String) -> Unit,
    indent: Int = 0,
) {
    when (entry) {
        is ForwardPick -> DestinationRow(
            label = entry.label,
            caption = entry.caption,
            icon = entry.icon,
            avatarUserId = entry.avatarUserId,
            indent = indent,
            onClick = { onToggle(entry.id) },
            leading = {
                ZillitCheckbox(checked = entry.id in picked, onCheckedChange = { onToggle(entry.id) })
            },
        )
        is ForwardStep -> DestinationRow(
            label = entry.label,
            caption = entry.caption,
            icon = entry.icon,
            avatarUserId = null,
            indent = indent,
            onClick = { onStep(entry.id) },
            trailing = {
                ZillitIcon(
                    icon = ZillitIcons.ChevronRight,
                    contentDescription = null,
                    tint = ZillitTheme.colors.textMuted,
                    size = ROW_GLYPH,
                )
            },
        )
        is ForwardGroup -> DestinationRow(
            label = entry.label,
            caption = entry.caption,
            icon = entry.icon,
            avatarUserId = null,
            indent = indent,
            onClick = onOpen,
            trailing = {
                ZillitIcon(
                    icon = if (opened) ZillitIcons.ChevronDown else ZillitIcons.ChevronRight,
                    contentDescription = null,
                    tint = ZillitTheme.colors.textMuted,
                    size = ROW_GLYPH,
                )
            },
        )
    }
}

/** One line of the flattened list: what to draw, how far in, and whether it is open. */
private class ForwardRow(val entry: ForwardEntry, val indent: Int, val opened: Boolean)

/**
 * An entry as the rows it draws: itself, plus an open group's children one
 * step in. Flattening here is what keeps the list lazy — see its call site.
 */
private fun ForwardEntry.rows(open: (String) -> Boolean, indent: Int = 0): List<ForwardRow> {
    if (this !is ForwardGroup) return listOf(ForwardRow(this, indent, opened = false))
    val isOpen = open(id)
    val head = ForwardRow(this, indent, opened = isOpen)
    if (!isOpen) return listOf(head)
    return listOf(head) + children.flatMap { it.rows(open, indent + 1) }
}

/**
 * The row every entry wears: the tick-box first, then a face or a glyph, then
 * the words, and a chevron on the ones that lead somewhere.
 *
 * The box leads rather than trails. Trailing it — which is where the web's
 * user list keeps it — left every short name with a hand's width of nothing
 * between it and its box, and the column of boxes read as a separate list;
 * "where does this go" is answered by a column of ticks down the left.
 */
@Composable
@Suppress("LongParameterList") // One slot per thing a row can carry.
private fun DestinationRow(
    label: String,
    caption: String?,
    icon: androidx.compose.ui.graphics.vector.ImageVector?,
    avatarUserId: String?,
    indent: Int,
    onClick: () -> Unit,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.small)
            .clickable(onClick = onClick)
            .padding(
                start = ZillitTheme.spacing.xs + (INDENT_STEP * indent),
                // Room on the right for the list's scrollbar, which otherwise
                // sits on top of the row's own chevron.
                end = ZillitTheme.spacing.md,
                top = ZillitTheme.spacing.xs,
                bottom = ZillitTheme.spacing.xs,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        // A row with nothing to tick keeps the space anyway, so every name in
        // the list starts on the same line.
        Box(Modifier.width(LEADING_SLOT), contentAlignment = Alignment.Center) { leading?.invoke() }
        RowGlyph(label = label, avatarUserId = avatarUserId, icon = icon)
        Column(Modifier.weight(1f)) {
            ZillitText(text = label, style = ZillitTheme.typography.bodySmall, maxLines = 1)
            if (caption != null) {
                ZillitText(
                    text = caption,
                    style = ZillitTheme.typography.labelSmall,
                    color = ZillitTheme.colors.textMuted,
                    maxLines = 1,
                )
            }
        }
        trailing?.invoke()
    }
}

/**
 * Sections sharing a heading become one.
 *
 * Every destination that is a board contributes under the same heading —
 * this board's own units, then each other board's — and a heading per board
 * over a row apiece is a list nobody can read. The order of first appearance
 * stands: the surface's own destinations stay at the top, where they are
 * what most forwards want.
 */
internal fun List<ForwardSection>.merged(): List<ForwardSection> {
    val byTitle = LinkedHashMap<String, MutableList<ForwardEntry>>()
    forEach { section -> byTitle.getOrPut(section.title) { mutableListOf() } += section.entries }
    return byTitle.map { (title, entries) -> ForwardSection(title, entries) }
}

/**
 * What leads a row: a face, or a glyph on a disc of the same size.
 *
 * A disc rather than a bare glyph so every row's left edge is the same
 * shape — beside the crew's round faces, a loose 18pt outline read as a
 * different kind of thing, and the list looked like two lists.
 */
@Composable
private fun RowGlyph(label: String, avatarUserId: String?, icon: ImageVector?) {
    if (avatarUserId != null) {
        ZillitAvatar(name = label, userId = avatarUserId, size = ROW_AVATAR)
        return
    }
    Box(
        modifier = Modifier
            .size(ROW_AVATAR)
            .clip(CircleShape)
            .background(ZillitTheme.colors.surfaceSunken),
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) {
            ZillitIcon(
                icon = icon,
                contentDescription = null,
                tint = ZillitTheme.colors.textMuted,
                size = ROW_GLYPH,
            )
        }
    }
}

/**
 * The search, applied to the whole tree: a section keeps the entries whose
 * words match, and a group survives if it or any of its children do.
 */
internal fun List<ForwardSection>.matching(query: String): List<ForwardSection> {
    val needle = query.trim()
    if (needle.isEmpty()) return filter { it.entries.isNotEmpty() }
    return mapNotNull { section ->
        val kept = section.entries.mapNotNull { it.matching(needle) }
        if (kept.isEmpty()) null else section.copy(entries = kept)
    }
}

private fun ForwardEntry.matching(needle: String): ForwardEntry? = when (this) {
    is ForwardGroup -> {
        if (label.hits(needle)) {
            this
        } else {
            children.mapNotNull { it.matching(needle) }
                .takeIf { it.isNotEmpty() }
                ?.let { copy(children = it) }
        }
    }
    is ForwardPick -> takeIf { label.hits(needle) || caption?.hits(needle) == true }
    is ForwardStep -> takeIf { label.hits(needle) || caption?.hits(needle) == true }
}

private fun String.hits(needle: String): Boolean = contains(needle, ignoreCase = true)

private fun Set<String>.toggled(id: String): Set<String> = if (id in this) this - id else this + id

private val PICKER_WIDTH = 480.dp
private val PICKER_LIST_HEIGHT = 420.dp
private val ROW_AVATAR = 28.dp
private val ROW_GLYPH = 16.dp
private val INDENT_STEP = 20.dp
private val LEADING_SLOT = 20.dp
