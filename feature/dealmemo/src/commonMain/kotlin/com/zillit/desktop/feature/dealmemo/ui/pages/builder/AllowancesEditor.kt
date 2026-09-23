package com.zillit.desktop.feature.dealmemo.ui.pages.builder

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.dealmemo.domain.authoring.AllowanceConversion
import com.zillit.desktop.feature.dealmemo.domain.authoring.DealForm
import com.zillit.desktop.feature.dealmemo.domain.authoring.DealValidators
import com.zillit.desktop.feature.dealmemo.domain.preview.CrewFormValues
import com.zillit.desktop.feature.dealmemo.domain.rates.Js
import com.zillit.desktop.feature.dealmemo.domain.rates.RateFormat
import com.zillit.desktop.feature.dealmemo.ui.DealMemoUiState
import com.zillit.desktop.feature.dealmemo.ui.builder.BuilderState
import com.zillit.desktop.feature.dealmemo.ui.components.DmType
import com.zillit.desktop.feature.dealmemo.ui.components.rememberHover
import com.zillit.desktop.feature.dealmemo.ui.pages.crew.MoneyInput
import com.zillit.desktop.feature.dealmemo.ui.pages.preview.CoaCodeField
import kotlin.time.Clock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** `BASIS_OPTIONS` — a retired basis still shows, disabled, until it is re-selected. */
internal fun basisOptions(current: String): List<PickOption> {
    val options = listOf(
        PickOption("day", str(S.dm_rates_buyout_mode_daily)),
        PickOption("week", str(S.desktop_5_days_week)),
        PickOption("3in5", "3 in 5"),
        PickOption("mile", str(S.desktop_per_mile)),
    )
    val legacy = when (current.trim().lowercase()) {
        "hour" -> str(S.desktop_hub_per_hour_retired_re_select_dashes)
        "night" -> str(S.desktop_hub_per_night_retired_re_select_dashes)
        "event" -> str(S.desktop_hub_per_event_retired_re_select_dashes)
        else -> null
    }
    return if (legacy != null) options + PickOption(current.trim().lowercase(), legacy, disabled = true) else options
}

internal val ALLOWANCE_APPLIES get() = listOf(
    PickOption("shoot", str(S.desktop_shoot_day_only)),
    PickOption("non_shoot", str(S.desktop_non_shoot_day)),
    PickOption("shoot_non_shoot", str(S.desktop_hub_shoot_non_shoot_days)),
)

internal val RENTAL_APPLIES get() =
    listOf(
        PickOption("shoot", str(S.desktop_shoot_day_only)),
        PickOption("full_production", str(S.desktop_full_contract)),
    )

/**
 * Allowances & Rentals (`Step6Allowances.jsx`): the deal's entitlement rows —
 * Production Setup's, toggled and tuned, and its own custom ones.
 */
@Composable
internal fun AllowancesEditor(state: DealMemoUiState, builder: BuilderState, ops: FormOps) {
    val form = builder.form
    val symbol = RateFormat.currencySymbol(form.text("currency"))
    val nominal = state.viewer.isAccountant
    builder.conversion?.let { ConversionBanner(it) }
    EntitlementCard(
        title = str(S.desktop_hub_equipment_rentals_box_rental),
        key = "rentals",
        rental = true,
        state = state,
        builder = builder,
        symbol = symbol,
        nominal = nominal,
        ops = ops,
    )
    EntitlementCard(
        title = str(S.dm_section_allowances),
        key = "allowances",
        rental = false,
        state = state,
        builder = builder,
        symbol = symbol,
        nominal = nominal,
        ops = ops,
    )
}

@Composable
private fun ConversionBanner(conversion: AllowanceConversion) {
    val text = when (conversion) {
        is AllowanceConversion.Converted ->
            str(S.desktop_dm_allowances_converted_note, conversion.from, conversion.to, Js.number(conversion.rate))
        is AllowanceConversion.NoRate ->
            str(S.desktop_dm_allowances_no_rate_note, conversion.to, conversion.from)
    }
    BuilderAlert(
        text,
        icon = if (conversion is AllowanceConversion.NoRate) ZillitIcons.Warning else ZillitIcons.Info,
        modifier = Modifier.padding(bottom = 14.dp),
    )
}

