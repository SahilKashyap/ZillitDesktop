package com.zillit.desktop.feature.drive.ui.pages

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitChoiceChip
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.drive.domain.DriveItem
import com.zillit.desktop.feature.drive.domain.FolderNode
import com.zillit.desktop.feature.drive.domain.flattened
import com.zillit.desktop.feature.drive.domain.folderTree

/**
 * A folder tree to pick a destination from — `MoveToDialog`'s tree and
 * `DriveDestinationField`'s. Expanded by default; a search keeps each
 * match and its ancestors so the path down to it stays visible.
 *
 * [rootLabel] non-null draws a "Drive (Root)" row above the tree that
 * selects null; the destination field has its own root radio and passes
 * none.
 */
@Composable
@Suppress("LongParameterList", "LongMethod") // One seam per thing the two callers differ on; the tree is one list.
internal fun FolderTree(
    folders: List<DriveItem>,
    selectedId: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
    excluded: Set<String> = emptySet(),
    rootLabel: String? = null,
    searchable: Boolean = true,
    maxHeight: Dp = TREE_MAX_HEIGHT,
) {
    var search by remember { mutableStateOf("") }
    var collapsed by remember { mutableStateOf(setOf<String>()) }
    val colors = ZillitTheme.colors
    val tree = remember(folders, excluded) { folderTree(folders, excluded) }
    val query = search.trim().lowercase()
    val visible = remember(tree, query, collapsed) { tree.visibleRows(query, collapsed) }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
        if (searchable && folders.size > SEARCH_THRESHOLD) {
            ZillitSearchField(value = search, onValueChange = { search = it }, placeholder = "Search folders…")
        }
        val rows = (if (rootLabel != null) 1 else 0) + visible.size
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(ZillitTheme.shapes.medium)
                .border(1.dp, colors.border, ZillitTheme.shapes.medium)
                .background(colors.surface),
        ) {
            if (rows == 0) {
                Box(Modifier.fillMaxWidth().padding(ZillitTheme.spacing.lg), contentAlignment = Alignment.Center) {
                    ZillitText(
                        text = if (query.isEmpty()) "No folders yet" else "No folders match",
                        style = ZillitTheme.typography.bodySmall,
                        color = colors.textMuted,
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.height((ROW_HEIGHT * rows).coerceAtMost(maxHeight))) {
                    if (rootLabel != null) {
                        item(key = "__root__") {
                            TreeRow(
                                label = rootLabel,
                                depth = 0,
                                icon = ZillitIcons.Home,
                                tint = colors.accent,
                                selected = selectedId == null,
                                expandable = false,
                                expanded = true,
                                onToggle = {},
                                onClick = { onSelect(null) },
                            )
                        }
                    }
                    items(visible, key = { it.folder.id }) { node ->
                        TreeRow(
                            label = node.folder.name,
                            depth = node.depth + if (rootLabel != null) 1 else 0,
                            icon = ZillitIcons.Folder,
                            tint = folderColour(node.folder),
                            selected = selectedId == node.folder.id,
                            expandable = node.children.isNotEmpty() && query.isEmpty(),
                            expanded = node.folder.id !in collapsed,
                            onToggle = {
                                val id = node.folder.id
                                collapsed = if (id in collapsed) collapsed - id else collapsed + id
                            },
                            onClick = { onSelect(node.folder.id) },
                        )
                    }
                }
            }
        }
    }
}

/** Depth-first, skipping collapsed subtrees; under a search, matches and their ancestors only. */
private fun List<FolderNode>.visibleRows(query: String, collapsed: Set<String>): List<FolderNode> {
    if (query.isEmpty()) {
        val out = mutableListOf<FolderNode>()
        fun walk(nodes: List<FolderNode>) {
            nodes.forEach { node ->
                out += node
                if (node.folder.id !in collapsed) walk(node.children)
            }
        }
        walk(this)
        return out
    }
    val all = flattened()
    val keep = mutableSetOf<String>()
    fun mark(node: FolderNode): Boolean {
        val childHit = node.children.map(::mark).any { it }
        val hit = childHit || node.folder.name.lowercase().contains(query)
        if (hit) keep += node.folder.id
        return hit
    }
    forEach { mark(it) }
    return all.filter { it.folder.id in keep }
}

@Composable
@Suppress("LongParameterList") // Every visual state of one tree row.
private fun TreeRow(
    label: String,
    depth: Int,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    selected: Boolean,
    expandable: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    onClick: () -> Unit,
) {
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT)
            .background(if (selected) colors.accentSoft else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(start = ZillitTheme.spacing.sm + INDENT * depth, end = ZillitTheme.spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(CHEVRON_SLOT)
                .clip(ZillitTheme.shapes.small)
                .then(if (expandable) Modifier.clickable(onClick = onToggle) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            if (expandable) {
                ZillitIcon(
                    icon = if (expanded) ZillitIcons.ChevronDown else ZillitIcons.ChevronRight,
                    tint = colors.textMuted,
                    size = ZillitTheme.spacing.md,
                )
            }
        }
        ZillitIcon(icon = icon, tint = tint, size = ZillitTheme.spacing.lg)
        ZillitText(
            text = label,
            style = ZillitTheme.typography.bodyMedium,
            color = if (selected) colors.accentText else colors.textPrimary,
            maxLines = 1,
        )
    }
}

/**
 * "Drive root / Inside a folder" with the tree beneath — the
 * `DriveDestinationField` shared by the upload and create-folder drawers.
 * Shown at the root only; inside a folder the destination is that folder.
 */
@Composable
@Suppress("LongParameterList") // Labels differ between the two drawers.
internal fun DestinationField(
    folders: List<DriveItem>,
    pickExisting: Boolean,
    selectedId: String?,
    onChange: (pickExisting: Boolean, folderId: String?) -> Unit,
    title: String,
    rootLabel: String,
    rootHint: String,
    modifier: Modifier = Modifier,
) {
    SheetSection(title = title, modifier = modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
            ZillitChoiceChip(label = rootLabel, selected = !pickExisting, onClick = { onChange(false, null) })
            ZillitChoiceChip(
                label = "Inside a folder",
                selected = pickExisting,
                onClick = { onChange(true, selectedId) },
            )
        }
        if (pickExisting) {
            FolderTree(
                folders = folders,
                selectedId = selectedId,
                onSelect = { onChange(true, it) },
                rootLabel = null,
            )
            if (selectedId == null) {
                ZillitText(
                    text = "Pick a folder above.",
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.warning,
                )
            }
        } else {
            ZillitText(
                text = rootHint,
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textSecondary,
            )
        }
    }
}

private val ROW_HEIGHT = 32.dp
private val INDENT = 18.dp
private val CHEVRON_SLOT = 18.dp
private val TREE_MAX_HEIGHT = 300.dp
private const val SEARCH_THRESHOLD = 8

/** Fills a Row slot of a given width; used to align tree rows without a chevron. */
@Composable
internal fun TreeSpacer(width: Dp) = Box(Modifier.width(width))
