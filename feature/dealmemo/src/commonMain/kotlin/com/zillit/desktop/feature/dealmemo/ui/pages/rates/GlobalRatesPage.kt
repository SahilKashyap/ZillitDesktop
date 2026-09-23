package com.zillit.desktop.feature.dealmemo.ui.pages.rates

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTooltip
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.dealmemo.domain.rates.Region
import com.zillit.desktop.feature.dealmemo.domain.rates.TerritoryCatalogue
import com.zillit.desktop.feature.dealmemo.ui.BackSquare
import com.zillit.desktop.feature.dealmemo.ui.DealMemoEvent
import com.zillit.desktop.feature.dealmemo.ui.DealMemoRoute
import com.zillit.desktop.feature.dealmemo.ui.DealMemoUiState
import com.zillit.desktop.feature.dealmemo.ui.DealTab
import com.zillit.desktop.feature.dealmemo.ui.GlobalRatesState
import com.zillit.desktop.feature.dealmemo.ui.RatesEvent
import com.zillit.desktop.feature.dealmemo.ui.RatesView
import com.zillit.desktop.feature.dealmemo.ui.components.DmButton
import com.zillit.desktop.feature.dealmemo.ui.components.DmButtonStyle
import com.zillit.desktop.feature.dealmemo.ui.components.DmCard
import com.zillit.desktop.feature.dealmemo.ui.components.DmIconTile
import com.zillit.desktop.feature.dealmemo.ui.components.DmIcons
import com.zillit.desktop.feature.dealmemo.ui.components.DmSearchPill
import com.zillit.desktop.feature.dealmemo.ui.components.DmType
import com.zillit.desktop.feature.dealmemo.ui.components.TerritoryFlag
import com.zillit.desktop.feature.dealmemo.ui.components.dm
import com.zillit.desktop.feature.dealmemo.ui.components.rememberHover

/**
 * Global Production Rates — a read-only browser of the union catalogue under
 * its own full-page top bar (`DealMemoModule.jsx:662-741`, `DMConfigPage.jsx`).
 */
@Composable
fun GlobalRatesPage(state: DealMemoUiState, onEvent: (DealMemoEvent) -> Unit) {
    val rates = state.rates
    Column(modifier = Modifier.fillMaxSize().background(dm.page)) {
        RatesTopBar(rates.refreshing, onEvent)
        if (rates.coveredLoading) {
            PageSkeleton()
        } else {
            Row(
                modifier = Modifier.fillMaxSize().padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TerritorySidebar(rates, onEvent)
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    when (rates.view) {
                        RatesView.Welcome -> WelcomeView(rates, onEvent)
                        RatesView.Territory -> TerritoryView(state, onEvent)
                        RatesView.AgreementsList -> TerritoryAgreementsView(rates, onEvent)
                        RatesView.Branch -> BranchView(state, onEvent)
                        RatesView.Agreement -> AgreementDetailView(state, onEvent)
                    }
                }
            }
        }
    }
}

@Composable
private fun RatesTopBar(refreshing: Boolean, onEvent: (DealMemoEvent) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (ZillitTheme.colors.isDark) Color(0xFF14171C) else Color.White)
            .padding(horizontal = 26.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            BackSquare(
                onClick = { onEvent(DealMemoEvent.Navigate(DealMemoRoute.Tab(DealTab.Deals))) },
                size = 36,
                tooltip = str(S.desktop_dm_back_to_all_deals),
            )
            Column {
                ZillitText(
                    text = "CONTRACTS",
                    style = DmType.sans(10.sp, FontWeight.Bold, 0.09.em),
                    color = if (ZillitTheme.colors.isDark) Color(0xFFFBBF24) else Color(0xFFEA7A0E),
                )
                Spacer(Modifier.height(3.dp))
                ZillitText(
                    text = str(S.dm_gpr_title),
                    style = DmType.sans(18.sp, FontWeight.Bold, (-0.02).em).copy(lineHeight = 20.sp),
                    color = dm.ink,
                )
            }
        }
        DmButton(
            text = if (refreshing) str(S.dm_gpr_refreshing) else str(S.dm_gpr_refresh),
            onClick = { onEvent(RatesEvent.Refresh) },
            style = DmButtonStyle.SmallSecondary,
            icon = ZillitIcons.Reload,
            loading = refreshing,
            enabled = !refreshing,
            tooltip = str(S.desktop_dm_re_seed_agreements_unions_designation_rates_from),
        )
    }
    Box(
        Modifier.fillMaxWidth().height(1.dp).background(
            if (ZillitTheme.colors.isDark) Color.White.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.10f),
        ),
    )
}

