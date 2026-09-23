package com.zillit.desktop.feature.dealmemo.ui.pages.rates

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import com.zillit.desktop.feature.dealmemo.domain.DocRead
import com.zillit.desktop.feature.dealmemo.domain.rates.AgreementDocument
import com.zillit.desktop.feature.dealmemo.domain.rates.AgreementFormat
import com.zillit.desktop.feature.dealmemo.domain.rates.EmpStatus
import com.zillit.desktop.feature.dealmemo.domain.rates.Js
import com.zillit.desktop.feature.dealmemo.domain.rates.RateFormat
import com.zillit.desktop.feature.dealmemo.domain.rates.RateTierEntry
import com.zillit.desktop.feature.dealmemo.domain.rates.TerritoryCatalogue
import com.zillit.desktop.feature.dealmemo.ui.AgreementOrigin
import com.zillit.desktop.feature.dealmemo.ui.DealMemoEvent
import com.zillit.desktop.feature.dealmemo.ui.DealMemoUiState
import com.zillit.desktop.feature.dealmemo.ui.RatesEvent
import com.zillit.desktop.feature.dealmemo.ui.components.DmBadge
import com.zillit.desktop.feature.dealmemo.ui.components.DmIcons
import com.zillit.desktop.feature.dealmemo.ui.components.DmTone
import com.zillit.desktop.feature.dealmemo.ui.components.DmType
import com.zillit.desktop.feature.dealmemo.ui.components.dm
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private val GRAY_400 = Color(0xFF9CA3AF)
private val GRAY_500 = Color(0xFF6B7280)

/** One agreement's every working rule (`DMConfigPage.jsx` `AgreementDetail`). */
@Composable
internal fun AgreementDetailView(state: DealMemoUiState, onEvent: (DealMemoEvent) -> Unit) {
    val rates = state.rates
    val territoryId = rates.territoryId
    val backLabel = when (rates.agreementOrigin) {
        AgreementOrigin.AgreementsList ->
            territoryId?.let { str(S.dm_gpr_agreements_for, TerritoryCatalogue.label(it) ?: it.uppercase()) }
                ?: str(S.desktop_dm_agreements)
        AgreementOrigin.Branch, null -> rates.branch?.name ?: str(S.dm_wizard_back)
    }
    ZillitScrollColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 24.dp),
    ) {
        BackLink(backLabel, onClick = { onEvent(RatesEvent.BackFromAgreement) })
        val doc = rates.agreement
        when {
            rates.agreementLoading -> AgreementSkeleton()
            doc == null -> if (rates.agreementFailed) {
                ZillitText(
                    text = str(S.desktop_dm_this_agreement_could_not_be_loaded),
                    style = DmType.sans(12.5.sp),
                    color = dm.ink3,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            }
            else -> AgreementBody(state, doc)
        }
    }
}

@Composable
private fun ColumnScope.AgreementBody(state: DealMemoUiState, doc: AgreementDocument) {
    AgreementHeader(doc)
    BasicSchedule(doc)
    RuleCard(doc, "overtimes", str(S.dm_rates_overtimes), ZillitIcons.Clock, withTrigger = true)
    RuleCard(doc, "premiums", str(S.dm_rates_premiums), ZillitIcons.StarOutline, withTrigger = true)
    RuleCard(doc, "turnaround", str(S.desktop_turnaround), DmIcons.Swap, withTrigger = true)
    RuleCard(doc, "allowances", str(S.dm_section_allowances), DmIcons.Gift, withTrigger = false)
    RuleCard(doc, "rentals", str(S.dm_allow_card_rentals), DmIcons.Database, withTrigger = false)
    val territoryStatuses = state.rates.empStatuses[doc.territory?.lowercase()].orEmpty()
    Fringes(doc, territoryStatuses)
    ScaleMinimums(doc, state)
    PerDiem(doc)
    DistantLocation(doc)
    PactBands(doc)
    DgaDetails(doc)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AgreementHeader(doc: AgreementDocument) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 22.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            ViewTitle(doc.title)
            doc.source?.let {
                Spacer(Modifier.height(10.dp))
                SourceLink(it, blue = false)
            }
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            doc.territory?.let { Chip(it.uppercase(), ChipTone.Grey) }
            doc.currency?.let { Chip(it, ChipTone.Amber) }
            doc.parties.takeIf { it.isNotEmpty() }?.let { Chip(it.joinToString(" / "), ChipTone.Teal) }
        }
    }
}

