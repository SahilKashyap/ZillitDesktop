@file:Suppress("MatchingDeclarationName") // The shared cells; ExtTint is one small helper among them.

package com.zillit.desktop.feature.drive.ui.pages

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.common.EpochDate
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitAvatar
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTooltip
import com.zillit.desktop.core.designsystem.component.avatarHue
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.media.decodeImageBitmap
import com.zillit.desktop.feature.drive.domain.DriveItem
import com.zillit.desktop.feature.drive.domain.DriveSection
import com.zillit.desktop.feature.drive.domain.MyDriveFilter
import com.zillit.desktop.feature.drive.domain.PreviewKind
import com.zillit.desktop.feature.drive.domain.isRecent
import com.zillit.desktop.feature.drive.domain.relativeTime
import com.zillit.desktop.feature.drive.ui.DriveEvent
import com.zillit.desktop.feature.drive.ui.DriveUiState

/**
 * The extension badge's palette — `DriveTable.getExtBadge`, one tint per
 * family so a glance tells a PDF from a spreadsheet.
 */
internal data class ExtTint(val background: Color, val content: Color)

@Composable
internal fun extTint(extension: String): ExtTint {
    val c = ZillitTheme.colors
    return when (extension.lowercase()) {
        "pdf" -> ExtTint(c.dangerSoft, c.danger)
        "doc", "docx", "odt", "rtf" -> ExtTint(c.infoSoft, c.info)
        "xls", "xlsx", "csv", "ods" -> ExtTint(c.successSoft, c.success)
        "ppt", "pptx", "odp" -> ExtTint(c.warningSoft, c.warning)
        "mp4", "mov", "avi", "mkv", "webm", "wmv", "flv", "m4v" -> ExtTint(c.violetSoft, c.violet)
        "mp3", "wav", "aac", "flac", "ogg", "m4a", "mpeg" -> ExtTint(c.tealSoft, c.teal)
        "png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "heic", "heif" -> ExtTint(c.accentSoft, c.accent)
        "zip", "rar", "7z" -> ExtTint(c.goldSoft, c.gold)
        else -> ExtTint(c.surfaceSunken, c.textSecondary)
    }
}

/** `PDF` in its family's tint — the web's `drive-ext-badge`. */
@Composable
internal fun ExtBadge(extension: String, modifier: Modifier = Modifier, size: Dp = BADGE_SIZE) {
    val tint = extTint(extension)
    val label = extension.uppercase().ifBlank { "FILE" }.take(MAX_BADGE_CHARS)
    Box(
        modifier = modifier
            .size(size)
            .clip(ZillitTheme.shapes.medium)
            .background(tint.background)
            .border(1.dp, tint.content.copy(alpha = BADGE_BORDER_ALPHA), ZillitTheme.shapes.medium),
        contentAlignment = Alignment.Center,
    ) {
        ZillitText(
            text = label,
            style = ZillitTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Bold,
                fontSize = if (label.length > SHORT_BADGE) 8.sp else 9.sp,
                letterSpacing = 0.3.sp,
            ),
            color = tint.content,
            maxLines = 1,
        )
    }
}

/** A folder glyph in the folder's colour, else the accent — `FolderOutlined` with `folder_color`. */
@Composable
internal fun FolderGlyph(item: DriveItem, size: Dp = ZillitTheme.spacing.xl) {
    ZillitIcon(icon = ZillitIcons.Folder, tint = folderColour(item), size = size)
}

@Composable
internal fun folderColour(item: DriveItem): Color =
    parseHex(item.folderColor) ?: ZillitTheme.colors.accent

/**
 * The name cell's leading picture: a folder glyph, the image's own
 * thumbnail once fetched, or the extension badge. Asks for the thumbnail
 * on first sight — the view model fetches at most a few at once.
 */
@Composable
internal fun ItemGlyph(
    item: DriveItem,
    state: DriveUiState,
    onEvent: (DriveEvent) -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = BADGE_SIZE,
) {
    if (item.isFolder) {
        Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
            FolderGlyph(item, size = size * FOLDER_GLYPH_RATIO)
        }
        return
    }
    val bytes = state.thumbnails[item.id]
    if (bytes == null && item.previewKind == PreviewKind.Image) {
        LaunchedEffect(item.id) { onEvent(DriveEvent.WantThumbnail(item)) }
    }
    val bitmap = remember(bytes) { bytes?.let(::decodeImageBitmap) }
    if (bitmap != null) {
        Thumbnail(bitmap, modifier.size(size))
    } else {
        ExtBadge(item.extension, modifier, size)
    }
}

