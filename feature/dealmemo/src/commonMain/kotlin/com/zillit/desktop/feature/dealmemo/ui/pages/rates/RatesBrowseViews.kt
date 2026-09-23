package com.zillit.desktop.feature.dealmemo.ui.pages.rates

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.dealmemo.domain.DealLabels
import com.zillit.desktop.feature.dealmemo.domain.rates.AgreementSummary
import com.zillit.desktop.feature.dealmemo.domain.rates.Branch
import com.zillit.desktop.feature.dealmemo.domain.rates.EmpStatus
import com.zillit.desktop.feature.dealmemo.domain.rates.GlobalRatesRules
import com.zillit.desktop.feature.dealmemo.domain.rates.RateDepartmentSection
import com.zillit.desktop.feature.dealmemo.domain.rates.RateDesignationGroup
import com.zillit.desktop.feature.dealmemo.domain.rates.RateFormat
import com.zillit.desktop.feature.dealmemo.domain.rates.TerritoryCatalogue
import com.zillit.desktop.feature.dealmemo.domain.rates.UnionSection
import com.zillit.desktop.feature.dealmemo.ui.AgreementOrigin
import com.zillit.desktop.feature.dealmemo.ui.DealMemoEvent
import com.zillit.desktop.feature.dealmemo.ui.DealMemoUiState
import com.zillit.desktop.feature.dealmemo.ui.GlobalRatesState
import com.zillit.desktop.feature.dealmemo.ui.RatesEvent
import com.zillit.desktop.feature.dealmemo.ui.components.DmBadge
import com.zillit.desktop.feature.dealmemo.ui.components.DmDot
import com.zillit.desktop.feature.dealmemo.ui.components.DmIcons
import com.zillit.desktop.feature.dealmemo.ui.components.DmTone
import com.zillit.desktop.feature.dealmemo.ui.components.DmType
import com.zillit.desktop.feature.dealmemo.ui.components.TerritoryFlag
import com.zillit.desktop.feature.dealmemo.ui.components.dm
import com.zillit.desktop.feature.dealmemo.ui.components.rememberHover

private val AMBER = Color(0xFFE8861A)

// -- territory -------------------------------------------------------------------------

@Composable
internal fun TerritoryView(state: DealMemoUiState, onEvent: (DealMemoEvent) -> Unit) {
    val rates = state.rates
    val id = rates.territoryId ?: return
    val territory = TerritoryCatalogue.territory(id)
    val sections = remember(rates.unions, rates.branches, rates.territoryFilter) { rates.sections }
    ZillitScrollColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 24.dp),
    ) {
        BackLink(str(S.desktop_dm_union_config), onClick = { onEvent(RatesEvent.BackToWelcome) })
        Row(
            modifier = Modifier.padding(bottom = 22.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                Modifier
                    .size(60.dp, 44.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(dm.card)
                    .border(1.dp, dm.cardBorder, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) { TerritoryFlag(id, width = 32.dp) }
            Column {
                ViewTitle(territory?.label ?: id.uppercase())
                MetaRow(
                    listOfNotNull(
                        id.uppercase() to true,
                        TerritoryCatalogue.regionLabelOf(id)?.let { it to false },
                        GlobalRatesRules.count(rates.unions.size, "union") to false,
                        GlobalRatesRules.count(rates.branches.size, "branch", "branches") to false,
                    ),
                )
            }
        }
        Row(modifier = Modifier.padding(bottom = 22.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilterField(
                value = rates.territoryFilter,
                placeholder = str(S.dm_gpr_filter_unions),
                onValueChange = { onEvent(RatesEvent.TerritoryFilter(it)) },
                modifier = Modifier.weight(1f),
            )
            ViewAgreementsButton(onClick = { onEvent(RatesEvent.ViewAgreements) })
        }
        when {
            rates.unionsLoading -> SectionsSkeleton()
            rates.branches.isEmpty() -> CenteredNote(str(S.dm_gpr_no_unions))
            sections.isEmpty() -> CenteredNote(str(S.dm_gpr_no_union_match))
            else -> Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
                sections.forEach { section -> UnionSectionBlock(section, onEvent) }
            }
        }
    }
}

@Composable
private fun UnionSectionBlock(section: UnionSection, onEvent: (DealMemoEvent) -> Unit) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ZillitText(
                text = section.union.name,
                style = DmType.sans(22.sp, FontWeight.Bold, (-0.022).em),
                color = dm.ink,
                maxLines = 1,
            )
            ZillitText(
                text = section.union.identifier,
                style = DmType.mono(12.sp, FontWeight.SemiBold),
                color = dm.ink3,
                maxLines = 1,
            )
            if (!section.isFlat) {
                DmDot()
                ZillitText(
                    text = GlobalRatesRules.count(section.branches.size, "branch", "branches"),
                    style = DmType.mono(12.sp),
                    color = dm.ink3,
                )
            }
            Spacer(Modifier.weight(1f))
            section.union.source?.let { SourceLink(it, blue = true) }
        }
        CardGrid(
            section.branches,
            columnsFor = { if (it >= 900.dp) 3 else if (it >= 560.dp) 2 else 1 },
        ) { branch, modifier ->
            SelectableCard(onClick = { onEvent(RatesEvent.SelectBranch(branch)) }, modifier = modifier) {
                Column(modifier = Modifier.weight(1f)) {
                    ZillitText(
                        text = branch.name,
                        style = DmType.sans(14.5.sp, FontWeight.Bold).copy(lineHeight = 19.sp),
                        color = dm.ink,
                    )
                    Spacer(Modifier.height(6.dp))
                    ZillitText(
                        text = branch.identifier,
                        style = DmType.mono(11.5.sp, FontWeight.SemiBold),
                        color = dm.ink3,
                        maxLines = 1,
                    )
                }
                branch.shortLabel?.let { Chip(it, ChipTone.Amber) }
                ZillitIcon(ZillitIcons.ChevronRight, size = 10.dp, tint = Color(0xFFC9C8C2))
            }
        }
    }
}