@Suppress("LongMethod")
@Composable
private fun EntitlementCard(
    title: String,
    key: String,
    rental: Boolean,
    state: DealMemoUiState,
    builder: BuilderState,
    symbol: String,
    nominal: Boolean,
    ops: FormOps,
) {
    val p = bp
    val rows = builder.form.objects(key)
    val kind = if (rental) "rental" else "allowance"
    CardBlock(title = title) {
        val shape = RoundedCornerShape(12.dp)
        Column(Modifier.fillMaxWidth().clip(shape).border(1.dp, p.hairline, shape)) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val minimum = if (rental) RENTAL_MIN else ALLOWANCE_MIN
                val scrolls = maxWidth < minimum
                val scroll = rememberScrollState()
                Column(
                    modifier = if (scrolls) {
                        Modifier.horizontalScroll(scroll).width(minimum)
                    } else {
                        Modifier.fillMaxWidth()
                    },
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(p.infoBox)
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(GAP),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        HeadText(
                            if (rental) str(S.desktop_dm_item) else str(S.desktop_dm_allowance),
                            Modifier.weight(1f),
                        )
                        HeadText(str(S.dm_allow_rate), Modifier.width(if (rental) 80.dp else 90.dp))
                        HeadText(str(S.dm_allow_basis), Modifier.width(130.dp))
                        HeadText(str(S.dm_allow_applies_to), Modifier.width(150.dp))
                        if (rental) HeadText(str(S.dm_allow_cap_type), Modifier.width(210.dp))
                        if (nominal) HeadText(str(S.dm_rule_nominal), Modifier.width(110.dp))
                        Box(Modifier.width(32.dp))
                    }
                    rows.forEach { row ->
                        Rule(p.hairline)
                        EntitlementRow(
                            row = row,
                            kind = kind,
                            rental = rental,
                            symbol = symbol,
                            nominal = nominal,
                            showErrors = builder.showEntitlementErrors,
                            state = state,
                            onChange = { field, value -> ops.edit { it.withRow(key, text(row["id"]), field, value) } },
                            onDelete = {
                                val id = text(row["id"])
                                ops.edit { form ->
                                    form.with(
                                        key,
                                        JsonArray(
                                            form.list(key).filterNot {
                                                (it as? JsonObject)?.get("id")?.let(::text) == id
                                            },
                                        ),
                                    )
                                }
                            },
                        )
                    }
                }
            }
            Rule(p.hairline)
            Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp)) {
                AddRowButton(
                    if (rental) str(S.dm_allow_add_dialog_title_rental) else str(S.dm_allow_add_dialog_title_allowance),
                ) {
                    ops.edit { form -> form.with(key, JsonArray(form.list(key) + newRow(rental))) }
                }
            }
        }
    }
}