@Composable
internal fun Thumbnail(bitmap: ImageBitmap, modifier: Modifier = Modifier) {
    Image(
        bitmap = bitmap,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier
            .clip(ZillitTheme.shapes.medium)
            .border(1.dp, ZillitTheme.colors.border, ZillitTheme.shapes.medium),
    )
}

/** "3 days ago" inside the week, a date beyond it — the Date Modified cell. */
internal fun modifiedLabel(millis: Long?, now: Long): String {
    if (millis == null || millis <= 0) return "—"
    return if (now > 0 && isRecent(millis, now)) relativeTime(millis, now) else EpochDate.date(millis)
}

/** "3 days ago" when the clock is known, the exact stamp otherwise — the details panel's dates. */
internal fun stampLabel(millis: Long, now: Long): String =
    if (now > 0) relativeTime(millis, now) else EpochDate.dateTime(millis)

/** The exact stamp a hover shows. */
internal fun exactStamp(millis: Long?): String = EpochDate.dateTime(millis).ifBlank { "—" }

/**
 * The Sharing cell — `DriveTable`'s `sharing` column: for someone else's
 * item, its owner; for mine, the people it is shared with as an avatar
 * group, or "Only you".
 */
@Composable
@Suppress("LongMethod") // Two shapes of one cell: the owner, or the stacked sharees.
internal fun SharingCell(item: DriveItem, state: DriveUiState) {
    val viewer = state.viewer
    if (viewer.isSharedWithMe(item)) {
        val name = item.uploadedByName.ifBlank { "Unknown" }
        Row(
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitAvatar(name = name, userId = item.uploadedById, size = SMALL_AVATAR)
            ZillitText(
                text = name,
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textSecondary,
                maxLines = 1,
            )
        }
        return
    }
    val others = item.accessUserIds.filter { it != viewer.userId }
    if (others.isEmpty()) {
        ZillitText(
            text = "Only you",
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textMuted,
        )
        return
    }
    val people = others.map { id -> id to (state.crew.firstOrNull { it.id == id }?.name ?: "User") }
    val shown = people.take(MAX_STACKED)
    val overflow = people.size - shown.size
    ZillitTooltip(text = people.joinToString(", ") { (_, name) -> name }) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            shown.forEach { (id, name) -> ZillitAvatar(name = name, userId = id, size = SMALL_AVATAR) }
            if (overflow > 0) {
                Box(
                    modifier = Modifier
                        .size(SMALL_AVATAR)
                        .clip(ZillitTheme.shapes.pill)
                        .background(ZillitTheme.colors.surfaceSunken)
                        .border(1.dp, ZillitTheme.colors.border, ZillitTheme.shapes.pill),
                    contentAlignment = Alignment.Center,
                ) {
                    ZillitText(
                        text = "+$overflow",
                        style = ZillitTheme.typography.labelSmall,
                        color = ZillitTheme.colors.textSecondary,
                    )
                }
            }
        }
    }
}

/** A small "shared with you" marker beside a name — `TeamOutlined` in blue. */
@Composable
internal fun SharedWithYouMark() {
    ZillitTooltip(text = "Shared with you") {
        ZillitIcon(icon = ZillitIcons.Users, tint = ZillitTheme.colors.info, size = ZillitTheme.spacing.md)
    }
}

/**
 * The empty listing — `DriveEmptyState`: what it says depends on why it
 * is empty. A search with no hits, a "Shared by me" filter with nothing
 * shared, an empty "Shared with me", or a fresh drive with its two ways to
 * get started.
 */
