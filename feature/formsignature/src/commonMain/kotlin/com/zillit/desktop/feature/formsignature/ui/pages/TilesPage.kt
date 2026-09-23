@file:Suppress("MagicNumber") // Tile geometry, named below.

package com.zillit.desktop.feature.formsignature.ui.pages

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ZillitBadge
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTooltip
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.formsignature.ui.FormSignScreen
import com.zillit.desktop.feature.formsignature.ui.FormSignatureEvent
import com.zillit.desktop.feature.formsignature.ui.FormSignatureUiState

private data class Tile(
    val title: String,
    val hint: String,
    val icon: ImageVector,
    val screen: FormSignScreen,
    val badge: Int,
)

/**
 * The hub — the web's `ContractSignatureTiles`: the visible tiles sorted by
 * title, a badge and an info tooltip on each, and the admin's guide link.
 */
@Composable
internal fun TilesPage(state: FormSignatureUiState, onEvent: (FormSignatureEvent) -> Unit) {
    val tiles = buildList {
        if (state.showsStandardTile) {
            add(
                Tile(
                    title = str(S.standard_forms),
                    hint = str(S.standard_forms),
                    icon = ZillitIcons.File,
                    screen = FormSignScreen.StandardDocuments,
                    badge = state.unread.standardForms,
                ),
            )
        }
        if (state.showsDocumentsTile) {
            add(
                Tile(
                    title = str(S.douments_for_sign_txt),
                    hint = str(S.desktop_fs_upload_documents_hint),
                    icon = ZillitIcons.Signature,
                    screen = FormSignScreen.DocumentsForSignature,
                    badge = state.unread.documents,
                ),
            )
        }
        add(
            Tile(
                title = str(S.set_signature_edit),
                hint = str(S.set_signature_edit),
                icon = ZillitIcons.Edit,
                screen = FormSignScreen.SignatureBlock,
                badge = 0,
            ),
        )
    }.sortedBy { it.title }

    Column(
        modifier = Modifier.fillMaxSize().padding(ZillitTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
        ) {
            tiles.forEach { tile ->
                HubTile(tile, modifier = Modifier.weight(1f)) { onEvent(FormSignatureEvent.Open(tile.screen)) }
            }
        }
        if (state.viewer.isAdmin) {
            Box(Modifier.fillMaxWidth().padding(top = ZillitTheme.spacing.md), contentAlignment = Alignment.Center) {
                ZillitButton(
                    text = str(S.desktop_fs_guide_button),
                    onClick = { onEvent(FormSignatureEvent.OpenGuide) },
                    size = ButtonSize.Small,
                    trailingIcon = ZillitIcons.ChevronRight,
                )
            }
        }
    }
}

@Composable
private fun HubTile(tile: Tile, modifier: Modifier = Modifier, onOpen: () -> Unit) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val border by animateColorAsState(if (hovered) colors.accent else colors.border, label = "tileBorder")
    Row(
        modifier = modifier
            .widthIn(min = 260.dp)
            .shadow(if (hovered) 8.dp else 1.dp, ZillitTheme.shapes.large)
            .clip(ZillitTheme.shapes.large)
            .background(colors.surface)
            .border(1.dp, border, ZillitTheme.shapes.large)
            .hoverable(interaction)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onOpen)
            .padding(horizontal = 24.dp, vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        Box(
            modifier = Modifier.size(40.dp).clip(CircleShape).background(colors.accentSoft),
            contentAlignment = Alignment.Center,
        ) {
            ZillitIcon(tile.icon, tint = colors.accent)
        }
        ZillitText(
            text = tile.title,
            style = ZillitTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
            maxLines = 2,
        )
        if (tile.badge > 0) ZillitBadge(count = tile.badge)
        ZillitTooltip(tile.hint) {
            ZillitIcon(ZillitIcons.Info, tint = colors.textMuted, size = 18.dp)
        }
        ZillitIcon(ZillitIcons.ChevronRight, tint = colors.textSecondary)
    }
}