// -- territory agreements -------------------------------------------------------------------

@Composable
internal fun TerritoryAgreementsView(rates: GlobalRatesState, onEvent: (DealMemoEvent) -> Unit) {
    val id = rates.territoryId ?: return
    val label = TerritoryCatalogue.label(id) ?: id.uppercase()
    ZillitScrollColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 24.dp),
    ) {
        BackLink(label, flag = id, onClick = { onEvent(RatesEvent.BackToTerritory) })
        Row(
            modifier = Modifier.padding(bottom = 22.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(11.dp)).background(Color(0xFFFDF2E2)).border(
                    1.dp,
                    Color(0xFFF6D8A8),
                    RoundedCornerShape(11.dp),
                ),
                contentAlignment = Alignment.Center,
            ) { ZillitIcon(DmIcons.Book, size = 18.dp, tint = AMBER) }
            Column {
                ViewTitle(str(S.dm_gpr_agreements_for, label))
                MetaRow(
                    listOf(id.uppercase() to true, GlobalRatesRules.count(rates.agreements.size, "agreement") to false),
                )
            }
        }
        FilterField(
            value = rates.agreementsFilter,
            placeholder = str(S.dm_gpr_filter_agreements),
            onValueChange = { onEvent(RatesEvent.AgreementsFilter(it)) },
            modifier = Modifier.fillMaxWidth().padding(bottom = 22.dp),
        )
        val filtered = rates.filteredAgreements
        when {
            rates.agreementsLoading -> CardGridSkeleton(6)
            rates.agreements.isEmpty() -> CenteredNote(str(S.dm_gpr_no_agreements))
            filtered.isEmpty() -> CenteredNote(str(S.dm_gpr_no_agreement_match))
            else -> CardGrid(
                filtered,
                columnsFor = { if (it >= 900.dp) 3 else if (it >= 560.dp) 2 else 1 },
            ) { agreement, modifier ->
                AgreementCard(
                    agreement,
                    onClick = { onEvent(RatesEvent.SelectAgreement(agreement, AgreementOrigin.AgreementsList)) },
                    modifier = modifier,
                )
            }
        }
    }
}

@Composable
private fun AgreementCard(agreement: AgreementSummary, onClick: () -> Unit, modifier: Modifier) {
    SelectableCard(onClick = onClick, modifier = modifier) {
        Column(modifier = Modifier.weight(1f)) {
            ZillitText(
                text = agreement.name,
                style = DmType.sans(14.5.sp, FontWeight.Bold).copy(lineHeight = 19.sp),
                color = dm.ink,
                maxLines = 2,
            )
            Spacer(Modifier.height(6.dp))
            ZillitText(
                text = agreement.identifier,
                style = DmType.mono(11.5.sp, FontWeight.SemiBold),
                color = dm.ink3,
                maxLines = 1,
            )
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            agreement.territory?.let { Chip(it.uppercase(), ChipTone.Grey) }
            agreement.currency?.let { Chip(it, ChipTone.Amber) }
        }
        ZillitIcon(ZillitIcons.ChevronRight, size = 10.dp, tint = Color(0xFFC9C8C2))
    }
}