@Composable
@Suppress("LongMethod", "CyclomaticComplexMethod", "ComplexCondition") // One message per reason it is empty.
internal fun DriveEmptyState(state: DriveUiState, onEvent: (DriveEvent) -> Unit) {
    val searchEmpty = state.isSearching
    val sharedByMeEmpty = state.section == DriveSection.MyDrive && state.myDriveFilter == MyDriveFilter.SharedByMe
    val sharedEmpty = state.section == DriveSection.SharedWithMe
    val (title, message) = when {
        searchEmpty -> "No results found" to "\"${state.search}\" not found"
        sharedEmpty -> "No folders or files have been shared with you yet" to
            "Files and folders shared with you will appear here"
        sharedByMeEmpty -> "You haven't shared any items yet" to "Items you share with others will appear here"
        state.showFavouritesOnly ->
            "No favourites here" to "Star a file or folder and it will show up under this filter"
        state.tagFilterId != null -> "Nothing carries this tag" to "Add the tag to a file or folder from its details"
        else -> "No files or folders yet" to "Drag & drop files here, or use the Upload button to get started"
    }
    val icon = when {
        searchEmpty -> ZillitIcons.Search
        sharedEmpty || sharedByMeEmpty -> ZillitIcons.Users
        else -> ZillitIcons.Inbox
    }
    Column(
        modifier = Modifier.fillMaxSize().padding(ZillitTheme.spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(EMPTY_DISC)
                .clip(ZillitTheme.shapes.pill)
                .background(ZillitTheme.colors.accentSoft),
            contentAlignment = Alignment.Center,
        ) {
            ZillitIcon(icon = icon, tint = ZillitTheme.colors.accent, size = ZillitTheme.spacing.xl)
        }
        ZillitText(
            text = title,
            style = ZillitTheme.typography.titleSmall,
            modifier = Modifier.padding(top = ZillitTheme.spacing.md),
        )
        ZillitText(
            text = message,
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textSecondary,
            modifier = Modifier.padding(top = ZillitTheme.spacing.xs).widthIn(max = EMPTY_TEXT_WIDTH),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        if (!searchEmpty && !sharedByMeEmpty && !sharedEmpty && state.canCreateHere &&
            !state.showFavouritesOnly && state.tagFilterId == null
        ) {
            Row(
                modifier = Modifier.padding(top = ZillitTheme.spacing.md),
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            ) {
                ZillitButton(
                    text = "Upload files",
                    onClick = { onEvent(DriveEvent.OpenUpload) },
                    variant = ButtonVariant.Secondary,
                    leadingIcon = ZillitIcons.Upload,
                )
                ZillitButton(
                    text = "Create folder",
                    onClick = { onEvent(DriveEvent.OpenNewFolder) },
                    variant = ButtonVariant.Secondary,
                    leadingIcon = ZillitIcons.FolderPlus,
                )
            }
        }
    }
}

/** A stable tint for a person, for avatars without a picture. */
@Composable
internal fun personTint(name: String): Color = avatarHue(name)

/** `#f99300` → a colour; null for anything that is not six hex digits. */
internal fun parseHex(hex: String): Color? {
    val digits = hex.trim().removePrefix("#")
    if (digits.length != HEX_LENGTH || digits.any { it.lowercaseChar() !in HEX_DIGITS }) return null
    val value = digits.toLong(HEX_RADIX)
    return Color(
        red = ((value shr RED_SHIFT) and BYTE_MASK).toInt(),
        green = ((value shr GREEN_SHIFT) and BYTE_MASK).toInt(),
        blue = (value and BYTE_MASK).toInt(),
    )
}

/** A checkbox-sized slot the row keeps even when no control is drawn in it. */
@Composable
internal fun SlotSpacer(width: Dp) {
    Box(Modifier.size(width, 1.dp))
}

/** Fills the width; used as a Row's flexible middle. */
@Composable
internal fun Filler(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth())
}

internal val BADGE_SIZE = 36.dp
internal val SMALL_AVATAR = 24.dp
private val EMPTY_DISC = 64.dp
private val EMPTY_TEXT_WIDTH = 360.dp
private const val MAX_STACKED = 3
private const val MAX_BADGE_CHARS = 4
private const val SHORT_BADGE = 3
private const val FOLDER_GLYPH_RATIO = 0.7f
private const val BADGE_BORDER_ALPHA = 0.35f
private const val HEX_LENGTH = 6
private const val HEX_RADIX = 16
private const val HEX_DIGITS = "0123456789abcdef"
private const val RED_SHIFT = 16
private const val GREEN_SHIFT = 8
private const val BYTE_MASK = 0xFFL
