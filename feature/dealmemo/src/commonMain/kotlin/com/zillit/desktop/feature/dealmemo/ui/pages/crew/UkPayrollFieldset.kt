package com.zillit.desktop.feature.dealmemo.ui.pages.crew

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.dealmemo.domain.DocRead
import com.zillit.desktop.feature.dealmemo.domain.preview.CrewFormRules
import com.zillit.desktop.feature.dealmemo.domain.preview.CrewFormValues
import com.zillit.desktop.feature.dealmemo.domain.preview.CrewMissing
import com.zillit.desktop.feature.dealmemo.domain.preview.UkOption
import com.zillit.desktop.feature.dealmemo.domain.preview.UkPayroll
import com.zillit.desktop.feature.dealmemo.domain.preview.isValueBlank
import com.zillit.desktop.feature.dealmemo.ui.components.DmType
import com.zillit.desktop.feature.dealmemo.ui.components.rememberHover
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The HMRC new-starter block (`UKPayrollFieldset.jsx`): the P45-or-statement
 * fork, the two loans, the NI category and the pension wish. HMRC's own copy,
 * unparaphrased. Every change is one patch, so a route switch clears the
 * other side in the same write.
 *
 * [missing] is the crew form's red "still to fill" state — true exactly when
 * the Send for Approval / Sign pop-up lists this block. It is keyed on the
 * route on screen, not the stored one: "No P45" still derives to no route until
 * a statement is ticked, but the statement options are what the crew member
 * is looking at, so they are what goes red.
 */