// -- branch ---------------------------------------------------------------------------

@Composable
internal fun BranchView(state: DealMemoUiState, onEvent: (DealMemoEvent) -> Unit) {
    val rates = state.rates
    val branch = rates.branch ?: return
    val navTerritory = rates.territoryId
    ZillitScrollColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 24.dp),
    ) {
        BackLink(
            TerritoryCatalogue.label(navTerritory) ?: navTerritory?.uppercase().orEmpty(),
            flag = navTerritory,
            onClick = { onEvent(RatesEvent.BackToTerritory) },
        )
        Column(modifier = Modifier.padding(bottom = 22.dp)) {
            ViewTitle(branch.name)
            MetaRow(
                listOfNotNull(
                    branch.identifier to false,
                    branch.territory?.uppercase()?.let { it to true },
                    TerritoryCatalogue.regionLabel(branch.region)?.let { it to false },
                ),
            )
            branch.source?.let {
                Spacer(Modifier.height(12.dp))
                SourceLink(it, blue = false)
            }
        }
        EmploymentStatusesPanel(branch.territory, rates.empStatuses[branch.territory?.lowercase()].orEmpty())
        Panel(icon = ZillitIcons.Shield, title = str(S.dm_gpr_agreements_covering)) {
            Box(Modifier.fillMaxWidth().padding(16.dp)) {
                when {
                    rates.agreementsLoading -> CardGridSkeleton(4, twoUp = true)
                    rates.agreements.isEmpty() -> CenteredNote(
                        str(S.dm_gpr_no_cba),
                        italic = true,
                        vertical = 24.dp,
                    )
                    else -> CardGrid(
                        rates.agreements,
                        columnsFor = { if (it >= 520.dp) 2 else 1 },
                    ) { agreement, modifier ->
                        AgreementCard(
                            agreement,
                            onClick = { onEvent(RatesEvent.SelectAgreement(agreement, AgreementOrigin.Branch)) },
                            modifier = modifier,
                        )
                    }
                }
            }
        }
        RateCardPanel(state, branch)
    }
}

@Composable
private fun EmploymentStatusesPanel(territory: String?, statuses: List<EmpStatus>) {
    val code = territory?.uppercase() ?: str(S.desktop_dm_territory_fallback_word)
    Panel(icon = ZillitIcons.Users, title = str(S.desktop_dm_employment_statuses_code, code)) {
        if (statuses.isEmpty()) {
            ZillitText(
                text = str(S.dm_gpr_no_statuses),
                style = DmType.sans(12.5.sp),
                color = dm.ink3,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            )
            return@Panel
        }
        Box(Modifier.fillMaxWidth().background(dm.soft).padding(horizontal = 20.dp, vertical = 12.dp)) {
            ZillitText(
                text = str(S.dm_gpr_emp_statuses_note, code),
                style = DmType.sans(12.sp).copy(fontStyle = FontStyle.Italic, lineHeight = 19.sp),
                color = dm.ink3,
            )
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(dm.cardBorder))
        statuses.forEachIndexed { index, status ->
            if (index > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(dm.cardBorder))
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp)) {
                Row(
                    modifier = Modifier.padding(bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ZillitText(text = status.label, style = DmType.sans(14.sp, FontWeight.Bold), color = dm.ink)
                    status.badge?.let { DmBadge(it, badgeTone(status.alertClass)) }
                    if (status.hpShown) DmBadge(str(S.desktop_dm_hp_shown), DmTone.Green)
                }
                status.sub?.let {
                    ZillitText(text = it, style = DmType.sans(12.5.sp).copy(lineHeight = 18.sp), color = dm.ink3)
                }
            }
        }
    }
}

private fun badgeTone(alertClass: String?): DmTone = when (alertClass) {
    "blue" -> DmTone.Blue
    "orange", "gold" -> DmTone.Amber
    "red" -> DmTone.Red
    "green" -> DmTone.Green
    else -> DmTone.Gray
}