// -- skeleton ---------------------------------------------------------------------------

@Composable
private fun PageSkeleton() {
    Row(
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        DmCard(
            modifier = Modifier.width(296.dp).padding(top = 12.dp, bottom = 12.dp).fillMaxHeight(),
            radius = 14.dp,
            padding = PaddingValues(12.dp),
        ) {
            Bar(height = 38.dp, radius = 10.dp, color = dm.track)
            Spacer(Modifier.height(12.dp))
            listOf(4, 3).forEach { rows ->
                Bar(width = 90.dp, height = 8.dp)
                Spacer(Modifier.height(10.dp))
                repeat(rows) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.padding(bottom = 10.dp),
                    ) {
                        Bar(width = 20.dp, height = 20.dp)
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Bar(width = 150.dp, height = 11.dp)
                            Bar(width = 50.dp, height = 7.dp)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
        Column(modifier = Modifier.weight(1f).padding(start = 28.dp, top = 20.dp, end = 28.dp)) {
            Bar(width = 320.dp, height = 26.dp)
            Spacer(Modifier.height(12.dp))
            Bar(width = 480.dp, height = 11.dp)
            Spacer(Modifier.height(28.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                repeat(4) {
                    DmCard(modifier = Modifier.weight(1f), radius = 14.dp, padding = PaddingValues(16.dp)) {
                        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                            Bar(width = 44.dp, height = 44.dp, radius = 10.dp, color = dm.soft)
                            Bar(width = 64.dp, height = 22.dp, radius = 11.dp, color = dm.track)
                        }
                        Spacer(Modifier.height(14.dp))
                        Bar(width = 120.dp, height = 13.dp)
                        Spacer(Modifier.height(6.dp))
                        Bar(width = 70.dp, height = 9.dp)
                    }
                }
            }
        }
    }
}

@Composable
internal fun Bar(width: Dp? = null, height: Dp, radius: Dp = 4.dp, color: Color = dm.skeleton) {
    Box(
        (if (width != null) Modifier.width(width) else Modifier.fillMaxWidth())
            .height(height)
            .clip(RoundedCornerShape(radius))
            .background(color),
    )
}

// -- sidebar ---------------------------------------------------------------------------

@Composable
private fun TerritorySidebar(rates: GlobalRatesState, onEvent: (DealMemoEvent) -> Unit) {
    val tree = remember(rates.covered, rates.sidebarSearch) {
        TerritoryCatalogue.sidebarTree(rates.covered, rates.sidebarSearch)
    }
    DmCard(modifier = Modifier.width(296.dp).fillMaxHeight().padding(top = 12.dp), radius = 16.dp) {
        Box(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 12.dp)) {
            DmSearchPill(
                value = rates.sidebarSearch,
                onValueChange = { onEvent(RatesEvent.SidebarSearch(it)) },
                placeholder = str(S.dm_gpr_search_hint),
                height = 38.dp,
                compact = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(dm.cardBorder))
        ZillitScrollColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(vertical = 4.dp),
        ) {
            if (tree.isEmpty()) {
                ZillitText(
                    text = str(S.dm_gpr_no_territories),
                    style = DmType.sans(12.5.sp),
                    color = dm.ink3,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 32.dp),
                    textAlign = TextAlign.Center,
                )
            }
            tree.forEach { region -> SidebarRegion(region, rates, onEvent) }
        }
    }
}

