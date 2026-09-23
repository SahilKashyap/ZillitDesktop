package com.zillit.desktop.feature.dealmemo.ui.pages.builder

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.dealmemo.domain.authoring.BuilderSeeds
import com.zillit.desktop.feature.dealmemo.domain.authoring.DealStructureRules
import com.zillit.desktop.feature.dealmemo.domain.rates.Js
import com.zillit.desktop.feature.dealmemo.ui.BuilderEvent
import com.zillit.desktop.feature.dealmemo.ui.DealMemoEvent
import com.zillit.desktop.feature.dealmemo.ui.DealMemoUiState
import com.zillit.desktop.feature.dealmemo.ui.builder.BuilderState
import com.zillit.desktop.feature.dealmemo.ui.components.DmType
import com.zillit.desktop.feature.dealmemo.ui.components.rememberHover
import com.zillit.desktop.feature.dealmemo.ui.pages.preview.FileTypeBadge
import kotlin.math.round
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A deal type the picker offers. */
private data class DealTypeOption(val id: String, val name: String, val sub: String, val icon: ImageVector)

private val DEAL_TYPE_OPTIONS get() = listOf(
    DealTypeOption(
        "weekly",
        str(S.desktop_dm_deal_type_weekly_rolling),
        str(S.dm_ds_type_weekly_desc),
        ZillitIcons.Calendar,
    ),
    DealTypeOption(
        "fixed",
        str(S.desktop_dm_deal_type_fixed_term),
        str(S.dm_ds_type_fixed_desc),
        ZillitIcons.Pin,
    ),
    DealTypeOption(
        "dayplayer",
        str(S.desktop_dm_deal_type_day_player),
        str(S.dm_ds_type_dayplayer_desc),
        ZillitIcons.Camera,
    ),
    DealTypeOption("buyout", str(S.desktop_dm_deal_type_buy_out), str(S.dm_ds_type_buyout_desc), ZillitIcons.Wallet),
    DealTypeOption(
        "picture",
        str(S.desktop_dm_deal_type_picture_deal),
        str(S.dm_ds_type_picture_desc),
        ZillitIcons.Play,
    ),
    DealTypeOption(
        "boxrental",
        str(S.desktop_dm_deal_type_box_rental_only),
        str(S.dm_ds_type_boxrental_desc),
        ZillitIcons.Grid,
    ),
)

private val NOTICE_CHIPS get() = listOf(
    "none" to str(S.dm_rule_increment_none),
    "1week" to str(S.desktop_dm_one_week),
    "2week" to str(S.desktop_dm_two_weeks),
    "custom" to str(S.custom),
)

private val NOTICE_TEXTS get() = mapOf(
    "none" to str(S.dm_ds_notice_none_desc),
    "1week" to str(S.desktop_dm_notice_one_week_desc),
    "2week" to str(S.dm_ds_notice_2week_desc),
    "custom" to str(S.desktop_dm_custom_notice_terms_specify_below),
)

/**
 * Deal Structure (`Step4DealStructure.jsx`) with the page's memo dates: the
 * deal type, the dates and the phase schedule, custom days, the notice
 * period, and a long-form contract for picture and box-rental deals.
 */
@Composable
internal fun DealStructureEditor(
    state: DealMemoUiState,
    builder: BuilderState,
    ops: FormOps,
    onEvent: (DealMemoEvent) -> Unit,
) {
    val form = builder.form
    val type = form.text("dealType")
    val flatContract = type == "picture" || type == "boxrental"
    DealTypeCard(type, ops)
    DatesCard(state, builder, ops)
    if (!flatContract) NoticeCard(builder, ops)
    if (flatContract) LongFormContractCard(builder, ops, onEvent)
    CardBlock(title = str(S.dm_ds_card_additional_notes)) {
        BuilderTextArea(
            value = form.text("additionalNotes"),
            onValueChange = { ops.set("additionalNotes", it) },
            placeholder = str(S.desktop_dm_any_deal_specific_terms_agent_instructions_or),
        )
    }
    MemoDates(builder, ops)
}

