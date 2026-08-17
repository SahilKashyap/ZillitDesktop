package com.zillit.desktop.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.ZillitTheme

/**
 * A file, as its extension on a coloured page.
 *
 * Every other Zillit client shows attachments this way, and for good reason:
 * on a production a PDF call sheet, an XLSX budget and a MOV dailies clip are
 * different *kinds* of thing, and one grey paperclip for all three makes the
 * list unreadable. The colour says the family, the letters say exactly what it
 * is — and unlike a raster icon set, this is drawn, so it stays sharp at any
 * scale and follows the theme.
 */
@Composable
fun ZillitFileBadge(
    fileName: String,
    modifier: Modifier = Modifier,
    size: Dp = BADGE_HEIGHT,
) {
    val extension = fileName.extension()
    val family = FileFamily.of(extension)

    Box(
        modifier = modifier
            .height(size)
            .width(size * BADGE_ASPECT)
            .clip(ZillitTheme.shapes.small)
            .background(family.tint),
        contentAlignment = Alignment.Center,
    ) {
        ZillitText(
            text = extension.takeIf { it.isNotBlank() }?.uppercase()?.take(MAX_LETTERS) ?: "FILE",
            style = ZillitTheme.typography.labelSmall.copy(
                fontSize = size.value.times(TEXT_RATIO).sp,
                fontWeight = FontWeight.Bold,
            ),
            color = Color.White,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
    }
}

/** The extension, lowercase and without its dot; empty when there is none. */
private fun String.extension(): String =
    substringAfterLast('.', "").takeIf { it.length in 1..MAX_EXTENSION }?.lowercase().orEmpty()

/**
 * What kind of file this is, as a colour.
 *
 * Grouped by what people do with them rather than by MIME tree: a `.xlsx` and
 * a `.csv` are the same errand, and they should read the same at a glance.
 */
private enum class FileFamily(val tint: Color) {
    Document(DOCUMENT_RED),
    Text(TEXT_BLUE),
    Sheet(SHEET_GREEN),
    Slides(SLIDES_ORANGE),
    Image(IMAGE_VIOLET),
    Video(VIDEO_PINK),
    Audio(AUDIO_TEAL),
    Archive(ARCHIVE_AMBER),
    Other(OTHER_SLATE),
    ;

    companion object {
        fun of(extension: String): FileFamily = when (extension) {
            "pdf" -> Document
            "doc", "docx", "rtf", "txt", "md", "pages" -> Text
            "xls", "xlsx", "csv", "numbers" -> Sheet
            "ppt", "pptx", "key" -> Slides
            "jpg", "jpeg", "png", "gif", "webp", "heic", "bmp", "svg" -> Image
            "mp4", "mov", "avi", "mkv", "webm", "m4v" -> Video
            "mp3", "wav", "aac", "m4a", "flac", "ogg" -> Audio
            "zip", "rar", "7z", "tar", "gz" -> Archive
            else -> Other
        }
    }
}

// Deep enough to carry white lettering in either theme, and far enough
// apart that a PDF is never mistaken for a spreadsheet at a glance.
private val DOCUMENT_RED = Color(0xFFB91C1C)
private val TEXT_BLUE = Color(0xFF1D4ED8)
private val SHEET_GREEN = Color(0xFF047857)
private val SLIDES_ORANGE = Color(0xFFC2410C)
private val IMAGE_VIOLET = Color(0xFF6D28D9)
private val VIDEO_PINK = Color(0xFFBE185D)
private val AUDIO_TEAL = Color(0xFF0E7490)
private val ARCHIVE_AMBER = Color(0xFFB45309)
private val OTHER_SLATE = Color(0xFF475569)

private val BADGE_HEIGHT = 22.dp
private const val BADGE_ASPECT = 1.25f
private const val TEXT_RATIO = 0.42f
private const val MAX_LETTERS = 4
private const val MAX_EXTENSION = 5
