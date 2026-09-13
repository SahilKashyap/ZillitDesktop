@file:Suppress("MatchingDeclarationName") // The section editor switch; FormOps is only what it hands each editor.

package com.zillit.desktop.feature.dealmemo.ui.pages.builder

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.zillit.desktop.feature.dealmemo.domain.authoring.DealForm
import com.zillit.desktop.feature.dealmemo.domain.authoring.DealValidators
import com.zillit.desktop.feature.dealmemo.ui.BuilderEvent
import com.zillit.desktop.feature.dealmemo.ui.DealMemoEvent
import com.zillit.desktop.feature.dealmemo.ui.DealMemoUiState
import com.zillit.desktop.feature.dealmemo.ui.builder.BuilderSection
import com.zillit.desktop.feature.dealmemo.ui.builder.BuilderState
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/** The form writes an editor makes — every one a user edit, except [auto]. */
internal class FormOps(private val onEvent: (DealMemoEvent) -> Unit) {

    fun set(key: String, value: String) = onEvent(BuilderEvent.SetField(key, JsonPrimitive(value)))

    fun set(key: String, value: Boolean) = onEvent(BuilderEvent.SetField(key, JsonPrimitive(value)))

    fun set(key: String, value: JsonElement) = onEvent(BuilderEvent.SetField(key, value))

    fun patch(vararg values: Pair<String, JsonElement>) = onEvent(BuilderEvent.Patch(mapOf(*values)))

    fun edit(transform: (DealForm) -> DealForm) = onEvent(BuilderEvent.Edit(transform))

    /** A default the page fills in itself — never dirty. */
    fun auto(key: String, value: JsonElement) = onEvent(BuilderEvent.Auto(mapOf(key to value)))
}

@Composable
internal fun rememberFormOps(onEvent: (DealMemoEvent) -> Unit): FormOps = remember(onEvent) { FormOps(onEvent) }

/** The editor a section mounts — the wizard step on a deal, the setup page's own body on a setup. */
@Suppress("CyclomaticComplexMethod")
@Composable
internal fun BuilderSectionEditor(
    state: DealMemoUiState,
    builder: BuilderState,
    sectionId: Int,
    onEvent: (DealMemoEvent) -> Unit,
) {
    val ops = rememberFormOps(onEvent)
    if (builder.mode.setup) {
        when (sectionId) {
            DealValidators.TERRITORY -> TerritoryEditor(state, builder, ops, onEvent)
            BuilderSection.NON_UNION_RULES -> NonUnionRulesSection(state, builder, onEvent)
            BuilderSection.SCHEDULE -> SetupScheduleSection(builder, ops)
            BuilderSection.ALLOWANCES -> SetupEntitlementsSection(state, builder, ops)
            BuilderSection.CONDITIONS -> SetupConditionsSection(state, builder, ops, onEvent)
            BuilderSection.PAYROLL -> SetupBureauSection(builder, onEvent)
        }
        return
    }
    when (sectionId) {
        DealValidators.CREW -> CrewEditor(state, builder, ops)
        DealValidators.PERSONAL -> PersonalEditor(state, builder, ops, onEvent)
        DealValidators.EMPLOYMENT -> EmploymentEditor(state, builder, ops)
        DealValidators.DEAL -> DealStructureEditor(state, builder, ops, onEvent)
        DealValidators.RATES -> RatesEditor(state, builder, ops, onEvent)
        DealValidators.ALLOWANCES -> AllowancesEditor(state, builder, ops)
        BuilderSection.CONDITIONS -> ConditionsDocumentsEditor(state, builder, ops, onEvent)
        DealValidators.NOMINAL -> NominalEditor(state, builder, ops)
        BuilderSection.PAYROLL -> PayrollEditor(state, builder, ops)
    }
}