@Suppress("LongMethod")
@Composable
private fun SidebarRegion(region: Region, rates: GlobalRatesState, onEvent: (DealMemoEvent) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitText(
            text = region.label.uppercase(),
            style = DmType.mono(10.sp, FontWeight.Bold, 0.12.em),
            color = if (ZillitTheme.colors.isDark) Color.White.copy(alpha = 0.45f) else dm.ink3,
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )
        ZillitText(
            text = region.territories.size.toString(),
            style = DmType.mono(10.5.sp, FontWeight.Bold),
            color = dm.ink3,
        )
    }
    Column(
        modifier = Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        region.territories.forEach { territory ->
            val active = rates.territoryId == territory.id
            val covered = territory.id in rates.covered
            val (source, hovered) = rememberHover()
            Box(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            when {
                                active -> if (ZillitTheme.colors.isDark) {
                                    Color(0xFFF59E0B).copy(alpha = 0.10f)
                                } else {
                                    Color(0xFFFFF4EA)
                                }
                                hovered -> dm.controlHoverBg
                                else -> Color.Transparent
                            },
                        )
                        .hoverable(source)
                        .clickable(
                            interactionSource = source,
                            indication = null,
                        ) { onEvent(RatesEvent.SelectTerritory(territory.id)) }
                        .pointerHoverIcon(PointerIcon.Hand)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    TerritoryFlag(territory.id, width = 20.dp)
                    Column(modifier = Modifier.weight(1f)) {
                        ZillitText(
                            text = territory.label,
                            style = DmType.sans(13.5.sp, FontWeight.SemiBold),
                            color = if (active) Color(0xFFE8861A) else dm.ink,
                            maxLines = 1,
                        )
                        ZillitText(
                            text = territory.code,
                            style = DmType.mono(10.5.sp, FontWeight.Bold, 0.04.em),
                            color = if (active) Color(0xFFE8861A).copy(alpha = 0.8f) else dm.ink3,
                        )
                    }
                    ZillitTooltip(text = if (covered) str(S.desktop_dm_has_unions) else str(S.desktop_not_configured)) {
                        Box(
                            Modifier.size(8.dp).clip(CircleShape).background(
                                if (covered) Color(0xFF1AA463) else Color(0xFFC9C8C2),
                            ),
                        )
                    }
                }
                if (active) {
                    Box(
                        Modifier
                            .align(Alignment.CenterStart)
                            .padding(vertical = 6.dp)
                            .width(3.dp)
                            .height(28.dp)
                            .clip(RoundedCornerShape(topEnd = 2.dp, bottomEnd = 2.dp))
                            .background(Color(0xFFE8861A)),
                    )
                }
            }
        }
    }
}

// -- welcome ---------------------------------------------------------------------------

@Suppress("LongMethod")
@Composable
private fun WelcomeView(rates: GlobalRatesState, onEvent: (DealMemoEvent) -> Unit) {
    val tree = remember(rates.covered) { TerritoryCatalogue.coveredTree(rates.covered) }
    val shown = tree.sumOf { it.territories.size }
    val total = TerritoryCatalogue.total
    val failOpen = rates.covered.isEmpty()
    val pinned = TerritoryCatalogue.pinned.filter { failOpen || it in rates.covered }
    ZillitScrollColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 28.dp, end = 28.dp, top = 20.dp, bottom = 32.dp),
    ) {
        ZillitText(
            text = buildAnnotatedString {
                append(str(S.desktop_dm_unions_agreements_and_rate_cards_across) + " ")
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = dm.ink2)) {
                    append(str(S.desktop_dm_n_territories, total))
                }
                append(str(S.desktop_dm_gpr_intro_tail))
            },
            style = DmType.sans(14.sp).copy(lineHeight = 21.sp),
            color = dm.ink3,
            modifier = Modifier.widthIn(max = 720.dp),
        )
        Spacer(Modifier.height(24.dp))
        ResponsiveGrid(
            items = listOf(
                StatSpec(
                    DmIcons.Globe,
                    Palette.Blue,
                    shown.toString(),
                    str(S.dm_gpr_stat_territories),
                    str(S.dm_gpr_stat_territories_sub),
                ),
                StatSpec(
                    ZillitIcons.Shield,
                    Palette.Green,
                    total.toString(),
                    str(S.dm_gpr_stat_catalogue),
                    str(S.desktop_dm_n_without_coverage_yet, total - shown),
                ),
                StatSpec(
                    ZillitIcons.Users,
                    Palette.Purple,
                    tree.size.toString(),
                    str(S.dm_gpr_stat_regions),
                    str(S.dm_gpr_stat_regions_sub),
                ),
                StatSpec(
                    ZillitIcons.StarOutline,
                    Palette.Amber,
                    pinned.size.toString(),
                    str(S.dm_gpr_stat_key),
                    str(S.dm_gpr_stat_key_sub),
                ),
            ),
        ) { spec, modifier -> WelcomeStat(spec, modifier) }
        Spacer(Modifier.height(28.dp))
        BlockHeading(
            str(S.desktop_dm_key_markets),
            str(S.dm_gpr_stat_key),
            str(S.desktop_dm_the_territories_you_shoot_in_most_often),
        )
        ResponsiveGrid(items = pinned) { id, modifier ->
            KeyTerritoryCard(
                id,
                covered = !failOpen,
                onClick = { onEvent(RatesEvent.SelectTerritory(id)) },
                modifier = modifier,
            )
        }
        Spacer(Modifier.height(32.dp))
        BlockHeading(
            str(S.dm_gpr_all_regions),
            str(S.desktop_dm_regions_territories_count, tree.size, shown),
            str(S.desktop_dm_every_territory_rolls_up_into_a_region),
        )
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            tree.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { region -> RegionCard(region, rates.covered, Modifier.weight(1f)) }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** A region card's wash, rule and ink. */
@Suppress("MagicNumber") // The web's swatches, verbatim.
internal enum class Palette(val light: Triple<Color, Color, Color>) {
    Blue(Triple(Color(0xFFE9EFFF), Color(0xFFCBD6F3), Color(0xFF2862E0))),
    Green(Triple(Color(0xFFE6F7EE), Color(0xFFC2E6D3), Color(0xFF0C6A3F))),
    Purple(Triple(Color(0xFFF1EBFF), Color(0xFFDBCDF6), Color(0xFF7A4CD6))),
    Amber(Triple(Color(0xFFFDF2E2), Color(0xFFF6D8A8), Color(0xFFE8861A))),
}

