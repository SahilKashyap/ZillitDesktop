package com.zillit.desktop.feature.taxfiling.ui.pages

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.taxfiling.domain.TaxFiling
import com.zillit.desktop.feature.taxfiling.ui.CatalogState
import com.zillit.desktop.feature.taxfiling.ui.TaxFilingEvent
import com.zillit.desktop.feature.taxfiling.ui.components.CardShape
import com.zillit.desktop.feature.taxfiling.ui.components.MtdCard
import com.zillit.desktop.feature.taxfiling.ui.components.MtdIconTile
import com.zillit.desktop.feature.taxfiling.ui.components.MtdSkeletonRows
import com.zillit.desktop.feature.taxfiling.ui.components.mtdPalette
import com.zillit.desktop.feature.taxfiling.ui.components.mtdText

/**
 * The filings the service offers — the web's `TaxFilingHub`.
 *
 * One card per filing; filings for the countries the production's companies
 * are in come first under "Your countries", the rest under "Other countries",
 * and a single plain grid when only one group has anything.
 */
@Composable
internal fun CatalogPage(catalog: CatalogState, onEvent: (TaxFilingEvent) -> Unit) {
    PageTitle(
        icon = ZillitIcons.Hierarchy,
        title = str(S.desktop_tax_filing),
        description = str(S.desktop_tax_catalog_description),
    )
    val open: (TaxFiling) -> Unit = { onEvent(TaxFilingEvent.OpenFiling(it)) }
    when {
        catalog.loading -> MtdCard(padding = 0.dp, modifier = Modifier.fillMaxWidth()) { MtdSkeletonRows() }
        catalog.filings.isEmpty() -> CatalogEmpty()
        catalog.grouped -> {
            SectionLabel(str(S.desktop_tax_your_countries))
            CardGrid(catalog.mine, open)
            Spacer(Modifier.height(26.dp))
            SectionLabel(str(S.desktop_tax_other_countries))
            CardGrid(catalog.others, open)
        }
        else -> CardGrid(catalog.mine.ifEmpty { catalog.others }, open)
    }
}

@Composable
private fun SectionLabel(text: String) {
    val palette = mtdPalette()
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ZillitText(
            text = text.uppercase(),
            style = mtdText(12.sp, FontWeight.Bold, tracking = 0.04.em),
            color = palette.muted,
        )
        Box(Modifier.weight(1f).height(1.dp).background(palette.divider))
    }
}

/** `repeat(auto-fill, minmax(300px, 1fr))`, with every card in a row the same height. */
@Composable
private fun CardGrid(filings: List<TaxFiling>, onOpen: (TaxFiling) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = ((maxWidth + GAP) / (MIN_CARD + GAP)).toInt().coerceAtLeast(1)
        Column(verticalArrangement = Arrangement.spacedBy(GAP)) {
            filings.chunked(columns).forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max),
                    horizontalArrangement = Arrangement.spacedBy(GAP),
                ) {
                    row.forEach { filing ->
                        FilingCard(
                            filing = filing,
                            onOpen = { onOpen(filing) },
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

private val GAP = 16.dp
private val MIN_CARD = 300.dp

/** A filing's card: the flag, the country and title, its description, and "Open →". */
@Composable
private fun FilingCard(filing: TaxFiling, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val palette = mtdPalette()
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val elevation by animateDpAsState(if (hovered) 10.dp else 1.dp, label = "filingLift")
    val edge by animateColorAsState(if (hovered) palette.border2 else palette.border, label = "filingEdge")
    Column(
        modifier = modifier
            .shadow(elevation, CardShape, clip = false, ambientColor = Shadow, spotColor = Shadow)
            .clip(CardShape)
            .background(palette.surface)
            .border(1.dp, edge, CardShape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClickLabel = str(S.desktop_drive_open_item, filing.title),
                role = Role.Button,
                onClick = onOpen,
            ),
    ) {
        FilingCardHead(filing)
        ZillitText(
            text = filing.description,
            style = mtdText(13.5.sp),
            color = palette.ink3,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
        )
        Spacer(Modifier.weight(1f))
        Box(Modifier.fillMaxWidth().height(1.dp).background(palette.divider))
        FilingCardFooter(filing)
    }
}

@Composable
private fun FilingCardHead(filing: TaxFiling) {
    val palette = mtdPalette()
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FlagTile(filing)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            ZillitText(
                text = filing.countryName.uppercase(),
                style = mtdText(11.5.sp, FontWeight.Bold, tracking = 0.04.em),
                color = palette.muted,
                maxLines = 1,
            )
            ZillitText(
                text = filing.title,
                style = mtdText(16.5.sp, FontWeight.Bold, tracking = (-0.02).em),
                color = palette.ink,
                maxLines = 1,
            )
            if (filing.subtitle.isNotBlank()) {
                ZillitText(text = filing.subtitle, style = mtdText(12.5.sp), color = palette.ink3, maxLines = 1)
            }
        }
    }
}

/** The regime on the left, "Open →" on the right, on the card's tinted foot. */
@Composable
private fun FilingCardFooter(filing: TaxFiling) {
    val palette = mtdPalette()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.surface2)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitText(
            text = filing.regime,
            style = mtdText(12.5.sp, FontWeight.SemiBold),
            color = palette.ink3,
            modifier = Modifier.weight(1f),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            ZillitText(text = str(S.recce_open), style = mtdText(13.sp, FontWeight.Bold), color = palette.accentText)
            ZillitIcon(icon = ZillitIcons.ArrowRight, tint = palette.accentText, size = 14.dp)
        }
    }
}

private val Shadow = Color.Black.copy(alpha = 0.18f)

/** The flag the catalogue sends; the country code when there is none to show. */
@Composable
private fun FlagTile(filing: TaxFiling) {
    val palette = mtdPalette()
    val shape = RoundedCornerShape(13.dp)
    Box(
        modifier = Modifier
            .size(46.dp)
            .clip(shape)
            .background(palette.surface2)
            .border(1.dp, palette.border, shape),
        contentAlignment = Alignment.Center,
    ) {
        val flag = filing.flag.ifBlank { filing.country.ifBlank { "—" } }
        ZillitText(
            text = flag,
            style = mtdText(if (filing.flag.isBlank()) 13.sp else 24.sp, FontWeight.Bold, lineHeight = 28.sp),
            color = palette.ink2,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun CatalogEmpty() {
    val palette = mtdPalette()
    MtdCard(modifier = Modifier.fillMaxWidth(), padding = 0.dp) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 62.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            MtdIconTile(icon = ZillitIcons.Building, size = 84.dp, iconSize = 40.dp, radius = 24.dp)
            Spacer(Modifier.height(22.dp))
            ZillitText(
                text = str(S.desktop_tax_no_filings_available),
                style = mtdText(20.sp, FontWeight.Bold, tracking = (-0.02).em),
                color = palette.ink,
            )
            Spacer(Modifier.height(8.dp))
            ZillitText(
                text = str(S.desktop_tax_no_filings_detail),
                style = mtdText(14.5.sp),
                color = palette.ink3,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 440.dp),
            )
        }
    }
}
