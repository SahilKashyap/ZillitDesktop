package com.zillit.desktop.feature.dealmemo.ui.pages.builder

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.dealmemo.domain.DocRead
import com.zillit.desktop.feature.dealmemo.domain.authoring.DealForm
import com.zillit.desktop.feature.dealmemo.domain.authoring.DealValidators
import com.zillit.desktop.feature.dealmemo.domain.authoring.NominalLine
import com.zillit.desktop.feature.dealmemo.domain.rates.Js
import com.zillit.desktop.feature.dealmemo.ui.DealMemoUiState
import com.zillit.desktop.feature.dealmemo.ui.builder.BuilderState
import com.zillit.desktop.feature.dealmemo.ui.components.DmType
import com.zillit.desktop.feature.dealmemo.ui.pages.preview.CoaCodeField
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Nominal Coding (`Step7Nominal.jsx`): a code for every labour line the deal
 * will carry, and the tax-credit regime it is tagged with.
 */
@Composable
internal fun NominalEditor(state: DealMemoUiState, builder: BuilderState, ops: FormOps) {
    val form = builder.form
    val union = builder.reference.selectedUnion
    val breakdown = state.projectSettings.view.nonUnionPaybreakdown
    val lines = remember(form, union, breakdown) { DealValidators.nominalLines(form, union, breakdown) }
    val department = form.text("department")
    BuilderAlert(
        if (department.isNotEmpty()) {
            str(S.dm_nom_info_note, state.labels.departmentLabel(department))
        } else {
            str(S.desktop_dm_select_a_department_in_step_2_to)
        },
        icon = ZillitIcons.Check,
        modifier = Modifier.padding(bottom = 14.dp),
    )
    CardBlock(title = str(S.dm_nom_card_codes), tone = BuilderTone.Gold) {
        val p = bp
        val shape = RoundedCornerShape(10.dp)
        Column(Modifier.fillMaxWidth().clip(shape).border(1.dp, p.hairline, shape)) {
            Row(Modifier.fillMaxWidth().background(p.infoBox).padding(horizontal = 16.dp, vertical = 10.dp)) {
                HeadCell(str(S.dm_nom_table_element), Modifier.weight(1f))
                HeadCell(str(S.dm_nom_table_override), Modifier.width(OVERRIDE_WIDTH.dp))
            }
            if (lines.isEmpty()) {
                EmptyNote(
                    str(S.desktop_dm_pick_an_agreement_in_step_1_the),
                    Modifier.padding(16.dp),
                )
            }
            lines.forEach { line ->
                Rule(p.hairline)
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ZillitText(
                        text = line.element,
                        style = DmType.sans(13.sp, FontWeight.Medium),
                        color = p.ink,
                        modifier = Modifier.weight(1f),
                    )
                    CoaCodeField(
                        value = DealValidators.nominalValue(form, line),
                        onValueChange = { code -> ops.edit { writeNominal(it, line, code) } },
                        coa = state.coa,
                        modifier = Modifier.width(OVERRIDE_WIDTH.dp),
                    )
                }
            }
        }
    }
    TaxCreditCard(state, form, ops)
}

@Composable
private fun HeadCell(text: String, modifier: Modifier) {
    ZillitText(
        text = text.uppercase(),
        style = DmType.mono(10.sp, FontWeight.Bold, 0.1.em),
        color = bp.muted,
        modifier = modifier,
    )
}

/** Writes a code where the line keeps it: on its allowance or rental row, else in the overrides. */
private fun writeNominal(form: DealForm, line: NominalLine, code: String): DealForm = when (line.kind) {
    "allowance", "rental" -> {
        val key = if (line.kind == "allowance") "allowances" else "rentals"
        form.with(
            key,
            JsonArray(
                form.list(key).map { element ->
                    val row = element as? JsonObject
                    if (row != null && row["id"]?.let(Js::text) == line.refId) {
                        JsonObject(row + ("nominal" to JsonPrimitive(code)))
                    } else {
                        element
                    }
                },
            ),
        )
    }
    else -> form.with(
        "nominalOverrides",
        JsonObject(form.obj("nominalOverrides").orEmpty() + (line.key to JsonPrimitive(code))),
    )
}

@Composable
private fun TaxCreditCard(state: DealMemoUiState, form: DealForm, ops: FormOps) {
    val entity = form.text("productionEntity")
    val company = state.projectSettings.view.companies.firstOrNull { DocRead.text(it, "id") == entity }
    val regimes = (company?.get("tax_credits") as? JsonArray).orEmpty().mapNotNull {
        (it as? JsonPrimitive)?.content?.takeIf(String::isNotEmpty)
    }
    val tagged = (form["taxCredits"] as? JsonArray)?.firstOrNull()?.let { (it as? JsonPrimitive)?.content }.orEmpty()
    CardBlock(
        title = str(S.dm_nom_card_tax_credit),
        tag = str(S.dm_nom_tax_credit_subtitle),
        tone = BuilderTone.Purple,
    ) {
        BuilderAlert(
            str(S.desktop_dm_tag_this_deal_with_the_tax_credit),
            modifier = Modifier.padding(bottom = 12.dp),
        )
        when {
            entity.isEmpty() -> InfoBox {
                InfoLine(str(S.desktop_dm_select_a_production_entity_in_step_1))
            }
            regimes.isEmpty() -> InfoBox {
                InfoLine(
                    str(S.desktop_dm_there_is_no_tax_credit_regimes_configured),
                )
            }
            else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ZillitText(
                    text = "${DocRead.text(company, "name").orEmpty()} — applicable regime".uppercase(),
                    style = DmType.sans(9.sp, FontWeight.SemiBold, 0.04.em),
                    color = bp.muted,
                )
                NativeSelect(
                    value = tagged,
                    options = regimes.map { PickOption(it, it) },
                    onPick = { tag ->
                        ops.set("taxCredits", JsonArray(if (tag.isEmpty()) emptyList() else listOf(JsonPrimitive(tag))))
                    },
                    placeholder = str(S.desktop_dm_none_placeholder),
                )
                if (tagged.isNotEmpty()) {
                    InfoBox {
                        InfoLine(str(S.desktop_dm_tagged_for_this_deal))
                        ZillitText(text = tagged, style = DmType.sans(11.sp, FontWeight.SemiBold), color = bp.ink)
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoLine(text: String) {
    ZillitText(text = text, style = DmType.sans(11.sp), color = bp.ink2)
}

private const val OVERRIDE_WIDTH = 160