@Composable
private fun DealTypeCard(type: String, ops: FormOps) {
    val selected = DEAL_TYPE_OPTIONS.firstOrNull { it.id == type }
    CardBlock(
        title = str(S.dm_ds_card_type),
        tag = selected?.name ?: str(S.desktop_dm_deal_type_weekly_rolling),
        tone = BuilderTone.Gold,
    ) {
        RichSelect(
            options = DEAL_TYPE_OPTIONS.map {
                PickOption(key = it.id, label = it.name, sub = it.sub, search = "${it.name} ${it.sub}")
            },
            selectedKey = type.ifEmpty { null },
            onPick = { ops.set("dealType", it.orEmpty()) },
            placeholder = str(S.dm_ds_type_placeholder),
            dropdownWidth = 420.dp,
            leading = { selected?.let { TypeTile(it.icon, active = true) } },
            rowLeading = { option ->
                DEAL_TYPE_OPTIONS.firstOrNull { it.id == option.key }?.let { TypeTile(it.icon, active = it.id == type) }
            },
        )
    }
}

@Composable
private fun TypeTile(icon: ImageVector, active: Boolean) {
    val p = bp
    val shape = RoundedCornerShape(9.dp)
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(shape)
            .background(if (active) p.amberSoft else p.infoBox)
            .border(1.dp, if (active) p.amberRing else p.hairline, shape),
        contentAlignment = Alignment.Center,
    ) { ZillitIcon(icon, size = 13.dp, tint = if (active) p.cta else p.muted) }
}

@Composable
private fun DatesCard(state: DealMemoUiState, builder: BuilderState, ops: FormOps) {
    val form = builder.form
    val p = bp
    CardBlock(title = str(S.dm_ds_card_dates)) {
        BuilderGrid(columns = 2) {
            cell {
                Field(str(S.dm_ds_start_date), required = true) {
                    IsoDateInput(value = form.text("dealStart"), onChange = { ops.set("dealStart", it) })
                }
            }
            cell {
                Field(str(S.dm_ds_end_date), required = true) {
                    IsoDateInput(
                        value = form.text("dealEnd"),
                        onChange = { ops.set("dealEnd", it) },
                        min = BuilderSeeds.nextDay(form.text("dealStart")).ifEmpty { null },
                    )
                }
            }
        }
        Rule(p.hairline, Modifier.padding(vertical = 14.dp))
        ToggleRow(
            title = str(S.dm_ds_phase_schedule_title),
            sub = str(S.desktop_dm_turn_the_toggle_on_to_define_production),
            checked = form.flag("schedOn"),
            onChange = { ops.set("schedOn", it) },
        )
        if (form.flag("schedOn")) PhaseTable(builder, ops)
        CustomDays(state, builder, ops)
        Totals(builder)
    }
}

@Composable
private fun PhaseTable(builder: BuilderState, ops: FormOps) {
    val form = builder.form
    val errors = DealStructureRules.phaseErrors(form)
    Column(Modifier.padding(top = 8.dp)) {
        TableHead(
            listOf(
                str(S.desktop_dm_phase) to PHASE_COLUMN,
                str(S.dm_ds_phase_start_hint) to null,
                str(S.dm_ds_phase_end_hint) to null,
                str(S.dm_ds_unit_days) to DAYS_COLUMN,
            ),
        )
        DealStructureRules.PHASES.forEachIndexed { index, phase ->
            val rowErrors = errors[phase.label].orEmpty()
            val days = BuilderSeeds.inclusiveDays(form.text(phase.startKey), form.text(phase.endKey))
            Column(Modifier.padding(vertical = 6.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    ZillitText(
                        text = phase.label.uppercase(),
                        style = DmType.display(10.sp, FontWeight.Bold, 0.05.em),
                        color = bp.ink2,
                        modifier = Modifier.width(PHASE_COLUMN.dp),
                    )
                    IsoDateInput(
                        value = form.text(phase.startKey),
                        onChange = { value -> ops.edit { it.with(phase.startKey, value) } },
                        min = DealStructureRules.phaseStartMin(form, index),
                        max = form.text("dealEnd").ifEmpty { null },
                        error = rowErrors.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                    )
                    IsoDateInput(
                        value = form.text(phase.endKey),
                        onChange = { value -> ops.edit { it.with(phase.endKey, value) } },
                        min = DealStructureRules.phaseEndMin(form, index),
                        max = form.text("dealEnd").ifEmpty { null },
                        error = rowErrors.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                    )
                    DayCount(days, rowErrors.isNotEmpty())
                }
                rowErrors.forEach { message ->
                    Advisory(message, Modifier.padding(start = (PHASE_COLUMN + 10).dp, top = 4.dp))
                }
            }
            Rule(bp.tile)
        }
        if (errors.values.any { it.isNotEmpty() }) WarningBanner(DealStructureRules.PHASE_BANNER)
    }
}