// -- rate card -------------------------------------------------------------------------

private val RATE_FIXED = listOf(220.dp, 110.dp, 150.dp, 80.dp)
private val RATE_HEADERS get() = listOf(
    str(S.dm_label_designation),
    str(S.desktop_dm_prod_type),
    str(S.budget_text),
    str(S.desktop_dm_exp),
    str(S.desktop_dm_hourly),
    str(S.dm_rates_buyout_mode_daily),
    str(S.dm_rates_buyout_mode_weekly),
    str(S.desktop_dm_flat),
)
private val RATE_MIN_WIDTH = 1080.dp

@Composable
private fun RateCardPanel(state: DealMemoUiState, branch: Branch) {
    val rates = state.rates
    val sections = remember(rates.rates, state.catalogue) {
        GlobalRatesRules.rateSections(rates.rates, state.catalogue)
    }
    val fallback = GlobalRatesRules.fallbackCurrency(branch)
    LegacyCard(icon = DmIcons.Dollar, title = str(S.dm_gpr_rate_card)) {
        when {
            rates.ratesLoading -> RateSkeleton()
            sections.isEmpty() -> ZillitText(
                text = str(S.dm_gpr_no_rates),
                style = DmType.sans(12.sp).copy(fontStyle = FontStyle.Italic),
                color = Color(0xFF9CA3AF),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 24.dp),
            )
            else -> BoxWithConstraints(Modifier.fillMaxWidth()) {
                val width = if (maxWidth < RATE_MIN_WIDTH) RATE_MIN_WIDTH else maxWidth
                Column(Modifier.horizontalScroll(rememberScrollState()).width(width)) {
                    RateHeader()
                    sections.forEach { section -> RateSection(section, fallback) }
                }
            }
        }
    }
}