@Suppress("CyclomaticComplexMethod", "LongMethod")
@Composable
internal fun UkPayrollFieldset(
    block: JsonObject,
    onPatch: (Map<String, JsonElement>) -> Unit,
    missing: Boolean = false,
) {
    // The route is local, seeded once from what is filled; the stash lets a
    // mis-click on the toggle be undone until the step is left.
    var route by remember { mutableStateOf(UkPayroll.route(block)) }
    val stash = remember { RouteStash() }

    fun pickRoute(next: String) {
        if (next == route) return
        if (next == UkPayroll.ROUTE_P45) {
            stash.statement = block["starter_statement"]?.takeUnless { it is JsonNull }
            onPatch(
                mapOf<String, JsonElement>("starter_statement" to JsonNull) +
                    UkPayroll.P45_KEYS.associateWith { stash.p45[it] ?: JsonNull },
            )
        } else {
            stash.p45 = UkPayroll.P45_KEYS.associateWith { block[it] ?: JsonNull }
            onPatch(
                mapOf("starter_statement" to (stash.statement ?: JsonNull)) +
                    UkPayroll.P45_KEYS.associateWith { JsonNull },
            )
        }
        route = next
    }

    val routeMissing = missing && route.isEmpty()

    Column {
        Question(
            title = str(S.desktop_dm_tax_code_information),
            sub = str(S.desktop_dm_a_crew_member_arrives_with_a_p45),
            lead = true,
            first = true,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = if (routeMissing) 0.dp else 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                RouteButton(
                    str(S.desktop_dm_no_p45_starter_statement),
                    route == UkPayroll.ROUTE_STATEMENT,
                    Modifier.weight(1f),
                    routeMissing,
                ) {
                    pickRoute(UkPayroll.ROUTE_STATEMENT)
                }
                RouteButton(
                    str(S.desktop_dm_i_have_a_p45),
                    route == UkPayroll.ROUTE_P45,
                    Modifier.weight(1f),
                    routeMissing,
                ) {
                    pickRoute(UkPayroll.ROUTE_P45)
                }
            }
            if (routeMissing) {
                Column(Modifier.padding(bottom = 12.dp)) { InlineError(str(S.desktop_dm_required_pick_one)) }
            }
            when (route) {
                // On this route `missing` can only mean no statement ticked: picking it clears the P45 keys.
                UkPayroll.ROUTE_STATEMENT -> {
                    Box(if (missing) Modifier.missingFrame() else Modifier) {
                        DeclarationRadio(
                            options = UkPayroll.STARTER_STATEMENTS,
                            value = DocRead.text(block, "starter_statement"),
                            onPick = { onPatch(mapOf("starter_statement" to JsonPrimitive(it))) },
                        )
                    }
                    if (missing) InlineError(CrewMissing.REQUIRED)
                }
                UkPayroll.ROUTE_P45 -> P45Grid(block, onPatch, missing)
            }
        }
        Question(
            title = str(S.dm_uk_sl_title),
            sub = str(S.desktop_dm_undergraduate_plans_only_a_postgraduate_loan_is),
        ) {
            DeclarationRadio(UkPayroll.STUDENT_LOAN_PLANS, DocRead.text(block, "student_loan_plan")) {
                onPatch(mapOf("student_loan_plan" to JsonPrimitive(it)))
            }
        }
        Question(
            title = str(S.dm_uk_pg_title),
            sub = str(S.desktop_dm_a_postgraduate_loan_repays_alongside_an_undergraduate),
        ) {
            DeclarationRadio(UkPayroll.PG_LOAN_PLANS, DocRead.text(block, "pg_loan")) {
                onPatch(mapOf("pg_loan" to JsonPrimitive(it)))
            }
        }
        FormGrid(Modifier.padding(top = 22.dp)) {
            half {
                val ni = DocRead.text(block, "ni_category").orEmpty()
                Column {
                    WizLabel(
                        str(S.desktop_dm_ni_category),
                        hint = str(S.desktop_dm_optional_the_payroll_bureau_derives_this_fill),
                    )
                    FormInput(
                        value = ni,
                        onValueChange = {
                            onPatch(mapOf("ni_category" to JsonPrimitive(CrewFormValues.niCategory(it))))
                        },
                        placeholder = "A",
                        mono = true,
                    )
                    if (CrewFormValues.isNiCategoryUnknown(ni)) {
                        ZillitText(
                            text = CrewFormValues.NI_CATEGORY_HINT,
                            style = DmType.sans(11.5.sp),
                            color = cp.amber,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }
        }
        Question(title = str(S.desktop_dm_pension_auto_enrolment), sub = CrewFormValues.PENSION_NOTE) {
            val pension = DocRead.text(block, "pension_status") ?: UkPayroll.PENSION_DEFAULT
            DeclarationRadio(UkPayroll.PENSION_STATUSES, pension) {
                onPatch(mapOf("pension_status" to JsonPrimitive(it)))
            }
        }
    }
}

/** What a route switch cleared, kept for a switch back. */
private class RouteStash {
    var statement: JsonElement? = null
    var p45: Map<String, JsonElement> = emptyMap()
}

@Composable
private fun P45Grid(block: JsonObject, onPatch: (Map<String, JsonElement>) -> Unit, missing: Boolean) {
    val paye = DocRead.text(block, "p45_previous_paye_ref").orEmpty()
    // A figure is empty on null or blank, never on falsy: 0 is a real one. The same test as `missingLabels`.
    fun empty(key: String) = missing && isValueBlank(block[key])
    FormGrid {
        listOf(
            "p45_previous_pay" to str(S.desktop_dm_previous_pay),
            "p45_previous_tax" to str(S.desktop_dm_previous_tax),
        ).forEach { (key, label) ->
            half {
                Column {
                    WizLabel(label)
                    MoneyInput(
                        value = DocRead.number(block, key),
                        onCommit = { onPatch(mapOf(key to CrewFormValues.amount(it))) },
                        missing = empty(key),
                    )
                    if (empty(key)) InlineError(CrewMissing.REQUIRED)
                }
            }
        }
        half {
            Column {
                WizLabel(str(S.desktop_dm_leaving_date))
                // UTC both ways — a local round-trip hands anyone west of Greenwich the day before.
                DateInput(
                    millis = DocRead.epoch(block, "p45_leaving_date"),
                    onChange = { onPatch(mapOf("p45_leaving_date" to JsonPrimitive(it))) },
                    zone = TimeZone.UTC,
                    missing = empty("p45_leaving_date"),
                )
                if (empty("p45_leaving_date")) InlineError(CrewMissing.REQUIRED)
            }
        }
        half {
            Column {
                WizLabel(str(S.desktop_dm_previous_employer_paye_ref))
                val invalid = !CrewFormRules.validPayeRef(paye)
                FormInput(
                    value = paye,
                    onValueChange = { onPatch(mapOf("p45_previous_paye_ref" to JsonPrimitive(it))) },
                    placeholder = "123/AB456",
                    error = invalid,
                    missing = empty("p45_previous_paye_ref"),
                )
                // A format error outranks "Required"; the two cannot coincide, as formats only fire on a value.
                if (invalid) InlineError(CrewFormRules.PAYE_ERROR)
                if (empty("p45_previous_paye_ref")) InlineError(CrewMissing.REQUIRED)
            }
        }
    }
}

/** `Q`: a question's heading — 15 for the one that opens the block — and its note. */
@Composable
private fun Question(
    title: String,
    sub: String?,
    lead: Boolean = false,
    first: Boolean = false,
    content: @Composable () -> Unit,
) {
    val p = cp
    Column(Modifier.fillMaxWidth().padding(top = if (first) 0.dp else 22.dp)) {
        ZillitText(
            text = title,
            style = DmType.sans(if (lead) 15.sp else 13.sp, FontWeight.Bold),
            color = p.ink,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        if (sub != null) {
            ZillitText(
                text = sub,
                style = DmType.sans(12.sp).copy(lineHeight = 18.sp),
                color = p.muted,
                modifier = Modifier.padding(bottom = 10.dp),
            )
        } else {
            Spacer(Modifier.height(10.dp))
        }
        content()
    }
}

@Composable
private fun RouteButton(label: String, active: Boolean, modifier: Modifier, missing: Boolean, onClick: () -> Unit) {
    val p = cp
    val (source, hovered) = rememberHover()
    val shape = RoundedCornerShape(11.dp)
    Box(
        modifier = modifier
            .then(if (active) Modifier.shadow(6.dp, shape, ambientColor = GLOW, spotColor = GLOW) else Modifier)
            .clip(shape)
            .then(
                if (active) {
                    Modifier.background(Brush.verticalGradient(listOf(p.routeActiveTop, p.card)))
                } else {
                    Modifier.background(p.inputBg)
                },
            )
            .border(
                if (active) 1.5.dp else 1.dp,
                when {
                    active -> Color(0xFFE8861A)
                    missing -> p.missing
                    hovered -> p.pickerBorder
                    else -> p.inputBorder
                },
                shape,
            )
            .hoverable(source)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        ZillitText(text = label, style = DmType.sans(13.sp, FontWeight.Bold), color = p.ink)
    }
}

/**
 * `DeclarationRadio`: each answer as a card with its conditions under it — the
 * conditions are the question. "None of these" carries a null value, so it
 * reads as chosen while nothing is.
 */
@Composable
private fun DeclarationRadio(options: List<UkOption>, value: String?, onPick: (String?) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { option ->
            RadioCard(option, selected = option.value == value) { onPick(option.value) }
        }
    }
}

@Composable
private fun RadioCard(option: UkOption, selected: Boolean, onClick: () -> Unit) {
    val p = cp
    val (source, hovered) = rememberHover()
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) p.cardSelected else p.inputBg)
            .border(
                1.dp,
                when {
                    selected -> Color(0xFFE8861A)
                    hovered -> p.pickerBorder
                    else -> p.inputBorder
                },
                shape,
            )
            .hoverable(source)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RadioDot(selected, Modifier.padding(top = 3.dp))
        Column(Modifier.weight(1f)) {
            ZillitText(
                text = option.marker ?: option.short,
                style = DmType.sans(13.5.sp, FontWeight.Bold),
                color = p.ink,
            )
            option.warn?.let {
                ZillitText(
                    text = it,
                    style = DmType.sans(12.sp, FontWeight.Bold),
                    color = Color(0xFFE23B3B),
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            val body = DmType.sans(12.5.sp).copy(lineHeight = 19.sp)
            option.lead?.let {
                ZillitText(text = it, style = body, color = p.label, modifier = Modifier.padding(top = 4.dp))
            }
            if (option.bullets.isNotEmpty()) {
                Column(Modifier.padding(top = 4.dp)) {
                    option.bullets.forEach { bullet ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            ZillitText(text = "•", style = body, color = p.label)
                            ZillitText(text = bullet, style = body, color = p.label, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RadioDot(selected: Boolean, modifier: Modifier = Modifier) {
    val p = cp
    Box(
        modifier = modifier
            .size(15.dp)
            .clip(CircleShape)
            .background(p.inputBg)
            .border(if (selected) 4.5.dp else 1.5.dp, if (selected) Color(0xFFE8861A) else p.pickerBorder, CircleShape),
    )
}

/**
 * `-mx-[7px] -mt-[7px] rounded-[12px] border p-1.5 ah-missing`: a red frame drawn 7 outside the box (its own 1 and the
 * web's 6 of padding), so the options sit where they do unframed and ticking one clears the frame without a reflow.
 */
@Composable
private fun Modifier.missingFrame(): Modifier {
    val red = cp.missing
    return drawBehind {
        val outset = FRAME_OUTSET.toPx()
        drawRoundRect(
            color = red,
            topLeft = Offset(-outset, -outset),
            size = Size(size.width + 2 * outset, size.height + 2 * outset),
            cornerRadius = CornerRadius(FRAME_RADIUS.toPx()),
            style = Stroke(1.dp.toPx()),
        )
    }
}

private val FRAME_OUTSET = 7.dp
private val FRAME_RADIUS = 12.dp
private val GLOW = Color(0x24E8861A)
