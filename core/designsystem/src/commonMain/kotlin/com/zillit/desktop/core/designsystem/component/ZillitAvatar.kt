package com.zillit.desktop.core.designsystem.component

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
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
import com.zillit.desktop.core.designsystem.ZillitTheme
import kotlin.math.absoluteValue

/**
 * A person as a circle: their profile picture when one loaded, their initials
 * until then — and instead, when they never uploaded one.
 *
 * The picture comes one of two ways: handed in as [image] by a caller that
 * already has it, or fetched here by [userId] through the [LocalAvatarLoader]
 * in scope. Initials are only ever the fallback — never what a screen shows
 * because it forgot to ask.
 *
 * The initials' colour is derived from the name, so one person is the same
 * colour in every list, every session — an identity cue, not decoration.
 */
@Composable
fun ZillitAvatar(
    name: String,
    modifier: Modifier = Modifier,
    size: Dp = AVATAR_SIZE,
    image: ImageBitmap? = null,
    userId: String? = null,
) {
    val cleaned = name.trim()
    val background = avatarHue(cleaned)
    val shown = image ?: rememberAvatar(userId)

    Box(
        modifier = modifier.size(size).clip(CircleShape).background(background),
        contentAlignment = Alignment.Center,
    ) {
        if (shown != null) {
            Image(
                bitmap = shown,
                contentDescription = null,
                modifier = Modifier.size(size),
                contentScale = ContentScale.Crop,
            )
        } else {
            ZillitText(
                text = cleaned.initials(),
                style = ZillitTheme.typography.labelSmall.copy(
                    fontSize = AVATAR_TEXT,
                    fontWeight = FontWeight.SemiBold,
                ),
                color = Color.White,
            )
        }
    }
}

/**
 * The identity hue [ZillitAvatar] gives [name] — for accents that should match
 * it, like a card's edge strip. One name, one colour, everywhere.
 */
fun avatarHue(name: String): Color =
    AVATAR_HUES[name.trim().hashCode().absoluteValue % AVATAR_HUES.size]

/** First letters of the first two words — "Aisha Khan" → "AK", "" → "?". */
private fun String.initials(): String {
    val letters = split(' ')
        .filter { it.isNotBlank() }
        .take(2)
        .map { it.first().uppercaseChar() }
    return if (letters.isEmpty()) "?" else letters.joinToString("")
}

/**
 * Muted, dark-text-free hues that hold up on both themes. Deliberately not
 * the accent orange — an avatar must not look like a button.
 */
private val AVATAR_HUES = listOf(
    Color(0xFF6D28D9),
    Color(0xFF0E7490),
    Color(0xFFBE185D),
    Color(0xFF047857),
    Color(0xFFB45309),
    Color(0xFF4338CA),
    Color(0xFF9F1239),
    Color(0xFF365314),
)

private val AVATAR_SIZE = 36.dp
private val AVATAR_TEXT = 13.sp