@Composable
private fun RateHeader() {
    Row(Modifier.fillMaxWidth().background(legacyGray50()), verticalAlignment = Alignment.CenterVertically) {
        RATE_HEADERS.forEachIndexed { index, title ->
            val cell = if (index < RATE_FIXED.size) Modifier.width(RATE_FIXED[index]) else Modifier.weight(1f)
            ZillitText(
                text = title.uppercase(),
                style = DmType.sans(10.sp, FontWeight.Bold, 0.05.em),
                color = Color(0xFF6B7280),
                modifier = cell.padding(horizontal = 12.dp, vertical = 8.dp),
                maxLines = 1,
            )
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(legacyGray200()))
}

@Composable
private fun RateSection(section: RateDepartmentSection, fallback: String?) {
    val brand = Color(0xFFFC9404)
    Box(Modifier.fillMaxWidth().height(1.dp).background(brand.copy(alpha = 0.2f)))
    Row(
        Modifier
            .fillMaxWidth()
            .background(brand.copy(alpha = if (ZillitTheme.colors.isDark) 0.12f else 0.08f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val ink = if (ZillitTheme.colors.isDark) Color(0xFFFBBF24) else Color(0xFFB86700)
        ZillitText(
            text = DealLabels.formatLabel(section.nameKey).uppercase(),
            style = DmType.sans(11.sp, FontWeight.Bold, 0.05.em),
            color = ink,
        )
        val d = section.designations.size
        val r = section.rateCount
        ZillitText(
            text = (if (d == 1) str(S.desktop_dm_one_designation) else str(S.desktop_dm_n_designations, d)) + " · " +
                if (r == 1) str(S.desktop_dm_one_rate) else str(S.desktop_dm_n_rates, r),
            style = DmType.mono(10.sp, FontWeight.Normal),
            color = ink.copy(alpha = 0.7f),
            modifier = Modifier.padding(start = 8.dp),
        )
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(brand.copy(alpha = 0.2f)))
    section.designations.forEach { group -> DesignationRows(group, fallback) }
}

@Suppress("LongMethod")
@Composable
private fun DesignationRows(group: RateDesignationGroup, fallback: String?) {
    Box(Modifier.fillMaxWidth().height(1.dp).background(legacyGray100()))
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Column(
            Modifier.width(RATE_FIXED[0]).fillMaxHeight().background(legacyGray50().copy(alpha = 0.6f)).padding(
                horizontal = 12.dp,
                vertical = 8.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            val label = if (group.matched) {
                DealLabels.formatLabel(group.nameKey)
            } else {
                DealLabels.formatLabel(GlobalRatesRules.orphanLabelKey(group.identifier))
            }
            ZillitText(
                text = label,
                style = DmType.sans(12.sp, FontWeight.SemiBold).copy(lineHeight = 16.sp),
                color = legacyInk800(),
            )
            if (!group.matched) DmBadge(str(S.desktop_dm_unmatched), DmTone.Amber)
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(legacyGray100()))
        Column(Modifier.weight(1f)) {
            group.rates.forEachIndexed { index, rate ->
                if (index > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(legacyGray100()))
                val currency = rate.currency ?: fallback
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    RateText(
                        RateFormat.productionTypeLabel(rate.productionType, DealLabels.translation).ifEmpty { "—" },
                        RATE_FIXED[1] - 1.dp,
                        mono = false,
                        muted = rate.productionType.isNullOrEmpty(),
                    )
                    RateText(RateFormat.formatBudgetRange(rate.minBudget, rate.maxBudget, currency), RATE_FIXED[2])
                    RateText(RateFormat.formatExpRange(rate.minExp, rate.maxExp), RATE_FIXED[3])
                    listOf(rate.hourly, rate.daily, rate.weekly, rate.flatRate).forEach { tiers ->
                        ZillitText(
                            text = RateFormat.formatTierArray(tiers, currency),
                            style = DmType.mono(11.sp),
                            color = legacyInk800(),
                            modifier = Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                }
                rate.notes?.let { note ->
                    Box(Modifier.fillMaxWidth().height(1.dp).background(legacyGray100()))
                    Row(
                        Modifier.fillMaxWidth().background(legacyGray50().copy(alpha = 0.4f)).padding(
                            horizontal = 12.dp,
                            vertical = 8.dp,
                        ),
                    ) {
                        ZillitText(
                            text = "NOTE",
                            style = DmType.sans(9.sp, FontWeight.Bold, 0.05.em),
                            color = Color(0xFF9CA3AF),
                            modifier = Modifier.padding(end = 8.dp, top = 2.dp),
                        )
                        ZillitText(
                            text = note,
                            style = DmType.sans(11.sp).copy(lineHeight = 17.sp),
                            color = Color(0xFF6B7280),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RateText(text: String, width: Dp, mono: Boolean = true, muted: Boolean = false) {
    ZillitText(
        text = text,
        style = if (mono) DmType.mono(11.sp) else DmType.sans(11.sp),
        color = if (muted) {
            Color(0xFF9CA3AF)
        } else {
            Color(0xFF374151).takeUnless { ZillitTheme.colors.isDark } ?: Color(0xFFD1D5DB)
        },
        maxLines = 1,
        modifier = Modifier.width(width).padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

@Composable
private fun RateSkeleton() {
    val widths = listOf(0.24f, 0.12f, 0.10f, 0.10f, 0.10f, 0.10f, 0.10f)
    Column {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            widths.forEach {
                Box(Modifier.weight(it).height(8.dp).clip(RoundedCornerShape(4.dp)).background(dm.skeleton))
            }
        }
        repeat(6) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(dm.track))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 11.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                widths.forEach {
                    Box(Modifier.weight(it).height(10.dp).clip(RoundedCornerShape(4.dp)).background(dm.skeleton))
                }
            }
        }
    }
}

// -- shared pieces ------------------------------------------------------------------------

@Composable
internal fun BackLink(label: String, flag: String? = null, onClick: () -> Unit) {
    val (source, hovered) = rememberHover()
    val colour = if (hovered) AMBER else dm.ink2
    Row(
        modifier = Modifier
            .padding(bottom = 14.dp)
            .hoverable(source)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ZillitIcon(ZillitIcons.ChevronLeft, size = 12.dp, tint = colour)
        if (flag != null) TerritoryFlag(flag, width = 14.dp)
        ZillitText(text = label, style = DmType.sans(12.5.sp, FontWeight.SemiBold), color = colour, maxLines = 1)
    }
}

@Composable
internal fun ViewTitle(text: String) {
    ZillitText(
        text = text,
        style = DmType.sans(28.sp, FontWeight.Bold, (-0.028).em).copy(lineHeight = 30.sp),
        color = dm.ink,
    )
}

/** A DM Mono meta line with 3 px dots between parts; `true` marks the emphasised part. */
@Composable
internal fun MetaRow(parts: List<Pair<String, Boolean>>) {
    Row(
        modifier = Modifier.padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        parts.forEachIndexed { index, (text, strong) ->
            if (index > 0) DmDot()
            ZillitText(
                text = text,
                style = DmType.mono(12.sp, if (strong) FontWeight.SemiBold else FontWeight.Normal),
                color = if (strong) dm.ink2 else dm.ink3,
                maxLines = 1,
            )
        }
    }
}

@Composable
internal fun SourceLink(url: String, blue: Boolean) {
    val uriHandler = LocalUriHandler.current
    val (source, hovered) = rememberHover()
    val colour = if (blue) (if (ZillitTheme.colors.isDark) Color(0xFF60A5FA) else Color(0xFF2862E0)) else dm.ink3
    Row(
        modifier = Modifier
            .hoverable(source)
            .clickable(interactionSource = source, indication = null) { runCatching { uriHandler.openUri(url) } }
            .pointerHoverIcon(PointerIcon.Hand),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ZillitIcon(ZillitIcons.Link, size = 10.dp, tint = colour)
        ZillitText(
            text = GlobalRatesRules.sourceText(url),
            style = DmType.mono(12.sp, FontWeight.SemiBold).copy(
                textDecoration = if (hovered) TextDecoration.Underline else null,
            ),
            color = colour,
            maxLines = 1,
        )
    }
}

@Composable
private fun FilterField(value: String, placeholder: String, onValueChange: (String) -> Unit, modifier: Modifier) {
    val focus = remember { MutableInteractionSource() }
    val focused by focus.collectIsFocusedAsState()
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier = modifier
            .height(46.dp)
            .clip(shape)
            .background(dm.card)
            .border(1.dp, if (focused) AMBER else dm.cardBorder, shape)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ZillitIcon(ZillitIcons.Search, size = 15.dp, tint = dm.ink3)
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) ZillitText(
                text = placeholder,
                style = DmType.sans(13.5.sp),
                color = dm.placeholder,
                maxLines = 1,
            )
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = DmType.sans(13.5.sp).copy(color = dm.ink),
                cursorBrush = SolidColor(AMBER),
                interactionSource = focus,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ViewAgreementsButton(onClick: () -> Unit) {
    val (source, hovered) = rememberHover()
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier = Modifier
            .height(46.dp)
            .clip(shape)
            .background(dm.card)
            .border(1.dp, if (hovered) AMBER else dm.cardBorder, shape)
            .hoverable(source)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ZillitIcon(ZillitIcons.Shield, size = 14.dp, tint = AMBER)
        ZillitText(
            text = str(S.dm_gpr_view_agreements),
            style = DmType.sans(13.5.sp, FontWeight.Bold),
            color = dm.ink,
            maxLines = 1,
        )
    }
}

/** A white card that lifts and warms its border on hover — branches and agreements. */
@Composable
private fun SelectableCard(
    onClick: () -> Unit,
    modifier: Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val (source, hovered) = rememberHover()
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = modifier
            .then(
                if (hovered) {
                    Modifier.shadow(6.dp, shape, ambientColor = Color(0x1A0F1115), spotColor = Color(0x1A0F1115))
                } else {
                    Modifier
                },
            )
            .clip(shape)
            .background(dm.card)
            .border(1.dp, if (hovered) AMBER else dm.cardBorder, shape)
            .hoverable(source)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
        content = content,
    )
}

@Composable
private fun <T> CardGrid(items: List<T>, columnsFor: (Dp) -> Int, cell: @Composable (T, Modifier) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = columnsFor(maxWidth)
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items.chunked(columns).forEach { row ->
                Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { item -> cell(item, Modifier.weight(1f).fillMaxHeight()) }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

internal enum class ChipTone { Amber, Grey, Teal }

@Composable
internal fun Chip(text: String, tone: ChipTone) {
    val (background, border, ink) = when (tone) {
        ChipTone.Amber -> Triple(Color(0xFFFDF2E2), Color(0xFFF6D8A8), AMBER)
        ChipTone.Grey -> Triple(Color(0xFFF1EFE9), Color.Transparent, Color(0xFF8A8D95))
        ChipTone.Teal -> Triple(Color(0xFFD9F4F0), Color(0xFFA8E3D9), Color(0xFF14A394))
    }
    val shape = RoundedCornerShape(6.dp)
    Box(
        Modifier
            .clip(shape)
            .background(background)
            .border(1.dp, border, shape)
            .padding(horizontal = 7.dp, vertical = 3.dp),
    ) {
        ZillitText(
            text = text,
            style = if (tone == ChipTone.Teal) {
                DmType.sans(10.5.sp, FontWeight.Bold)
            } else {
                DmType.mono(10.5.sp, FontWeight.Bold)
            },
            color = ink,
            maxLines = 1,
        )
    }
}

/** The new panel chrome: a 28 px amber tile, a bold title, and a hairline under the header. */
@Composable
internal fun Panel(icon: ImageVector, title: String, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier.padding(bottom = 16.dp).fillMaxWidth().clip(shape).background(dm.card).border(
            1.dp,
            dm.cardBorder,
            shape,
        ),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFFFDF2E2)).border(
                    1.dp,
                    Color(0xFFF6D8A8),
                    RoundedCornerShape(8.dp),
                ),
                contentAlignment = Alignment.Center,
            ) { ZillitIcon(icon, size = 13.dp, tint = AMBER) }
            ZillitText(text = title, style = DmType.sans(15.5.sp, FontWeight.Bold, (-0.01).em), color = dm.ink)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(dm.cardBorder))
        content()
    }
}

/** The legacy `Card`: a 12 px corner, an inline amber icon beside a 14 px title. */
@Composable
internal fun LegacyCard(icon: ImageVector, title: String, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier.padding(bottom = 12.dp).fillMaxWidth().clip(shape).background(legacyCardBg()).border(
            1.dp,
            legacyGray200(),
            shape,
        ),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ZillitIcon(icon, size = 14.dp, tint = Color(0xFFFC9404))
            ZillitText(text = title, style = DmType.sans(14.sp, FontWeight.Bold), color = legacyInk900())
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(legacyGray200()))
        content()
    }
}

@Composable
private fun CenteredNote(text: String, italic: Boolean = false, vertical: Dp = 48.dp) {
    ZillitText(
        text = text,
        style = DmType.sans(12.5.sp).copy(fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal),
        color = dm.ink3,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(vertical = vertical),
    )
}

@Composable
private fun SectionsSkeleton() {
    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        listOf(3, 2).forEach { cards ->
            Column {
                Bar(width = 190.dp, height = 18.dp)
                Spacer(Modifier.height(6.dp))
                Bar(width = 90.dp, height = 10.dp)
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    repeat(cards) { SkeletonCard(Modifier.weight(1f)) }
                    repeat(3 - cards) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun CardGridSkeleton(count: Int, twoUp: Boolean = false) {
    val columns = if (twoUp) 2 else 3
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        for (row in (0 until count).chunked(columns)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                repeat(row.size) { SkeletonCard(Modifier.weight(1f)) }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun SkeletonCard(modifier: Modifier) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier.clip(shape).background(dm.card).border(1.dp, dm.cardBorder, shape).padding(16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.fillMaxWidth(0.62f).height(13.dp).clip(RoundedCornerShape(4.dp)).background(dm.skeleton))
            Box(Modifier.fillMaxWidth(0.42f).height(9.dp).clip(RoundedCornerShape(4.dp)).background(dm.skeleton))
        }
        Box(Modifier.size(48.dp, 21.dp).clip(RoundedCornerShape(6.dp)).background(dm.track))
    }
}

// Tailwind's legacy grays, with the web's dark remaps (`darkMode.css`).
@Composable internal fun legacyGray50(): Color = if (ZillitTheme.colors.isDark) Color(0xFF22262E) else Color(0xFFF9FAFB)
@Composable internal fun legacyGray100(): Color = if (ZillitTheme.colors.isDark) {
    Color.White.copy(alpha = 0.06f)
} else {
    Color(0xFFF3F4F6)
}
@Composable internal fun legacyGray200(): Color = if (ZillitTheme.colors.isDark) {
    Color.White.copy(alpha = 0.10f)
} else {
    Color(0xFFE5E7EB)
}
@Composable internal fun legacyInk800(): Color = if (ZillitTheme.colors.isDark) Color(0xFFD1D5DB) else Color(0xFF1F2937)
@Composable internal fun legacyInk900(): Color = if (ZillitTheme.colors.isDark) Color(0xFFE8EAF0) else Color(0xFF111827)
@Composable internal fun legacyCardBg(): Color = if (ZillitTheme.colors.isDark) Color(0xFF1A1D23) else Color.White
