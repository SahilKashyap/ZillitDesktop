package com.zillit.desktop.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.AppLanguage
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.Strings
import com.zillit.desktop.core.strings.str

/**
 * The language switch: a globe that opens the list of languages the app
 * ships, with "follow the system" at the top.
 *
 * [selected] is the stored preference — a language code, or blank for
 * "follow the system" — and [onSelect] receives the same shape back, so the
 * caller writes what it was given straight to the preference. What is
 * actually on screen (the OS language, when the preference is blank) is read
 * from [Strings], which is the one place that knows.
 *
 * Every language is named in itself — `Deutsch`, `日本語` — because that is
 * the only name its speaker is guaranteed to recognise, with the English name
 * beside it for whoever is helping them. Sits next to the theme toggle in the
 * app bar and inside Settings › Appearance; both hand it the same preference.
 */
@Composable
fun ZillitLanguageMenu(
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val current = Strings.language

    Box(modifier) {
        ZillitTooltip(text = str(S.desktop_language) + " · " + current.nativeName) {
            ZillitIconButton(
                icon = ZillitIcons.Globe,
                contentDescription = str(S.desktop_language),
                onClick = { expanded = true },
            )
        }
        ZillitMenuSurface(expanded = expanded, onDismissRequest = { expanded = false }) {
            Column(
                Modifier
                    .widthIn(min = MENU_WIDTH)
                    .heightIn(max = MENU_MAX_HEIGHT)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = MENU_INSET),
            ) {
                LanguageRow(
                    title = str(S.desktop_language_system_short),
                    detail = str(S.desktop_language_system),
                    checked = selected.isBlank(),
                ) {
                    expanded = false
                    onSelect("")
                }
                ZillitMenuDivider()
                AppLanguage.all.forEach { language ->
                    LanguageRow(
                        title = language.nativeName,
                        detail = language.englishName.takeIf { it != language.nativeName },
                        checked = selected.isNotBlank() && AppLanguage.byCode(selected) == language,
                    ) {
                        expanded = false
                        onSelect(language.code)
                    }
                }
            }
        }
    }
}

@Composable
private fun LanguageRow(title: String, detail: String?, checked: Boolean, onClick: () -> Unit) {
    val colors = ZillitTheme.colors
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(ROW_RADIUS))
            .background(if (hovered) colors.accentSoft else Color.Transparent)
            .hoverable(source)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = ROW_PADDING_X, vertical = ROW_PADDING_Y),
        horizontalArrangement = Arrangement.spacedBy(ROW_GAP),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(CHECK_SLOT), contentAlignment = Alignment.Center) {
            if (checked) {
                ZillitIcon(
                    icon = ZillitIcons.Check,
                    contentDescription = null,
                    tint = colors.accent,
                    size = CHECK_GLYPH,
                )
            }
        }
        ZillitText(
            text = title,
            style = ZillitTheme.typography.bodyMedium.copy(
                fontWeight = if (checked) FontWeight.SemiBold else FontWeight.Medium,
            ),
            color = colors.textPrimary,
            maxLines = 1,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (detail != null) {
            ZillitText(
                text = detail,
                style = ZillitTheme.typography.bodySmall,
                color = colors.textMuted,
                maxLines = 1,
            )
        }
    }
}

private val MENU_WIDTH = 260.dp
private val MENU_MAX_HEIGHT = 480.dp
private val MENU_INSET = 6.dp
private val ROW_RADIUS = 8.dp
private val ROW_PADDING_X = 10.dp
private val ROW_PADDING_Y = 6.dp
private val ROW_GAP = 10.dp
private val CHECK_SLOT = 20.dp
private val CHECK_GLYPH = 14.dp