@Suppress("LongMethod")
@Composable
private fun EntitlementRow(
    row: JsonObject,
    kind: String,
    rental: Boolean,
    symbol: String,
    nominal: Boolean,
    showErrors: Boolean,
    state: DealMemoUiState,
    onChange: (String, JsonElement) -> Unit,
    onDelete: () -> Unit,
) {
    val p = bp
    val on = Js.truthy(row["on"])
    val missing = if (showErrors && on) DealValidators.entitlementMissing(row, kind).toSet() else emptySet()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (on) 1f else DISABLED_ROW)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(GAP),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            BuilderSwitch(on, onChange = { onChange("on", JsonPrimitive(it)) })
            if (DealValidators.isCustomRow(row)) {
                BuilderInput(
                    value = text(row["name"]),
                    onValueChange = { onChange("name", JsonPrimitive(it)) },
                    placeholder = if (rental) str(S.desktop_rental_name) else str(S.desktop_allowance_name),
                    error = "name" in missing,
                    height = ROW_CONTROL,
                    textSize = 13f,
                    modifier = Modifier.weight(1f),
                )
            } else {
                ZillitText(
                    text = text(row["name"]),
                    style = DmType.sans(13.sp, FontWeight.SemiBold),
                    color = p.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        BuilderInput(
            value = text(row["rate"]),
            onValueChange = { onChange("rate", JsonPrimitive(it)) },
            placeholder = "0",
            mono = true,
            prefix = symbol,
            error = "rate" in missing,
            height = ROW_CONTROL,
            textSize = 13f,
            modifier = Modifier.width(if (rental) 80.dp else 90.dp),
        )
        NativeSelect(
            value = text(row["basis"]),
            options = basisOptions(text(row["basis"])),
            onPick = { onChange("basis", JsonPrimitive(it)) },
            placeholder = str(S.desktop_dm_pay_frequency_placeholder),
            error = "basis" in missing,
            height = ROW_CONTROL,
            textSize = 13f,
            modifier = Modifier.width(130.dp),
            menuWidth = 240.dp,
        )
        NativeSelect(
            value = text(row["applies_to"]),
            options = if (rental) RENTAL_APPLIES else ALLOWANCE_APPLIES,
            onPick = { onChange("applies_to", JsonPrimitive(it)) },
            placeholder = str(S.desktop_dm_applies_to_placeholder),
            error = "applies_to" in missing,
            height = ROW_CONTROL,
            textSize = 13f,
            modifier = Modifier.width(150.dp),
            menuWidth = 240.dp,
        )
        if (rental) {
            val capped = text(row["cap_type"]) == "capped"
            Row(
                Modifier.width(210.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                NativeSelect(
                    value = text(row["cap_type"]).ifEmpty { "uncapped" },
                    options = listOf(
                        PickOption("uncapped", str(S.desktop_uncapped)),
                        PickOption("capped", str(S.desktop_capped)),
                    ),
                    onPick = { onChange("cap_type", JsonPrimitive(it)) },
                    height = ROW_CONTROL,
                    textSize = 13f,
                    modifier = if (capped) Modifier.width(100.dp) else Modifier.weight(1f),
                    menuWidth = 160.dp,
                )
                if (capped) {
                    Box(
                        Modifier
                            .weight(1f)
                            .then(
                                if ("cap_amount" in missing) {
                                    Modifier.border(1.dp, p.red, RoundedCornerShape(RADIUS))
                                } else {
                                    Modifier
                                },
                            ),
                    ) {
                        MoneyInput(
                            value = Js.parseFloat(row["cap_amount"]),
                            onCommit = { value ->
                                onChange("cap_amount", value?.let(CrewFormValues::amount) ?: JsonPrimitive(""))
                            },
                        )
                    }
                }
            }
        }
        if (nominal) {
            CoaCodeField(
                value = text(row["nominal"]),
                onValueChange = { onChange("nominal", JsonPrimitive(it)) },
                coa = state.coa,
                placeholder = "—",
                modifier = Modifier.width(110.dp),
            )
        }
        DeleteIcon(onDelete)
    }
}

@Composable
private fun DeleteIcon(onClick: () -> Unit) {
    val p = bp
    val (source, hovered) = rememberHover()
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (hovered) p.redSoft else Color.Transparent)
            .hoverable(source)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand),
        contentAlignment = Alignment.Center,
    ) { ZillitIcon(ZillitIcons.Close, size = 11.dp, tint = if (hovered) p.red else p.placeholder) }
}

@Composable
private fun HeadText(text: String, modifier: Modifier) {
    ZillitText(
        text = text.uppercase(),
        style = DmType.mono(10.sp, FontWeight.Bold, 0.1.em),
        color = bp.muted,
        maxLines = 1,
        modifier = modifier,
    )
}

/** A custom row: `custom-rental-{time}` or `custom-{time}`, on, with the wizard's default basis. */
private fun newRow(rental: Boolean): JsonObject {
    val now = Clock.System.now().toEpochMilliseconds()
    return buildJsonObject {
        put("id", if (rental) "custom-rental-$now" else "custom-$now")
        put("name", "")
        put("on", true)
        put("rate", "")
        put("basis", if (rental) "week" else "day")
        put("applies_to", "")
        if (rental) {
            put("cap_type", "uncapped")
            put("cap_amount", "")
        }
        put("nominal", "")
    }
}

private fun DealForm.withRow(key: String, id: String, field: String, value: JsonElement): DealForm = with(
    key,
    JsonArray(
        list(key).map { element ->
            val row = element as? JsonObject
            if (row != null && text(row["id"]) == id) JsonObject(row + (field to value)) else element
        },
    ),
)

private fun text(value: JsonElement?): String = when (value) {
    null, JsonNull -> ""
    else -> Js.text(value)
}

private val GAP = 10.dp
private val ROW_CONTROL = 34.dp
private val RENTAL_MIN = 1000.dp
private val ALLOWANCE_MIN = 800.dp
private const val DISABLED_ROW = 0.6f
