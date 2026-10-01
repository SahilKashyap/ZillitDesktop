package com.zillit.desktop.feature.costumesetsync.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitActionMenu
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitMenuEntry
import com.zillit.desktop.core.designsystem.component.ZillitMenuTone
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.costumesetsync.domain.Rec
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

private const val AVATAR_SIZE = 36

/** The characters in cast-number order; those with no number follow alphabetically (the web's `sortByCast`). */
internal fun castOrder(list: List<Rec>): List<Rec> =
    list.sortedWith(
        compareBy<Rec> { if (it.has("cast_number")) it.long("cast_number") else Long.MAX_VALUE }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.str("name") },
    )

/** `AK` for "Arjun Kapoor" — the first letters of the first two words. */
internal fun nameInitials(name: String): String =
    name.split(Regex("\\s+")).filter { it.isNotEmpty() }.take(2).joinToString("") { it.first().uppercase() }

/** Blue (`--hub-blue`) text that navigates or opens something: the web's `csync-linkbtn`. */
@Composable
internal fun CastLink(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, bold: Boolean = false) {
    ZillitText(
        text,
        modifier.clickable(onClick = onClick),
        style = ZillitTheme.typography.bodyMedium.copy(fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal),
        color = ZillitTheme.colors.info,
        maxLines = 1,
    )
}

/** The round badge on a character row: its cast number, or the initials of an actor's name. */
@Composable
internal fun RowBadge(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier.size(AVATAR_SIZE.dp).clip(CircleShape).background(ZillitTheme.colors.accentSoft),
        contentAlignment = Alignment.Center,
    ) {
        ZillitText(
            text.ifBlank { "—" },
            style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = ZillitTheme.colors.accentText,
            maxLines = 1,
        )
    }
}

/** A row's "⋮" button and the menu it opens. */
@Composable
internal fun RowMenu(entries: List<ZillitMenuEntry>) {
    var open by remember { mutableStateOf(false) }
    Box {
        ZillitButton("", onClick = { open = true }, variant = ButtonVariant.Tertiary, size = ButtonSize.Small, leadingIcon = ZillitIcons.MoreVertical)
        ZillitActionMenu(
            expanded = open,
            onDismissRequest = { open = false },
            entries = entries.map { e ->
                if (e is ZillitMenuEntry.Action) e.copy(onClick = { open = false; e.onClick() }) else e
            },
        )
    }
}

/** A menu row, shorthand. */
internal fun menuAction(label: String, danger: Boolean = false, onClick: () -> Unit): ZillitMenuEntry =
    ZillitMenuEntry.Action(label, tone = if (danger) ZillitMenuTone.Danger else ZillitMenuTone.Neutral, onClick = onClick)

/**
 * A JSON body that keeps what [com.zillit.desktop.feature.costumesetsync.ui.body] drops: a blank string is sent
 * as typed (so an emptied field is cleared) and `null` is an explicit JSON null (so a cleared picker unsets).
 */
internal fun castBody(vararg fields: Pair<String, Any?>): JsonObject = buildJsonObject {
    fields.forEach { (key, value) ->
        when (value) {
            null -> put(key, JsonNull)
            is Boolean -> put(key, JsonPrimitive(value))
            is Number -> put(key, JsonPrimitive(if (value is Double && value % 1.0 == 0.0) value.toLong() else value))
            else -> put(key, JsonPrimitive(value.toString()))
        }
    }
}
