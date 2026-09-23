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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import com.zillit.desktop.feature.dealmemo.domain.preview.UkOption
import com.zillit.desktop.feature.dealmemo.domain.preview.UkPayroll
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
 */
@Suppress("LongMethod")
@Composable
internal fun UkPayrollFieldset(block: JsonObject, onPatch: (Map<String, JsonElement>) -> Unit) {
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

    Column {
        Question(
            title = str(S.desktop_dm_tax_code_information),
            sub = str(S.desktop_dm_a_crew_member_arrives_with_a_p45),
            lead = true,
            first = true,
        ) {
            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                RouteButton(
                    str(S.desktop_dm_no_p45_starter_statement),
                    route == UkPayroll.ROUTE_STATEMENT,
                    Modifier.weight(1f),
                ) {
                    pickRoute(UkPayroll.ROUTE_STATEMENT)
                }
                RouteButton(str(S.desktop_dm_i_have_a_p45), route == UkPayroll.ROUTE_P45, Modifier.weight(1f)) {
                    pickRoute(UkPayroll.ROUTE_P45)
                }
            }
            when (route) {
                UkPayroll.ROUTE_STATEMENT -> DeclarationRadio(
                    options = UkPayroll.STARTER_STATEMENTS,
                    value = DocRead.text(block, "starter_statement"),
                    onPick = { onPatch(mapOf("starter_statement" to JsonPrimitive(it))) },
                )
                UkPayroll.ROUTE_P45 -> P45Grid(block, onPatch)
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
private fun P45Grid(block: JsonObject, onPatch: (Map<String, JsonElement>) -> Unit) {
    val paye = DocRead.text(block, "p45_previous_paye_ref").orEmpty()
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
                    )
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
                )
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
                )
                if (invalid) InlineError(CrewFormRules.PAYE_ERROR)
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
private fun RouteButton(label: String, active: Boolean, modifier: Modifier, onClick: () -> Unit) {
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

private val GLOW = Color(0x24E8861A)