@Composable
private fun BasicSchedule(doc: AgreementDocument) {
    val brd = doc.block("basic_rate_details")
    val currency = doc.currency
    val daily = DocRead.obj(brd, "daily")
    val weekly = DocRead.obj(brd, "weekly")
    val parts = listOfNotNull(
        DocRead.text(brd, "day_type"),
        DocRead.number(daily, "work_hrs")?.let { "${Js.number(it)}h/day" },
        DocRead.number(weekly, "work_hrs")?.let { "${Js.number(it)}h/week" },
    )
    val range = AgreementFormat.effectiveRange(DocRead.epoch(brd, "effective_from"), DocRead.epoch(brd, "effective_to"))
    val tiers = listOf(
        str(S.desktop_dm_hourly) to "hourly",
        str(S.dm_rates_buyout_mode_daily) to "daily",
        str(S.dm_rates_buyout_mode_weekly) to "weekly",
        str(S.desktop_dm_flat) to "flat_rate",
    ).mapNotNull { (label, key) ->
        DocRead.obj(brd, key)?.let { RateFormat.formatTier(tierFrom(it), currency) }?.let { label to it }
    }
    LegacyCard(icon = DmIcons.Dollar, title = str(S.desktop_dm_basic_rate_schedule)) {
        val nothingPublished = parts.isEmpty() && range.isEmpty() && tiers.isEmpty()
        if (brd == null || nothingPublished) {
            MutedNote(str(S.desktop_dm_no_published_schedule))
            return@LegacyCard
        }
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (parts.isNotEmpty()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    ZillitIcon(ZillitIcons.Clock, size = 12.dp, tint = GRAY_500)
                    ZillitText(text = str(S.schedule), style = DmType.sans(11.sp), color = GRAY_500)
                    ZillitText(
                        text = parts.joinToString(" · "),
                        style = DmType.sans(11.sp, FontWeight.SemiBold),
                        color = legacyInk800(),
                    )
                    if (range.isNotEmpty()) ZillitText(text = "($range)", style = DmType.sans(11.sp), color = GRAY_400)
                }
            }
            if (tiers.isNotEmpty()) TierLine(tiers)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TierLine(tiers: List<Pair<String, String>>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ZillitIcon(DmIcons.Dollar, size = 12.dp, tint = GRAY_500)
            ZillitText(text = str(S.desktop_dm_agreement_rates), style = DmType.sans(11.sp), color = GRAY_500)
        }
        tiers.forEach { (label, value) ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                ZillitText(text = label, style = DmType.sans(11.sp), color = GRAY_400)
                ZillitText(text = value, style = DmType.mono(11.sp, FontWeight.SemiBold), color = legacyInk800())
            }
        }
    }
}

private fun tierFrom(json: JsonObject) = RateTierEntry(
    baseRate = DocRead.number(json, "base_rate"),
    minRate = DocRead.number(json, "min_rate"),
    maxRate = DocRead.number(json, "max_rate"),
    workHours = DocRead.number(json, "work_hrs"),
)

// -- rule tables -------------------------------------------------------------------------

