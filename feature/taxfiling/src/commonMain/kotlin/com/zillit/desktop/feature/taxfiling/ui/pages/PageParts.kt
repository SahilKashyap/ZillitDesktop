package com.zillit.desktop.feature.taxfiling.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.taxfiling.ui.TaxFilingUiState
import com.zillit.desktop.feature.taxfiling.ui.components.MtdIconTile
import com.zillit.desktop.feature.taxfiling.ui.components.mtdPalette
import com.zillit.desktop.feature.taxfiling.ui.components.mtdText

/** A page's title block: the icon tile, the title and a line of description. */
@Composable
internal fun PageTitle(icon: ImageVector, title: String, description: String) {
    val palette = mtdPalette()
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 26.dp, bottom = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MtdIconTile(icon = icon, size = 52.dp, iconSize = 24.dp)
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            ZillitText(
                text = title,
                style = mtdText(29.sp, FontWeight.ExtraBold, tracking = (-0.03).em, lineHeight = 31.sp),
                color = palette.ink,
            )
            ZillitText(text = description, style = mtdText(14.5.sp), color = palette.ink3)
        }
    }
}

/**
 * Said once, at the top, when this machine cannot be described to HMRC.
 *
 * The two calls that reach the authority are refused in that state, and an
 * accountant deserves to know that before they map seven boxes.
 */
@Composable
internal fun AuthorityWarning(state: TaxFilingUiState) {
    if (state.canReachAuthority) return
    val palette = mtdPalette()
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 18.dp)
            .clip(shape)
            .background(palette.redWash)
            .border(1.dp, palette.redBorder, shape)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitIcon(icon = ZillitIcons.Warning, tint = palette.red, size = 16.dp)
        ZillitText(
            text = str(S.desktop_tax_no_machine_details_banner),
            style = mtdText(13.sp, FontWeight.Medium, tracking = 0.em),
            color = palette.ink,
        )
    }
}