@Suppress("LongMethod")
@Composable
private fun CustomDays(state: DealMemoUiState, builder: BuilderState, ops: FormOps) {
    val form = builder.form
    val p = bp
    val rows = form.objects("customDays")
    val defaults = builder.dealDefaultCustomDays
        ?: (state.projectSettings.view.productionSchedule?.get("custom_days") as? JsonArray)?.toList().orEmpty()
    Column(Modifier.padding(top = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                ZillitText(
                    text = str(S.dm_ds_custom_days_title),
                    style = DmType.sans(11.sp, FontWeight.Bold),
                    color = p.ink,
                )
                ZillitText(
                    text = str(S.desktop_dm_named_date_ranges_for_this_crew_member),
                    style = DmType.sans(11.sp),
                    color = p.muted,
                )
            }
            if (defaults.isNotEmpty()) {
                SmallTextButton(str(S.desktop_dm_reset_to_production_days)) {
                    ops.edit { it.with("customDays", BuilderSeeds.customDaysFromProduction(JsonArray(defaults))) }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        if (rows.isEmpty()) {
            EmptyNote(str(S.desktop_dm_no_custom_days_on_this_deal))
        } else {
            TableHead(
                listOf(
                    str(S.name) to null,
                    str(S.dm_ds_phase_start_hint) to null,
                    str(S.dm_ds_phase_end_hint) to null,
                    str(S.dm_ds_unit_days) to DAYS_COLUMN,
                    "" to REMOVE_COLUMN,
                ),
            )
            rows.forEachIndexed { index, row ->
                val errors = DealStructureRules.customDayErrors(row, form.text("dealStart"), form.text("dealEnd"))
                val start = text(row["start_date"])
                val end = text(row["end_date"])
                Row(
                    modifier = Modifier.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    BuilderInput(
                        value = text(row["name"]),
                        onValueChange = { value ->
                            ops.edit {
                                it.with("customDays", patchCustomDay(it.list("customDays"), index, "name", value))
                            }
                        },
                        placeholder = str(S.desktop_hub_e_g_night_shoot),
                        modifier = Modifier.weight(1f),
                    )
                    IsoDateInput(
                        value = start,
                        onChange = { value ->
                            ops.edit {
                                it.with("customDays", patchCustomDay(it.list("customDays"), index, "start_date", value))
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                    IsoDateInput(
                        value = end,
                        onChange = { value ->
                            ops.edit {
                                it.with("customDays", patchCustomDay(it.list("customDays"), index, "end_date", value))
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                    DayCount(BuilderSeeds.inclusiveDays(start, end), errors.isNotEmpty())
                    RemoveButton(tooltip = str(S.desktop_remove_custom_day), size = REMOVE_COLUMN.dp, onClick = {
                        ops.edit { form ->
                            form.with(
                                "customDays",
                                JsonArray(form.list("customDays").filterIndexed { i, _ -> i != index }),
                            )
                        }
                    })
                }
            }
        }
        DealStructureRules.customDayBanner(rows, form.text("dealStart"), form.text("dealEnd"))?.let {
            WarningBanner(it)
        }
        SmallTextButton(str(S.desktop_dm_add_custom_day_plus), modifier = Modifier.padding(top = 8.dp)) {
            ops.edit { form ->
                form.with(
                    "customDays",
                    JsonArray(
                        form.list("customDays") + buildJsonObject {
                            put("name", "")
                            put("start_date", "")
                            put("end_date", "")
                        },
                    ),
                )
            }
        }
    }
}

private fun patchCustomDay(rows: List<JsonElement>, index: Int, key: String, value: String): JsonArray =
    JsonArray(
        rows.mapIndexed { i, row ->
            if (i == index && row is JsonObject) JsonObject(row + (key to JsonPrimitive(value))) else row
        },
    )

@Composable
private fun Totals(builder: BuilderState) {
    val form = builder.form
    val p = bp
    val span = BuilderSeeds.inclusiveDays(form.text("dealStart"), form.text("dealEnd"))
    val phaseTotal = DealStructureRules.PHASES.sumOf {
        BuilderSeeds.inclusiveDays(form.text(it.startKey), form.text(it.endKey))
    }
    val schedOn = form.flag("schedOn")
    Column(Modifier.padding(top = 16.dp)) {
        Rule(p.hairline)
        Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (schedOn && phaseTotal > 0) TotalRow(str(S.dm_ds_phase_total), "${phaseTotal}d", p.ink)
            TotalRow(str(S.dm_ds_deal_span), if (span > 0) "${span}d" else DASH, p.cta)
            TotalRow(str(S.dm_ds_approx_weeks), if (span > 0) oneDecimal(span / WEEK_DAYS) else DASH, p.cta)
            val diff = span - phaseTotal
            val mismatched = span > 0 && diff != 0
            if (schedOn && phaseTotal > 0 && mismatched) {
                if (diff > 0) {
                    TotalRow(str(S.desktop_dm_days_unscheduled, diff), "+$diff", p.cta, labelColor = p.muted)
                } else {
                    TotalRow("${-diff}d over deal span", "$diff", p.red, labelColor = p.red)
                }
            }
        }
    }
}

@Composable
private fun TotalRow(label: String, value: String, valueColor: Color, labelColor: Color = bp.ink2) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        ZillitText(
            text = label,
            style = DmType.sans(12.sp, FontWeight.SemiBold),
            color = labelColor,
            modifier = Modifier.weight(1f),
        )
        ZillitText(text = value, style = DmType.mono(12.sp, FontWeight.Bold), color = valueColor)
    }
}

@Composable
private fun NoticeCard(builder: BuilderState, ops: FormOps) {
    val form = builder.form
    val notice = form.text("noticeType")
    CardBlock(
        title = str(S.dm_ds_card_notice),
        tag = NOTICE_CHIPS.firstOrNull { it.first == notice }?.second ?: str(S.standard),
        tone = BuilderTone.Blue,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 14.dp)) {
            NOTICE_CHIPS.forEach { (id, label) ->
                ChoiceChip(label, selected = notice == id, onClick = { ops.set("noticeType", id) })
            }
        }
        NOTICE_TEXTS[notice]?.let { BuilderAlert(it) }
        if (notice == "custom") {
            Field(str(S.desktop_dm_custom_notice_period), required = true, modifier = Modifier.padding(top = 14.dp)) {
                NumberWithUnit(
                    value = form.text("noticeCustomValue"),
                    onValue = { ops.set("noticeCustomValue", it) },
                    placeholder = str(S.dm_ds_notice_value_hint),
                    unit = form.text("noticeCustomUnit"),
                    units = listOf(
                        "day" to str(S.dm_ds_unit_days),
                        "week" to str(S.dm_ds_unit_weeks),
                        "month" to str(S.dm_ds_unit_months),
                    ),
                    onUnit = { ops.set("noticeCustomUnit", it) },
                )
            }
        }
        if (notice != "none") {
            Rule(bp.hairline, Modifier.padding(vertical = 14.dp))
            Field(
                str(S.desktop_dm_notice_reminder),
                hint = str(S.desktop_dm_well_remind_you_this_far_ahead_of),
            ) {
                NumberWithUnit(
                    value = form.text("noticeReminderValue"),
                    onValue = { ops.set("noticeReminderValue", it) },
                    placeholder = str(S.desktop_dm_e_g_24),
                    unit = form.text("noticeReminderUnit"),
                    units = listOf("hour" to str(S.dm_ds_unit_hours), "day" to str(S.dm_ds_unit_days)),
                    onUnit = { ops.set("noticeReminderUnit", it) },
                )
            }
        }
    }
}

@Composable
private fun NumberWithUnit(
    value: String,
    onValue: (String) -> Unit,
    placeholder: String,
    unit: String,
    units: List<Pair<String, String>>,
    onUnit: (String) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        BuilderInput(
            value = value,
            onValueChange = { typed -> if (typed.all { it.isDigit() }) onValue(typed) },
            placeholder = placeholder,
            modifier = Modifier.weight(1f),
        )
        NativeSelect(
            value = unit,
            options = units.map { PickOption(it.first, it.second) },
            onPick = onUnit,
            modifier = Modifier.width(116.dp),
            menuWidth = 160.dp,
        )
    }
}

@Suppress("LongMethod")
@Composable
private fun LongFormContractCard(builder: BuilderState, ops: FormOps, onEvent: (DealMemoEvent) -> Unit) {
    val p = bp
    val contract = builder.form.obj("longFormContract")
    CardBlock(title = str(S.dm_ds_lfc_title), tag = str(S.dm_ds_lfc_tag), tone = BuilderTone.Teal) {
        BuilderAlert(
            str(S.desktop_dm_this_deal_type_typically_uses_a_bespoke),
            modifier = Modifier.padding(bottom = 14.dp),
        )
        if (contract == null) {
            val (source, hovered) = rememberHover()
            val shape = RoundedCornerShape(12.dp)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .dashedBorder(if (hovered) p.sectionLabel else p.hairline, 12.dp, 2.dp)
                    .clip(shape)
                    .hoverable(source)
                    .clickable(interactionSource = source, indication = null) {
                        if (!builder.contractUploading) onEvent(BuilderEvent.PickLongFormContract)
                    }
                    .pointerHoverIcon(PointerIcon.Hand)
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ZillitIcon(ZillitIcons.File, size = 22.dp, tint = p.muted)
                ZillitText(
                    text = if (builder.contractUploading) str(S.dm_nda_uploading) else str(S.dm_ds_lfc_dropzone),
                    style = DmType.display(13.sp, FontWeight.Bold),
                    color = p.ink,
                )
                ZillitText(text = str(S.desktop_dm_pdf_doc_or_docx), style = DmType.sans(11.5.sp), color = p.muted)
                Box(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(p.amberSoft)
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    ZillitText(
                        text = str(S.dm_docs_upload_browse),
                        style = DmType.sans(11.5.sp, FontWeight.Bold),
                        color = p.cta,
                    )
                }
            }
        } else {
            val name = text(contract["name"]).ifEmpty { str(S.contract_text) }
            val kind = text(contract["content_subtype"]).uppercase().ifEmpty { "FILE" }
            val shape = RoundedCornerShape(10.dp)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(p.infoBox)
                    .border(1.dp, p.hairline, shape)
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                FileTypeBadge(text(contract["content_subtype"]).ifEmpty { name.substringAfterLast('.', "") })
                Column(Modifier.weight(1f)) {
                    ZillitText(
                        text = name,
                        style = DmType.sans(13.sp, FontWeight.SemiBold),
                        color = p.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    ZillitText(text = kind, style = DmType.mono(10.5.sp), color = p.muted)
                }
                SmallTextButton(str(S.dm_docs_view)) { onEvent(BuilderEvent.ViewLongFormContract) }
                if (builder.contractUploading) {
                    ZillitSpinner(size = 14.dp, color = p.cta)
                } else {
                    SmallTextButton(str(S.dm_nda_action_replace)) { onEvent(BuilderEvent.PickLongFormContract) }
                }
                RemoveButton(
                    tooltip = str(S.desktop_dm_remove_contract),
                    size = 30.dp,
                    onClick = { ops.set("longFormContract", JsonNull) },
                )
            }
        }
    }
}

/** The memo's dates on a deal page: the issue date is stamped, the due date is chosen. */
@Composable
private fun MemoDates(builder: BuilderState, ops: FormOps) {
    val form = builder.form
    BuilderGrid(columns = 2, modifier = Modifier.padding(top = 4.dp), verticalAlignment = Alignment.Top) {
        cell {
            Field(str(S.desktop_dm_deal_memo_date)) {
                IsoDateInput(value = form.text("dealMemoDate"), onChange = {}, enabled = false)
                HintText(str(S.desktop_dm_stamped_as_todays_date_when_the_memo), Modifier.padding(top = 6.dp))
            }
        }
        cell {
            Field(str(S.dm_prev_date_due)) {
                IsoDateInput(value = form.text("completionDue"), onChange = { ops.set("completionDue", it) })
            }
        }
    }
}

@Composable
private fun TableHead(columns: List<Pair<String, Int?>>) {
    Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        columns.forEach { (title, width) ->
            val style = DmType.display(9.sp, FontWeight.Bold, 0.08.em)
            if (width != null) {
                ZillitText(
                    text = title.uppercase(),
                    style = style,
                    color = bp.muted,
                    textAlign = if (title == str(S.dm_ds_unit_days)) TextAlign.End else TextAlign.Start,
                    modifier = Modifier.width(width.dp),
                )
            } else {
                ZillitText(text = title.uppercase(), style = style, color = bp.muted, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun DayCount(days: Int, error: Boolean) {
    val p = bp
    ZillitText(
        text = if (days > 0) "${days}d" else DASH,
        style = DmType.mono(11.sp, FontWeight.Medium),
        color = when {
            days <= 0 -> p.muted
            error -> p.red
            else -> p.ink
        },
        textAlign = TextAlign.End,
        modifier = Modifier.width(DAYS_COLUMN.dp),
    )
}

@Composable
private fun Advisory(text: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        ZillitIcon(ZillitIcons.Warning, size = 10.dp, tint = bp.red)
        ZillitText(text = text, style = DmType.sans(10.sp), color = bp.red)
    }
}

@Composable
private fun WarningBanner(text: String) {
    val p = bp
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier = Modifier
            .padding(top = 8.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(p.redSoft)
            .border(1.dp, p.redBorder, shape)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ZillitIcon(ZillitIcons.Warning, size = 10.dp, tint = p.red, modifier = Modifier.padding(top = 2.dp))
        ZillitText(
            text = text,
            style = DmType.sans(10.5.sp).copy(lineHeight = 15.sp),
            color = p.redInk,
            modifier = Modifier.weight(1f),
        )
    }
}

/** A small amber text action — `+ Add custom day`, `Reset to production days`, `View`. */
@Composable
internal fun SmallTextButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val p = bp
    val (source, hovered) = rememberHover()
    ZillitText(
        text = text,
        style = DmType.sans(11.sp, FontWeight.Bold),
        color = p.sectionLabel,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (hovered) p.amberSoft else Color.Transparent)
            .hoverable(source)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 6.dp, vertical = 3.dp),
    )
}

private fun text(value: JsonElement?): String = when (value) {
    null, JsonNull -> ""
    else -> Js.text(value)
}

private fun oneDecimal(value: Double): String {
    val tenths = round(value * TENTHS) / TENTHS
    val whole = tenths.toLong()
    val fraction = round((tenths - whole) * TENTHS).toInt()
    return "$whole.$fraction"
}

private const val DASH = "—"
private const val PHASE_COLUMN = 48
private const val DAYS_COLUMN = 40
private const val REMOVE_COLUMN = 30
private const val WEEK_DAYS = 5.0
private const val TENTHS = 10.0