@Composable
private fun RuleCard(
    doc: AgreementDocument,
    key: String,
    title: String,
    icon: ImageVector,
    withTrigger: Boolean,
) {
    val block = doc.ruleBlock(key) ?: return
    LegacyCard(icon = icon, title = title) {
        block.note?.let {
            ZillitText(
                text = it,
                style = DmType.sans(12.sp).copy(fontStyle = FontStyle.Italic, lineHeight = 18.sp),
                color = GRAY_400,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
        RowsTable(block.rows, doc.currency, withTrigger)
    }
}

@Suppress("CyclomaticComplexMethod", "LongMethod")
@Composable
private fun RowsTable(rows: List<JsonObject>, currency: String?, withTrigger: Boolean) {
    if (rows.isEmpty()) {
        MutedNote(str(S.dm_gpr_no_rows))
        return
    }
    val hasBasis = rows.any { Js.truthy(it["basis"]) }
    val hasCap = rows.any { !Js.isNullish(it["min"]) || !Js.isNullish(it["max"]) }
    Box(Modifier.fillMaxWidth().height(1.dp).background(legacyGray100()))
    Row(Modifier.fillMaxWidth().background(legacyGray50())) {
        HeaderCell(str(S.cr_meta_period), 1.6f)
        if (withTrigger) HeaderCell(str(S.desktop_dm_trigger), 1.5f)
        HeaderCell(str(S.dm_allow_rate), 1.2f)
        if (hasBasis) HeaderCell(str(S.dm_rule_basis), 0.9f)
        if (hasCap) HeaderCell(str(S.dm_allow_cap_type), 1f)
    }
    rows.forEach { row ->
        Box(Modifier.fillMaxWidth().height(1.dp).background(legacyGray100()))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            BodyCell(1.6f) {
                ZillitText(
                    text = Js.text(row["label"]).takeUnless { Js.isNullish(row["label"]) }.orEmpty(),
                    style = DmType.sans(12.sp, FontWeight.SemiBold),
                    color = legacyInk800(),
                )
                if (Js.truthy(row["note"])) {
                    ZillitText(
                        text = Js.text(row["note"]),
                        style = DmType.sans(10.sp).copy(lineHeight = 15.sp),
                        color = GRAY_400,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            if (withTrigger) {
                val trigger = AgreementFormat.trigger(AgreementFormat.primaryTrigger(row))
                BodyCell(1.5f) {
                    ZillitText(
                        text = trigger.main,
                        style = DmType.sans(12.sp),
                        color = Color(0xFF4B5563).dark(Color(0xFF9CA3AF)),
                    )
                    if (trigger.meta.isNotEmpty()) ZillitText(
                        text = trigger.meta,
                        style = DmType.sans(10.sp),
                        color = GRAY_400,
                    )
                }
            }
            BodyCell(1.2f) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    ZillitText(
                        text = AgreementFormat.compensation(row, currency),
                        style = DmType.mono(12.sp),
                        color = Color(0xFF374151).dark(Color(0xFFD1D5DB)),
                        maxLines = 1,
                    )
                    if (Js.truthy(row["is_gold_time"])) DmBadge(str(S.desktop_dm_gold_time), DmTone.Amber)
                }
            }
            if (hasBasis) {
                BodyCell(0.9f) {
                    val basis = row["basis"]
                    ZillitText(
                        text = if (Js.truthy(basis)) AgreementFormat.basisLabel(basis) else "—",
                        style = DmType.mono(12.sp),
                        color = Color(0xFF4B5563).dark(Color(0xFF9CA3AF)),
                    )
                }
            }
            if (hasCap) {
                BodyCell(1f) {
                    ZillitText(
                        text = AgreementFormat.cap(row, currency) ?: "—",
                        style = DmType.mono(12.sp),
                        color = Color(0xFF374151).dark(Color(0xFFD1D5DB)),
                    )
                }
            }
        }
    }
}

@Composable
private fun RowScope.HeaderCell(title: String, weight: Float, end: Boolean = false) {
    ZillitText(
        text = title.uppercase(),
        style = DmType.sans(10.sp, FontWeight.Bold, 0.05.em),
        color = GRAY_400,
        textAlign = if (end) TextAlign.End else TextAlign.Start,
        modifier = Modifier.weight(weight).padding(horizontal = 16.dp, vertical = 8.dp),
        maxLines = 1,
    )
}

@Composable
private fun RowScope.BodyCell(weight: Float, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.weight(weight).padding(horizontal = 16.dp, vertical = 10.dp), content = content)
}

// -- fringes ---------------------------------------------------------------------------

@Composable
private fun Fringes(
    doc: AgreementDocument,
    territoryStatuses: List<EmpStatus>,
) {
    val fringes = doc.block("fringes")?.takeIf { it.isNotEmpty() } ?: return
    val map = doc.block("fringe_map")
    val statuses = doc.empStatuses.ifEmpty { territoryStatuses }
    LegacyCard(icon = DmIcons.Dollar, title = str(S.desktop_dm_fringe_packages)) {
        if (map != null) {
            SubHeader(str(S.desktop_dm_status_package_mapping))
            Row(Modifier.fillMaxWidth().background(legacyGray50())) {
                HeaderCell(str(S.dm_quick_sec_employment_sub), 1f)
                HeaderCell(str(S.desktop_dm_fringe_package), 1f)
            }
            map.entries.forEach { (statusId, pkg) ->
                Box(Modifier.fillMaxWidth().height(1.dp).background(legacyGray100()))
                Row(Modifier.fillMaxWidth()) {
                    BodyCell(1f) {
                        ZillitText(
                            text = statuses.firstOrNull { it.id == statusId }?.label ?: statusId,
                            style = DmType.sans(12.sp),
                            color = Color(0xFF374151).dark(Color(0xFFD1D5DB)),
                        )
                    }
                    BodyCell(1f) {
                        val label = (pkg as? JsonPrimitive)?.content
                            ?.let { key -> DocRead.text(DocRead.obj(fringes, key), "label") } ?: "—"
                        ZillitText(text = label, style = DmType.sans(12.sp, FontWeight.Medium), color = legacyInk800())
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(legacyGray100()))
        }
        fringes.entries.forEachIndexed { index, (_, value) ->
            val pkg = value as? JsonObject ?: return@forEachIndexed
            if (index > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(legacyGray100()))
            Row(
                Modifier.fillMaxWidth().background(legacyGray50()).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ZillitText(
                    text = (DocRead.text(pkg, "label") ?: "—").uppercase(),
                    style = DmType.sans(10.sp, FontWeight.Bold, 0.05.em),
                    color = GRAY_500,
                )
                DocRead.text(pkg, "currency")?.let {
                    ZillitText(
                        text = "($it)",
                        style = DmType.sans(10.sp),
                        color = GRAY_400,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }
            FringeItems(DocRead.objects(pkg["items"]))
        }
    }
}

@Suppress("CyclomaticComplexMethod")
@Composable
private fun FringeItems(items: List<JsonObject>) {
    if (items.isEmpty()) {
        MutedNote(str(S.desktop_dm_no_items))
        return
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(legacyGray100()))
    Row(Modifier.fillMaxWidth().background(legacyGray50())) {
        HeaderCell(str(S.desktop_dm_item), 1.6f)
        HeaderCell(str(S.dm_step2_bank_addl_value_hint), 0.8f, end = true)
        HeaderCell(str(S.desktop_dm_percentage), 0.8f, end = true)
        HeaderCell(str(S.desktop_dm_flat), 0.8f, end = true)
        HeaderCell(str(S.dm_rule_basis), 1f)
    }
    items.forEach { item ->
        Box(Modifier.fillMaxWidth().height(1.dp).background(legacyGray100()))
        val rateType = DocRead.text(item, "rate_type")
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            BodyCell(1.6f) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    ZillitText(
                        text = DocRead.text(item, "label") ?: DocRead.text(item, "name").orEmpty(),
                        style = DmType.sans(12.sp),
                        color = Color(0xFF374151).dark(Color(0xFFD1D5DB)),
                    )
                    val hpOnly = DocRead.flag(item, "hp_excl_only") ||
                        DocRead.text(item, "note")?.contains("[hp_excl_only") == true
                    if (hpOnly) DmBadge(str(S.desktop_dm_hp_excl_only), DmTone.Amber)
                }
            }
            val raw = item["raw_value"]
            val value = when {
                Js.isNullish(raw) -> "—"
                rateType == "flat" -> AgreementFormat.groupAmount(raw)
                else -> Js.text(raw)
            }
            val percentage =
                (item["rate_amount"]?.takeUnless(Js::isNullish) ?: item["percentage"]?.takeUnless(Js::isNullish))
                ?.takeIf { (rateType ?: "percentage") == "percentage" }?.let { "${Js.text(it)}%" } ?: "—"
            val flat = (item["rate_amount"]?.takeUnless(Js::isNullish) ?: item["flat"]?.takeUnless(Js::isNullish))
                ?.takeIf { rateType == "flat" }?.let { AgreementFormat.groupAmount(it) } ?: "—"
            NumberCell(value, 0.8f, strong = true)
            NumberCell(percentage, 0.8f)
            NumberCell(flat, 0.8f)
            BodyCell(1f) {
                ZillitText(
                    text = DocRead.text(item, "basis")?.let(AgreementFormat::basisLookup) ?: "—",
                    style = DmType.sans(12.sp),
                    color = GRAY_500,
                )
            }
        }
    }
}

@Composable
private fun RowScope.NumberCell(text: String, weight: Float, strong: Boolean = false) {
    ZillitText(
        text = text,
        style = DmType.mono(12.sp, if (strong) FontWeight.SemiBold else FontWeight.Normal),
        color = if (strong) legacyInk900() else Color(0xFF374151).dark(Color(0xFFD1D5DB)),
        textAlign = TextAlign.End,
        maxLines = 1,
        modifier = Modifier.weight(weight).padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

// -- scale, per diem, distant, PACT, DGA ------------------------------------------------------

@Composable
private fun ScaleMinimums(doc: AgreementDocument, state: DealMemoUiState) {
    val scale = doc.block("scale") ?: return
    LegacyCard(icon = DmIcons.Dollar, title = str(S.desktop_dm_scale_minimums)) {
        Row(Modifier.fillMaxWidth().background(legacyGray50())) {
            HeaderCell(str(S.desktop_dm_position), 1.6f)
            HeaderCell(str(S.dm_rates_buyout_mode_weekly), 1f, end = true)
            HeaderCell(str(S.desktop_dm_prod_fee_wk), 1f, end = true)
        }
        scale.entries.forEach { (role, value) ->
            val entry = value as? JsonObject
            Box(Modifier.fillMaxWidth().height(1.dp).background(legacyGray100()))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                BodyCell(1.6f) {
                    ZillitText(
                        text = state.catalogue.designationLabel(role),
                        style = DmType.sans(12.sp, FontWeight.SemiBold),
                        color = legacyInk800(),
                    )
                    DocRead.text(entry, "note")?.let {
                        ZillitText(text = it, style = DmType.sans(10.sp), color = GRAY_400)
                    }
                }
                NumberCell(
                    entry?.get("weekly")?.takeUnless(Js::isNullish)?.let { "$${AgreementFormat.groupAmount(it)}" }
                        ?: "—",
                    1f,
                )
                NumberCell(
                    entry?.get("prod_fee")?.takeUnless(Js::isNullish)?.let { "$${AgreementFormat.groupAmount(it)}" }
                        ?: "—",
                    1f,
                )
            }
        }
    }
}

private val PER_DIEM = Regex("per[_\\s-]?diem", RegexOption.IGNORE_CASE)

@Composable
private fun PerDiem(doc: AgreementDocument) {
    val allowances = doc.block("allowances")
    val rows = DocRead.objects(allowances?.get("rows")).filter { row ->
        PER_DIEM.containsMatchIn(DocRead.text(row, "id").orEmpty()) ||
            PER_DIEM.containsMatchIn(DocRead.text(row, "label").orEmpty())
    }
    if (rows.isEmpty()) return
    LegacyCard(icon = ZillitIcons.Info, title = str(S.desktop_dm_per_diem)) {
        rows.forEachIndexed { index, row ->
            if (index > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(legacyGray100()))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                ZillitText(
                    text = DocRead.text(row, "label") ?: str(S.desktop_dm_rate_n, index + 1),
                    style = DmType.sans(12.sp),
                    color = GRAY_400,
                    modifier = Modifier.width(144.dp),
                )
                val amount = row["rate_amount"]?.takeUnless(Js::isNullish) ?: row["amount"]
                ZillitText(
                    text = "$${AgreementFormat.groupAmount(amount)}/${DocRead.text(row, "basis") ?: "day"}",
                    style = DmType.mono(12.sp, FontWeight.Medium),
                    color = legacyInk800(),
                )
            }
        }
        DocRead.text(allowances, "note")?.let {
            ZillitText(
                text = it,
                style = DmType.sans(12.sp),
                color = GRAY_400,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            )
        }
    }
}

@Composable
private fun DistantLocation(doc: AgreementDocument) {
    val distant = doc.block("distant_location")?.takeIf { DocRead.flag(it, "applicable") } ?: return
    LegacyCard(icon = ZillitIcons.Pin, title = str(S.desktop_dm_distant_location_lower)) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DocRead.text(distant, "legal_warning")?.let { WarningBox(it) }
            DocRead.text(distant, "resident_note")?.let { LabelledText(str(S.desktop_dm_resident_location), it) }
            DocRead.text(distant, "overseas_note")?.let { LabelledText(str(S.desktop_dm_overseas_location), it) }
            DocRead.text(distant, "travel_note")?.let {
                ZillitText(text = it, style = DmType.sans(12.sp), color = Color(0xFF4B5563).dark(Color(0xFF9CA3AF)))
            }
            DocRead.text(distant, "note")?.let {
                ZillitText(text = it, style = DmType.sans(12.sp).copy(fontStyle = FontStyle.Italic), color = GRAY_500)
            }
            DocRead.text(distant, "idle_days_note")?.let {
                val applicable = DocRead.flag(distant, "idle_days_applicable")
                val idleLabel = if (applicable) {
                    str(S.desktop_dm_idle_days_applicable)
                } else {
                    str(S.desktop_dm_idle_days_not_applicable)
                }
                LabelledText(idleLabel, it)
            }
        }
        val zones = DocRead.objects(distant["travel_zones"])
        if (zones.isNotEmpty()) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(legacyGray100()))
            SectionLabel(str(S.desktop_dm_travel_zones))
            zones.forEachIndexed { index, zone ->
                if (index > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(legacyGray100()))
                TravelZone(zone)
            }
        }
        val rows = DocRead.objects(distant["rows"])
        if (rows.isNotEmpty()) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(legacyGray100()))
            SectionLabel(str(S.desktop_dm_distant_specific_rows))
            RowsTable(rows, doc.currency, withTrigger = true)
        }
    }
}