private data class StatSpec(
    val icon: ImageVector,
    val palette: Palette,
    val value: String,
    val label: String,
    val sub: String,
)

/** 1 / 2 / 4 columns by the pane's own width, as the web's sm/lg breakpoints do by the window's. */
@Composable
private fun <T> ResponsiveGrid(items: List<T>, cell: @Composable (T, Modifier) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = when {
            maxWidth >= 760.dp -> 4
            maxWidth >= 420.dp -> 2
            else -> 1
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { item -> cell(item, Modifier.weight(1f)) }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun WelcomeStat(spec: StatSpec, modifier: Modifier) {
    val (soft, ring, ink) = spec.palette.light
    DmCard(modifier = modifier, radius = 14.dp, padding = PaddingValues(18.dp)) {
        DmIconTile(spec.icon, background = soft, ring = ring, tint = ink, size = 34.dp, radius = 9.dp, iconSize = 17.dp)
        Spacer(Modifier.height(14.dp))
        ZillitText(
            text = spec.value,
            style = DmType.sans(30.sp, FontWeight.Bold, (-0.028).em).copy(lineHeight = 30.sp),
            color = dm.ink,
        )
        Spacer(Modifier.height(6.dp))
        ZillitText(
            text = spec.label.uppercase(),
            style = DmType.mono(10.5.sp, FontWeight.Bold, 0.1.em),
            color = dm.ink3,
        )
        Spacer(Modifier.height(4.dp))
        ZillitText(text = spec.sub, style = DmType.sans(11.5.sp), color = dm.ink3)
    }
}

@Composable
internal fun BlockHeading(eyebrow: String, title: String, description: String) {
    Column(modifier = Modifier.padding(bottom = 14.dp)) {
        ZillitText(text = eyebrow.uppercase(), style = DmType.mono(10.5.sp, FontWeight.Bold, 0.12.em), color = dm.ink3)
        Spacer(Modifier.height(4.dp))
        ZillitText(text = title, style = DmType.sans(19.sp, FontWeight.Bold, (-0.018).em), color = dm.ink)
        Spacer(Modifier.height(4.dp))
        ZillitText(text = description, style = DmType.sans(12.5.sp), color = dm.ink3)
    }
}

@Composable
private fun KeyTerritoryCard(id: String, covered: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val territory = TerritoryCatalogue.territory(id) ?: return
    val (source, hovered) = rememberHover()
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier = modifier
            .clip(shape)
            .background(dm.card)
            .border(1.dp, if (hovered) Color(0xFFE8861A).copy(alpha = 0.5f) else dm.cardBorder, shape)
            .hoverable(source)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(16.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp), verticalAlignment = Alignment.Top) {
            Box(
                modifier = Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(dm.soft).border(
                    1.dp,
                    dm.cardBorder,
                    RoundedCornerShape(10.dp),
                ),
                contentAlignment = Alignment.Center,
            ) {
                TerritoryFlag(id, width = 28.dp)
            }
            Spacer(Modifier.weight(1f))
            CoveragePill(covered)
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ZillitText(
                text = territory.label,
                style = DmType.sans(16.sp, FontWeight.Bold, (-0.012).em),
                color = if (hovered) Color(0xFFE8861A) else dm.ink,
                maxLines = 1,
            )
            ZillitText(
                text = territory.code,
                style = DmType.mono(10.5.sp, FontWeight.SemiBold, 0.04.em),
                color = dm.ink3,
            )
        }
        ZillitText(
            text = TerritoryCatalogue.regionLabelOf(id).orEmpty(),
            style = DmType.sans(11.5.sp),
            color = dm.ink3,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
private fun CoveragePill(covered: Boolean) {
    val tone = if (covered) CoveredPill else NotSetPill
    val shape = RoundedCornerShape(50)
    Row(
        modifier = Modifier
            .clip(shape)
            .background(tone.background)
            .border(1.dp, tone.border, shape)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(tone.dot))
        ZillitText(
            text = if (covered) "COVERED" else str(S.dm_gpr_not_set),
            style = DmType.sans(10.sp, FontWeight.Bold, 0.06.em),
            color = tone.ink,
        )
    }
}

/** The coverage pill's ink, wash, rule and dot. */
private class PillTone(val ink: Color, val background: Color, val border: Color, val dot: Color)

private val CoveredPill = PillTone(Color(0xFF0C6A3F), Color(0xFFE6F7EE), Color(0xFFC2E6D3), Color(0xFF1AA463))
private val NotSetPill = PillTone(Color(0xFF8A8D95), Color(0xFFF1EFE9), Color(0xFFE3E2DD), Color(0xFFC9C8C2))

@Composable
private fun RegionCard(region: Region, covered: Set<String>, modifier: Modifier) {
    val total = region.territories.size
    val coveredCount = region.territories.count { it.id in covered }
    DmCard(modifier = modifier, radius = 14.dp, padding = PaddingValues(16.dp)) {
        ZillitText(text = region.label, style = DmType.sans(15.sp, FontWeight.Bold, (-0.01).em), color = dm.ink)
        Spacer(Modifier.height(2.dp))
        ZillitText(
            text = buildAnnotatedString {
                withStyle(
                    SpanStyle(fontFamily = ZillitTheme.fonts.mono, fontWeight = FontWeight.SemiBold),
                ) { append("$total") }
                append(" " + if (total == 1) str(S.desktop_dm_territory_fallback_word)
                    else str(S.desktop_dm_territories_suffix))
                if (coveredCount > 0) {
                    append(" · ")
                    withStyle(
                        SpanStyle(
                            color = if (ZillitTheme.colors.isDark) Color(0xFF34D399) else Color(0xFF0C6A3F),
                            fontWeight = FontWeight.SemiBold,
                        ),
                    ) {
                        append(str(S.desktop_dm_n_covered, coveredCount))
                    }
                }
            },
            style = DmType.sans(11.5.sp),
            color = dm.ink3,
        )
        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(dm.track)) {
            val fraction = if (total == 0) 0f else coveredCount.toFloat() / total
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .fillMaxHeight()
                    .clip(CircleShape)
                    .background(if (fraction >= 1f) Color(0xFF1AA463) else Color(0xFFE8861A)),
            )
        }
        Spacer(Modifier.height(12.dp))
        FlagChips(region)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlagChips(region: Region) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        region.territories.take(MAX_CHIPS).forEach { territory ->
            ZillitTooltip(text = territory.label) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(dm.soft)
                        .border(1.dp, dm.cardBorder, RoundedCornerShape(6.dp))
                        .padding(start = 5.dp, end = 7.dp, top = 3.dp, bottom = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    TerritoryFlag(territory.id, width = 13.dp)
                    ZillitText(
                        text = territory.code,
                        style = DmType.mono(11.sp, FontWeight.Bold, 0.02.em),
                        color = dm.ink2,
                    )
                }
            }
        }
        if (region.territories.size > MAX_CHIPS) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(dm.track)
                    .border(1.dp, dm.cardBorder, RoundedCornerShape(6.dp))
                    .padding(horizontal = 7.dp, vertical = 3.dp),
            ) {
                ZillitText(
                    text = "+${region.territories.size - MAX_CHIPS}",
                    style = DmType.mono(11.sp, FontWeight.Bold),
                    color = dm.ink3,
                )
            }
        }
    }
}

private const val MAX_CHIPS = 8
