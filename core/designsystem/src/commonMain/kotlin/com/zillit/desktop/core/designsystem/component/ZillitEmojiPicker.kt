package com.zillit.desktop.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.rememberWheelScroll
import com.zillit.desktop.core.designsystem.component.zillitHorizontalScroll

/**
 * An emoji palette: category tabs over a grid, click to pick.
 *
 * A curated set rather than the full Unicode inventory — three hundred emoji
 * people actually send, not three thousand to scroll past. The host decides
 * where the popup lives; this is only its contents.
 */
@Composable
fun ZillitEmojiPicker(
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var category by remember { mutableStateOf(EmojiCategory.entries.first()) }

    Column(
        modifier = modifier.width(PICKER_WIDTH),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .zillitHorizontalScroll(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
        ) {
            EmojiCategory.entries.forEach { tab ->
                Box(
                    modifier = Modifier
                        .clip(ZillitTheme.shapes.small)
                        .background(
                            if (tab == category) {
                                ZillitTheme.colors.accentSoft
                            } else {
                                ZillitTheme.colors.surface
                            },
                        )
                        .clickable { category = tab }
                        .padding(
                            horizontal = ZillitTheme.spacing.sm,
                            vertical = ZillitTheme.spacing.xxs,
                        ),
                ) {
                    // The tab is its own first emoji — a picture beats a word
                    // in the space a tab strip allows.
                    ZillitText(text = tab.emoji.first(), style = ZillitTheme.typography.bodyMedium)
                }
            }
        }

        EmojiGrid(category, onPick)
    }
}

/** One category's emoji, in a grid the wheel can actually get through. */
@Composable
private fun EmojiGrid(category: EmojiCategory, onPick: (String) -> Unit) {
    val gridState = rememberLazyGridState()
    LazyVerticalGrid(
        columns = GridCells.Adaptive(CELL),
        state = gridState,
        modifier = Modifier
            .fillMaxWidth()
            .height(GRID_HEIGHT)
            .then(rememberWheelScroll(gridState)),
    ) {
        items(category.emoji, key = { it }) { emoji ->
            Box(
                modifier = Modifier
                    .size(CELL)
                    .clip(ZillitTheme.shapes.small)
                    .clickable { onPick(emoji) },
                contentAlignment = Alignment.Center,
            ) {
                ZillitText(
                    text = emoji,
                    style = ZillitTheme.typography.bodyLarge.copy(fontSize = EMOJI_SIZE),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** The palette, grouped the way every picker groups it. */
enum class EmojiCategory(val emoji: List<String>) {
    Smileys(
        listOf(
            "😀", "😃", "😄", "😁", "😆", "😅", "😂", "🤣", "🙂", "😉",
            "😊", "😇", "🥰", "😍", "🤩", "😘", "😋", "😜", "🤪", "🤗",
            "🤔", "🤨", "😐", "😑", "🙄", "😬", "😴", "🤒", "🤕", "🥳",
            "😎", "🤓", "😟", "🙁", "😢", "😭", "😤", "😠", "🤯", "😳",
            "🥺", "😰", "😓", "🤝", "🙏", "💪", "👏", "🙌",
        ),
    ),
    Gestures(
        listOf(
            "👍", "👎", "👌", "🤌", "✌️", "🤞", "🤟", "🤘", "👈", "👉",
            "👆", "👇", "☝️", "✋", "🤚", "🖐️", "🖖", "👋", "🤙", "✊",
            "👊", "🫶", "💅", "🫡", "🤷", "🤦", "💁", "🙋",
        ),
    ),
    Hearts(
        listOf(
            "❤️", "🧡", "💛", "💚", "💙", "💜", "🖤", "🤍", "🤎", "💔",
            "❣️", "💕", "💞", "💓", "💗", "💖", "💘", "💝", "💯", "✨",
            "⭐", "🌟", "💫", "🔥", "🎉", "🎊", "🎈", "🏆",
        ),
    ),
    Work(
        listOf(
            "🎬", "🎥", "📷", "📸", "🎞️", "📽️", "🎙️", "🎧", "💡", "🔦",
            "📋", "📄", "📝", "✏️", "📌", "📎", "🗂️", "📁", "📅", "⏰",
            "⏱️", "🕐", "📞", "💻", "🖥️", "🔋", "🔌", "🚚", "🚐", "⚠️",
            "🚧", "🛠️", "🔧", "🎭", "🎨", "👗", "💄", "🎵",
        ),
    ),
    Food(
        listOf(
            "☕", "🍵", "🥤", "🍺", "🥂", "🍕", "🍔", "🍟", "🌭", "🌮",
            "🥪", "🍿", "🍩", "🍪", "🎂", "🍰", "🍫", "🍎", "🍌", "🍉",
            "🥗", "🍜", "🍱", "🍚", "🥡", "🧁", "🥨", "🥐",
        ),
    ),
    Nature(
        listOf(
            "☀️", "🌤️", "⛅", "🌧️", "⛈️", "🌩️", "❄️", "🌪️", "🌈", "🌙",
            "🌊", "🏔️", "🌲", "🌴", "🌵", "🌸", "🌹", "🌻", "🍀", "🍂",
            "🐶", "🐱", "🐴", "🦅", "🐍", "🦋", "🐝", "🐘",
        ),
    ),
    Symbols(
        listOf(
            "✅", "❌", "❓", "❗", "‼️", "⁉️", "💬", "💭", "🔴", "🟠",
            "🟡", "🟢", "🔵", "🟣", "⚫", "⚪", "▶️", "⏸️", "⏹️", "🔁",
            "🔀", "➡️", "⬅️", "⬆️", "⬇️", "🔝", "🆗", "🆕", "🚫", "♻️",
        ),
    ),
}

private val PICKER_WIDTH = 320.dp
private val GRID_HEIGHT = 240.dp
private val CELL = 36.dp
private val EMOJI_SIZE = 20.sp