@Composable
private fun TravelZone(zone: JsonObject) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ZillitText(
                text = DocRead.text(zone, "label").orEmpty(),
                style = DmType.sans(12.sp, FontWeight.SemiBold),
                color = legacyInk800(),
            )
            DocRead.text(zone, "clause")?.let { DmBadge(it, DmTone.Gray) }
            if (DocRead.flag(zone, "default")) DmBadge(str(S.desktop_email_format_default), DmTone.Green)
        }
        DocRead.text(zone, "desc")?.let {
            ZillitText(text = it, style = DmType.sans(12.sp), color = GRAY_500, modifier = Modifier.padding(top = 2.dp))
        }
        DocRead.text(zone, "restriction")?.let {
            ZillitText(
                text = it,
                style = DmType.sans(10.sp, FontWeight.Medium),
                color = Color(0xFFB45309),
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun PactBands(doc: AgreementDocument) {
    val pact = doc.block("pact") ?: return
    LegacyCard(icon = DmIcons.Apartment, title = str(S.desktop_dm_pact_budget_bands)) {
        Row(Modifier.fillMaxWidth().background(legacyGray50())) {
            HeaderCell(str(S.desktop_dm_band), 1.2f)
            HeaderCell(str(S.desktop_dm_budget_threshold), 1.2f)
            HeaderCell(str(S.desktop_dm_ot_rates), 1f)
            HeaderCell(str(S.desktop_bank_holiday), 1f)
        }
        DocRead.objects(pact["bands"]).forEach { band ->
            Box(Modifier.fillMaxWidth().height(1.dp).background(legacyGray100()))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                BodyCell(1.2f) {
                    ZillitText(
                        text = DocRead.text(band, "label").orEmpty(),
                        style = DmType.sans(12.sp, FontWeight.Bold),
                        color = legacyInk800(),
                        maxLines = 1,
                    )
                    DocRead.text(band, "notes")?.let {
                        ZillitText(text = it, style = DmType.sans(10.sp), color = GRAY_400)
                    }
                }
                BodyCell(1.2f) {
                    ZillitText(
                        text = DocRead.text(band, "threshold") ?: "—",
                        style = DmType.sans(12.sp),
                        color = Color(0xFF4B5563).dark(Color(0xFF9CA3AF)),
                    )
                }
                BodyCell(1f) {
                    val min = band["ot_min"]?.takeUnless(Js::isNullish)
                    val max = band["ot_max"]?.takeUnless(Js::isNullish)
                    ZillitText(
                        text = if (min != null && max != null) {
                            "£${AgreementFormat.groupAmount(min)}–£${AgreementFormat.groupAmount(max)}/hr"
                        } else {
                            "—"
                        },
                        style = DmType.mono(12.sp),
                        color = Color(0xFF374151).dark(Color(0xFFD1D5DB)),
                        maxLines = 1,
                    )
                }
                BodyCell(1f) {
                    ZillitText(
                        text = DocRead.text(band, "bank_holiday") ?: "—",
                        style = DmType.sans(12.sp),
                        color = GRAY_500,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DgaDetails(doc: AgreementDocument) {
    val dga = doc.block("dga") ?: return
    LegacyCard(icon = ZillitIcons.Info, title = str(S.desktop_dm_dga_details)) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val bases = DocRead.objects(dga["coa_basis"])
            if (dga["coa_basis"] != null && dga["coa_basis"] !is JsonNull) {
                Column {
                    SmallCaps(str(S.desktop_dm_completion_of_assignment_bases))
                    Spacer(Modifier.height(6.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        bases.forEach { DmBadge(DocRead.text(it, "label") ?: "—", DmTone.Gray) }
                    }
                }
            }
            DocRead.obj(dga, "distant_allowance")?.let { allowance ->
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    ZillitText(
                        text = str(S.desktop_dm_distant_allowance),
                        style = DmType.sans(12.sp),
                        color = GRAY_400,
                        modifier = Modifier.width(144.dp),
                    )
                    val amount = allowance["daily"]?.takeUnless(Js::isNullish) ?: allowance["amount"]
                    ZillitText(
                        text = str(
                            S.desktop_dm_amount_per_day_from,
                            "$${AgreementFormat.groupAmount(amount)}",
                            Js.text(allowance["effective_from"]),
                        ),
                        style = DmType.sans(12.sp, FontWeight.Medium),
                        color = legacyInk800(),
                    )
                }
            }
        }
    }
}

// -- small pieces ------------------------------------------------------------------------

@Composable
private fun MutedNote(text: String) {
    ZillitText(
        text = text,
        style = DmType.sans(12.sp).copy(fontStyle = FontStyle.Italic),
        color = GRAY_400,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

@Composable
private fun SubHeader(text: String) {
    Box(Modifier.fillMaxWidth().background(legacyGray50()).padding(horizontal = 16.dp, vertical = 8.dp)) {
        ZillitText(text = text.uppercase(), style = DmType.sans(10.sp, FontWeight.Bold, 0.05.em), color = GRAY_400)
    }
}

@Composable
private fun SectionLabel(text: String) {
    ZillitText(
        text = text.uppercase(),
        style = DmType.sans(10.sp, FontWeight.Bold, 0.05.em),
        color = GRAY_400,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 4.dp),
    )
}

@Composable
private fun SmallCaps(text: String) {
    ZillitText(text = text.uppercase(), style = DmType.sans(10.sp, FontWeight.Bold, 0.05.em), color = GRAY_400)
}

@Composable
private fun LabelledText(label: String, text: String) {
    Column {
        SmallCaps(label)
        Spacer(Modifier.height(4.dp))
        ZillitText(
            text = text,
            style = DmType.sans(12.sp).copy(lineHeight = 18.sp),
            color = Color(0xFF4B5563).dark(Color(0xFF9CA3AF)),
        )
    }
}

@Composable
private fun WarningBox(text: String) {
    val dark = ZillitTheme.colors.isDark
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (dark) Color(0xFFFC9404).copy(alpha = 0.08f) else Color(0xFFFFFBEB))
            .border(1.dp, if (dark) Color(0xFFFC9404).copy(alpha = 0.25f) else Color(0xFFFDE68A), shape)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        ZillitText(
            text = text,
            style = DmType.sans(12.sp, FontWeight.Medium).copy(lineHeight = 18.sp),
            color = if (dark) Color(0xFFFBBF24) else Color(0xFF92400E),
        )
    }
}

@Composable
private fun AgreementSkeleton() {
    Column {
        Bar(width = 340.dp, height = 20.dp)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Bar(width = 140.dp, height = 10.dp)
            Bar(width = 48.dp, height = 21.dp, radius = 6.dp, color = dm.track)
            Bar(width = 48.dp, height = 21.dp, radius = 6.dp, color = dm.amberSoft)
        }
        Spacer(Modifier.height(22.dp))
        listOf(4, 6).forEach { lines ->
            LegacyCard(icon = DmIcons.Dollar, title = " ") {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    repeat(lines) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            listOf(0.30f, 0.18f, 0.14f, 0.14f).forEach { fraction ->
                                Box(
                                    Modifier
                                        .weight(fraction)
                                        .height(10.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(dm.skeleton),
                                )
                            }
                            Spacer(Modifier.weight(0.24f))
                        }
                    }
                }
            }
        }
    }
}

/** The dark theme's remap of a legacy gray. */
@Composable
private fun Color.dark(darkColor: Color): Color = if (ZillitTheme.colors.isDark) darkColor else this
