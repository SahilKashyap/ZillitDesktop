package com.zillit.desktop.feature.dealmemo.ui.pages.builder

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.dealmemo.domain.authoring.CompanyDraft
import com.zillit.desktop.feature.dealmemo.domain.authoring.DealForm
import com.zillit.desktop.feature.dealmemo.domain.isNonUnionId
import com.zillit.desktop.feature.dealmemo.domain.preview.CrewFormRules
import com.zillit.desktop.feature.dealmemo.domain.rates.Js
import com.zillit.desktop.feature.dealmemo.domain.rates.TerritoryCatalogue
import com.zillit.desktop.feature.dealmemo.ui.BuilderEvent
import com.zillit.desktop.feature.dealmemo.ui.DealMemoEvent
import com.zillit.desktop.feature.dealmemo.ui.DealMemoUiState
import com.zillit.desktop.feature.dealmemo.ui.SetupGroup
import com.zillit.desktop.feature.dealmemo.ui.builder.BuilderState
import com.zillit.desktop.feature.dealmemo.ui.components.DmButton
import com.zillit.desktop.feature.dealmemo.ui.components.DmButtonStyle
import com.zillit.desktop.feature.dealmemo.ui.components.DmModal
import com.zillit.desktop.feature.dealmemo.ui.components.DmType
import com.zillit.desktop.feature.dealmemo.ui.components.TerritoryFlag
import com.zillit.desktop.feature.dealmemo.ui.components.rememberHover
import com.zillit.desktop.feature.dealmemo.ui.pages.crew.CountryPicker
import com.zillit.desktop.feature.dealmemo.ui.pages.crew.CountryRowStyle
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Territory & Union on a setup page (`Step1Territory.jsx`): the contracting
 * entity, union or non-union, the territory and its agreement, and the
 * agreement's budget band.
 */
@Composable
internal fun TerritoryEditor(
    state: DealMemoUiState,
    builder: BuilderState,
    ops: FormOps,
    onEvent: (DealMemoEvent) -> Unit,
) {
    val form = builder.form
    ContractingEntity(state, form, ops, onEvent)
    UnionStatus(form, locked = builder.mode.unionLocked(builder.editLoading), ops)
    if (!isNonUnionId(form.text("union"))) TerritoryAndAgreement(builder, ops)
    BudgetBand(builder, ops)
}

@Composable
private fun ContractingEntity(state: DealMemoUiState, form: DealForm, ops: FormOps, onEvent: (DealMemoEvent) -> Unit) {
    val settings = state.projectSettings
    val companies = settings.view.companies
    val productionType = state.production.project.productionType
    CardBlock(title = str(S.dm_step1_card_entity)) {
        BuilderGrid(columns = 2) {
            cell {
                Field(
                    label = str(S.desktop_dm_production_entity),
                    required = true,
                    trailing = { LabelAction(str(S.desktop_add_company)) { onEvent(BuilderEvent.AddCompany) } },
                ) {
                    RichSelect(
                        options = companies.map { company ->
                            val name = text(company, "name")
                            val country = text(company, "country")
                            PickOption(
                                key = text(company, "id"),
                                label = CompanyDraft.label(name, country),
                                search = "$name $country",
                            )
                        },
                        selectedKey = form.text("productionEntity").ifEmpty { null },
                        onPick = { ops.set("productionEntity", it.orEmpty()) },
                        placeholder = when {
                            settings.loading -> str(S.desktop_dm_loading_entities)
                            companies.isEmpty() -> str(S.desktop_dm_no_companies_in_production_setup)
                            else -> str(S.desktop_dm_select_entity)
                        },
                        enabled = !settings.loading,
                        rowLeading = { option -> Monogram(option.label) },
                    )
                }
            }
            cell {
                Field(str(S.dm_step1_production_type)) {
                    ReadOnlyBox(
                        text = productionType.takeIf { it.isNotEmpty() }?.let(::localised),
                        emptyText = str(S.desktop_dm_no_project_type_on_this_project),
                        tooltip = str(S.desktop_dm_set_on_the_current_project_cant_be),
                    )
                }
            }
        }
    }
}

/** Union or Non-Union; once locked only the chosen one shows. A switch clears what hangs on the agreement. */
@Composable
private fun UnionStatus(form: DealForm, locked: Boolean, ops: FormOps) {
    val nonUnion = isNonUnionId(form.text("union"))
    CardBlock(title = str(S.desktop_dm_union_status)) {
        val options = listOf(
            false to str(S.dm_label_union),
            true to str(S.dm_create_non_union),
        ).filter { (isNonUnion, _) ->
            !locked || isNonUnion == nonUnion
        }
        BuilderGrid(columns = if (locked) 1 else 2, gap = 10.dp) {
            options.forEach { (isNonUnion, label) ->
                cell {
                    StatusOption(label = label, active = isNonUnion == nonUnion, locked = locked) {
                        ops.patch(
                            "union" to JsonPrimitive(if (isNonUnion) SetupGroup.NonUnion.wire else ""),
                            "pactBand" to JsonPrimitive(""),
                            "designation" to JsonPrimitive(""),
                            "jobTitle" to JsonPrimitive(""),
                            "customJobTitle" to JsonPrimitive(""),
                        )
                    }
                }
            }
        }
    }
}

@Suppress("CyclomaticComplexMethod")
@Composable
private fun StatusOption(label: String, active: Boolean, locked: Boolean, onClick: () -> Unit) {
    val p = bp
    val dark = ZillitTheme.colors.isDark
    val (source, hovered) = rememberHover()
    val shape = RoundedCornerShape(11.dp)
    val fill = when {
        active && dark -> Brush.verticalGradient(listOf(Color(0xFF1F1A13), Color(0xFF141720)))
        active -> Brush.verticalGradient(listOf(Color(0xFFFFFAF1), Color.White))
        else -> SolidColor(if (dark) Color(0xFF1E2535) else Color.White)
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (active) Modifier.shadow(6.dp, shape, ambientColor = p.cta, spotColor = p.cta) else Modifier)
            .clip(shape)
            .background(fill)
            .border(
                if (active) 1.5.dp else 1.dp,
                if (active) p.cta else if (hovered && !locked) p.menuBorder else p.hairline,
                shape,
            )
            .hoverable(source)
            .then(
                if (!locked && !active) {
                    Modifier.clickable(interactionSource = source, indication = null, onClick = onClick)
                        .pointerHoverIcon(PointerIcon.Hand)
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        ZillitText(
            text = label,
            style = DmType.sans(13.5.sp, FontWeight.Bold),
            color = p.ink,
            modifier = Modifier.padding(end = if (active) 24.dp else 0.dp),
        )
        if (active) {
            Box(
                modifier = Modifier.align(Alignment.CenterEnd).size(18.dp).clip(CircleShape).background(p.cta),
                contentAlignment = Alignment.Center,
            ) { ZillitIcon(ZillitIcons.Check, size = 10.dp, tint = Color.White) }
        }
    }
}

/** The territory — narrowed to the covered ones — beside the agreements it offers. */
@Composable
private fun TerritoryAndAgreement(builder: BuilderState, ops: FormOps) {
    val form = builder.form
    val territoryId = form.text("territory")
    val territory = TerritoryCatalogue.territory(territoryId)
    val covered = builder.reference.coveredTerritories?.takeIf { it.isNotEmpty() }
    CardBlock(
        title = str(S.dm_step1_card_territory),
        headerTrailing = territory?.let {
            {
                BuilderTag(
                    str(S.dm_step1_territory_subheading, it.label),
                    BuilderTone.Gold,
                    leading = { TerritoryFlag(it.id, 12.dp) },
                )
            }
        },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Field(str(S.dm_section_territory), required = true) {
                    TerritoryPicker(
                        selected = territoryId,
                        covered = covered,
                        onPick = { id ->
                            ops.patch(
                                "territory" to JsonPrimitive(id.orEmpty()),
                                "union" to JsonPrimitive(""),
                                "pactBand" to JsonPrimitive(""),
                                "designation" to JsonPrimitive(""),
                                "jobTitle" to JsonPrimitive(""),
                                "customJobTitle" to JsonPrimitive(""),
                            )
                        },
                    )
                    covered?.let { ids ->
                        CoverageNote(TerritoryCatalogue.territories.filter { it.id in ids }.map { it.label })
                    }
                }
            }
            if (territoryId.isNotEmpty()) {
                Column(Modifier.weight(AGREEMENTS_WEIGHT)) {
                    Agreements(builder, territory?.label ?: territoryId.uppercase(), ops)
                }
            }
        }
    }
}

@Composable
private fun TerritoryPicker(selected: String, covered: Set<String>?, onPick: (String?) -> Unit) {
    val territories = TerritoryCatalogue.territories.filter { covered == null || it.id in covered || it.id == selected }
    val current = TerritoryCatalogue.territory(selected)
    RichSelect(
        options = territories.map { territory ->
            val currency = TerritoryCatalogue.defaultCurrency(territory.id).orEmpty()
            PickOption(
                key = territory.id,
                label = territory.label,
                sub = listOf(territory.code, currency).filter { it.isNotEmpty() }.joinToString(" · "),
                search = "${territory.label} ${territory.id} $currency",
            )
        },
        selectedKey = selected.ifEmpty { null },
        onPick = onPick,
        placeholder = str(S.desktop_dm_select_territory_placeholder),
        clearable = false,
        dropdownWidth = 320.dp,
        triggerText = current?.let { territory ->
            listOfNotNull(territory.label, TerritoryCatalogue.defaultCurrency(territory.id)).joinToString("  ")
        },
        leading = { current?.let { TerritoryFlag(it.id, 16.dp) } },
        rowLeading = { option -> TerritoryFlag(option.key, 18.dp) },
    )
}

/** "Currently offered in these territories only: …" — five names, then "+n more". */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CoverageNote(names: List<String>) {
    if (names.isEmpty()) return
    val p = bp
    var expanded by remember { mutableStateOf(false) }
    val collapsed = names.size > COVERAGE_PREVIEW && !expanded
    val style = DmType.sans(13.sp, FontWeight.Medium)
    FlowRow(modifier = Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (collapsed) {
            ZillitText(
                text = str(S.desktop_dm_offered_territories_prefix, names.take(COVERAGE_PREVIEW).joinToString(", ")),
                style = style,
                color = p.ink2,
            )
            val (source, hovered) = rememberHover()
            ZillitText(
                text = str(S.desktop_dm_n_more, names.size - COVERAGE_PREVIEW),
                style = style.copy(
                    fontWeight = FontWeight.SemiBold,
                    textDecoration = if (hovered) TextDecoration.Underline else null,
                ),
                color = p.gold,
                modifier = Modifier
                    .hoverable(source)
                    .clickable(interactionSource = source, indication = null) { expanded = true }
                    .pointerHoverIcon(PointerIcon.Hand),
            )
        } else {
            val joined = if (names.size == 1) {
                names.single()
            } else {
                str(S.desktop_docdist_x_and_y, names.dropLast(1).joinToString(", "), names.last())
            }
            ZillitText(text = str(S.desktop_dm_offered_territories_only, joined), style = style, color = p.ink2)
        }
    }
}

@Suppress("LongMethod")
@Composable
private fun Agreements(builder: BuilderState, territoryLabel: String, ops: FormOps) {
    val p = bp
    val reference = builder.reference
    FieldLabel(str(S.dm_step1_agreements_header, territoryLabel))
    val shape = RoundedCornerShape(10.dp)
    when {
        reference.agreementsLoading -> Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(p.inputBg)
                .border(1.dp, p.hairline, shape)
                .padding(vertical = 24.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitSpinner(size = 14.dp, color = p.cta)
            ZillitText(
                text = str(S.desktop_loading_agreements),
                style = DmType.sans(12.sp),
                color = p.muted,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        reference.agreements.isEmpty() -> Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(p.inputBg)
                .dashedBorder(p.dashed, 10.dp)
                .padding(vertical = 24.dp),
            contentAlignment = Alignment.Center,
        ) {
            ZillitText(
                text = str(S.desktop_dm_no_union_agreements_in, territoryLabel),
                style = DmType.sans(11.5.sp),
                color = p.muted,
            )
        }
        else -> RichSelect(
            options = reference.agreements.map { agreement ->
                val label = text(agreement, "label")
                val short = text(agreement, "short_label")
                PickOption(
                    key = text(agreement, "id"),
                    label = label,
                    sub = short.uppercase().ifEmpty { null },
                    search = "$label $short",
                )
            },
            selectedKey = builder.form.text("union").ifEmpty { null },
            onPick = { id ->
                ops.patch(
                    "union" to JsonPrimitive(id.orEmpty()),
                    "pactBand" to JsonPrimitive(""),
                    "designation" to JsonPrimitive(""),
                    "jobTitle" to JsonPrimitive(""),
                    "customJobTitle" to JsonPrimitive(""),
                )
            },
            placeholder = str(S.dm_step1_agreement_placeholder),
        )
    }
}

/** The agreement's budget bands — which scale the rate card reads — and its special departments. */
@Composable
private fun BudgetBand(builder: BuilderState, ops: FormOps) {
    val agreement = builder.reference.selectedUnion ?: return
    val pact = agreement["pact"] as? JsonObject
    val bands = objects(pact?.get("bands"))
    if (bands.isEmpty()) return
    val form = builder.form
    val departments = objects(pact?.get("special_depts"))
    CardBlock(
        title = str(
            S.dm_step1_budget_band_title_dynamic,
            text(agreement, "short_label").ifEmpty { str(S.dm_rule_import_agreement) },
        ),
        tag = str(S.desktop_dm_mandatory_clause_3_3),
        tone = BuilderTone.Purple,
    ) {
        BuilderGrid(columns = 2, verticalAlignment = Alignment.Top) {
            cell {
                Field(str(S.dm_step1_budget_band_title), required = true) {
                    NativeSelect(
                        value = form.text("pactBand"),
                        options = bands.map { band ->
                            val label = text(band, "label")
                            val threshold = text(band, "threshold")
                            PickOption(text(band, "band"), if (threshold.isNotEmpty()) "$label — $threshold" else label)
                        },
                        onPick = { ops.set("pactBand", it) },
                        placeholder = str(S.desktop_dm_select_band_placeholder),
                    )
                }
            }
            if (departments.isNotEmpty()) {
                cell {
                    Field(specialDepartmentLabel(pact, bands)) {
                        ToggleRow(
                            title = departments.joinToString(", ") {
                                text(it, "label").ifEmpty { text(it, "department_identifier") }
                            },
                            sub = null,
                            checked = form.flag("pactSpecialDept"),
                            onChange = { ops.set("pactSpecialDept", !form.flag("pactSpecialDept")) },
                        )
                    }
                }
            }
        }
    }
}

/** `Special Department (10+1 contracted hours)?`, the base hours read off the first band's notes. */
private fun specialDepartmentLabel(pact: JsonObject?, bands: List<JsonObject>): String {
    val extra = (pact?.get("extra_contracted_hours") as? JsonObject)?.get("hrs")?.takeIf(Js::truthy)
        ?: return str(S.desktop_dm_special_department_10_1_contracted_hours)
    val base = HOURS_IN_NOTES.find(text(bands.first(), "notes"))?.value ?: "10"
    return str(S.desktop_dm_special_department_question, base, Js.text(extra))
}

// -- companies ---------------------------------------------------------------------------------------

/** "Add your first company" and the New company form — over the whole page, never inside the section. */
@Composable
internal fun CompanyDialogs(state: DealMemoUiState, builder: BuilderState, onEvent: (DealMemoEvent) -> Unit) {
    val page = builder.setupPage
    DmModal(
        visible = page.companyNotice,
        title = str(S.desktop_dm_add_your_first_company),
        onDismiss = { onEvent(BuilderEvent.CloseCompanyNotice(proceed = false)) },
        maxWidth = 420.dp,
        footer = {
            DmButton(
                str(S.dd_action_got_it),
                { onEvent(BuilderEvent.CloseCompanyNotice(proceed = true)) },
                DmButtonStyle.ModalPrimary,
            )
        },
    ) {
        ZillitText(
            text = str(S.desktop_dm_fill_in_the_company_details_for_the),
            style = DmType.sans(14.sp).copy(lineHeight = 21.sp),
            color = bp.ink2,
            modifier = Modifier.padding(24.dp),
        )
    }
    val draft = page.companyDraft
    DmModal(
        visible = draft != null,
        title = str(S.desktop_new_company),
        onDismiss = { onEvent(BuilderEvent.CloseCompany) },
        maxWidth = 560.dp,
        dismissible = !page.companySaving,
        footer = {
            DmButton(
                str(S.dm_cancel),
                { onEvent(BuilderEvent.CloseCompany) },
                DmButtonStyle.ModalNeutral,
                enabled = !page.companySaving,
            )
            DmButton(
                text = if (page.companySaving) str(S.dm_nda_saving) else str(S.desktop_add_company),
                onClick = { onEvent(BuilderEvent.SaveCompany) },
                style = DmButtonStyle.ModalPrimary,
                enabled = draft != null && CompanyDraft.ready(draft) && !page.companySaving,
            )
        },
    ) {
        draft ?: return@DmModal
        CompanyForm(state, draft) { values -> onEvent(BuilderEvent.EditCompany(values)) }
    }
}

@Suppress("LongMethod")
@Composable
private fun CompanyForm(state: DealMemoUiState, draft: JsonObject, onChange: (Map<String, JsonElement>) -> Unit) {
    val uk = draft["uk"] as? JsonObject
    val gb = CompanyDraft.isGb(draft)
    fun patchUk(key: String, value: String) {
        val blank = mapOf("paye_ref" to JsonPrimitive(""), "accounts_office_ref" to JsonPrimitive(""))
        onChange(mapOf("uk" to JsonObject(blank + uk.orEmpty() + (key to JsonPrimitive(value)))))
    }
    Column(Modifier.padding(horizontal = 24.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Field(str(S.desktop_dm_company_entity_name), required = true) {
            BuilderInput(
                value = text(draft, "name"),
                onValueChange = { onChange(mapOf("name" to JsonPrimitive(it))) },
                placeholder = str(S.ps_company_name_hint),
            )
        }
        Field(str(S.ps_company_legal_name)) {
            BuilderInput(
                value = text(draft, "legal_name"),
                onValueChange = { onChange(mapOf("legal_name" to JsonPrimitive(it))) },
                placeholder = str(S.desktop_hub_as_registered_at_companies_house),
            )
        }
        Field(str(S.dm_step2_address_country), required = true) {
            val countries = state.production.countries.sortedBy { it.name }
            CountryPicker(
                countries = countries,
                selectedCode = text(draft, "country_code").ifEmpty { null },
                triggerText = text(draft, "country").ifEmpty { null },
                placeholder = str(S.desktop_hub_type_country_name_or_iso_code),
                onPick = { country ->
                    onChange(
                        mapOf(
                            "country" to JsonPrimitive(country?.name.orEmpty()),
                            "country_code" to JsonPrimitive(country?.code.orEmpty()),
                        ),
                    )
                },
                rowStyle = CountryRowStyle.Country,
                height = CONTROL_HEIGHT,
                textSize = 14f,
                radius = RADIUS,
            )
        }
        if (gb) {
            val paye = uk?.get("paye_ref")?.let(Js::text).orEmpty()
            Field(
                str(S.ps_company_paye_ref),
                error = CrewFormRules.PAYE_ERROR.takeUnless { CrewFormRules.validPayeRef(paye) },
            ) {
                BuilderInput(
                    value = paye,
                    onValueChange = { patchUk("paye_ref", it.trim().take(CompanyDraft.UK_REF_MAX)) },
                    placeholder = "123/AB456",
                    error = !CrewFormRules.validPayeRef(paye),
                )
            }
            val office = uk?.get("accounts_office_ref")?.let(Js::text).orEmpty()
            val officeValid = CrewFormRules.validAccountsOfficeRef(office)
            Field(str(S.ps_company_ao_ref), error = CrewFormRules.ACCOUNTS_OFFICE_ERROR.takeUnless { officeValid }) {
                BuilderInput(
                    value = office,
                    onValueChange = { patchUk("accounts_office_ref", it.trim().take(CompanyDraft.UK_REF_MAX)) },
                    placeholder = "123PA00012345",
                    error = !officeValid,
                )
            }
        }
        TaxCreditsField((draft["tax_credits"] as? JsonArray).orEmpty().map(Js::text)) { tags ->
            onChange(mapOf("tax_credits" to JsonArray(tags.map { JsonPrimitive(it) })))
        }
    }
}

/** Tax credit regimes as chips: Enter or a comma adds, Backspace on an empty field takes the last back. */
@Suppress("CyclomaticComplexMethod", "LongMethod")
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TaxCreditsField(tags: List<String>, onChange: (List<String>) -> Unit) {
    val p = bp
    var draft by remember { mutableStateOf("") }
    var focused by remember { mutableStateOf(false) }
    fun commit() {
        val parts = draft.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return
        val next = tags.toMutableList()
        parts.forEach { tag -> if (next.none { it.equals(tag, ignoreCase = true) }) next += tag }
        onChange(next)
        draft = ""
    }
    Field(str(S.dm_nom_card_tax_credit)) {
        val shape = RoundedCornerShape(8.dp)
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(p.infoBox)
                .border(1.dp, if (focused) p.focusBorder else p.inputBorder, shape)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            tags.forEachIndexed { index, tag ->
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(p.amberSoft)
                        .border(1.dp, p.amberRing, RoundedCornerShape(6.dp))
                        .padding(start = 10.dp, end = 4.dp, top = 3.dp, bottom = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    ZillitText(text = tag, style = DmType.sans(12.5.sp, FontWeight.SemiBold), color = p.cta)
                    Box(
                        modifier = Modifier.size(18.dp).clip(RoundedCornerShape(4.dp))
                            .clickable { onChange(tags.filterIndexed { i, _ -> i != index }) }
                            .pointerHoverIcon(PointerIcon.Hand),
                        contentAlignment = Alignment.Center,
                    ) { ZillitIcon(ZillitIcons.Close, size = 9.dp, tint = p.cta) }
                }
            }
            Box(Modifier.widthIn(min = 160.dp).padding(vertical = 4.dp)) {
                val style = DmType.sans(13.sp, FontWeight.Medium)
                if (draft.isEmpty()) {
                    ZillitText(
                        text = if (tags.isEmpty()) str(S.desktop_hub_type_a_regime_press_enter_e_g_uk_hetv_paren)
                            else str(S.desktop_dm_add_another_ellipsis),
                        style = style,
                        color = p.placeholder,
                    )
                }
                BasicTextField(
                    value = draft,
                    onValueChange = { typed ->
                        if (typed.endsWith(",")) {
                            draft = typed.dropLast(1)
                            commit()
                        } else {
                            draft = typed
                        }
                    },
                    singleLine = true,
                    textStyle = style.copy(color = p.ink),
                    cursorBrush = SolidColor(p.cta),
                    keyboardActions = KeyboardActions(onDone = { commit() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { state ->
                            if (focused && !state.isFocused) commit()
                            focused = state.isFocused
                        }
                        .onPreviewKeyEvent { event ->
                            when {
                                event.type != KeyEventType.KeyDown -> false
                                event.key == Key.Enter -> {
                                    commit()
                                    true
                                }
                                event.key == Key.Backspace && draft.isEmpty() && tags.isNotEmpty() -> {
                                    onChange(tags.dropLast(1))
                                    true
                                }
                                else -> false
                            }
                        },
                )
            }
        }
    }
}

@Composable
private fun Monogram(name: String) {
    val p = bp
    Box(Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)).background(p.tile), contentAlignment = Alignment.Center) {
        ZillitText(
            text = name.split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1).uppercase() },
            style = DmType.sans(12.sp, FontWeight.Bold),
            color = p.ink2,
            maxLines = 1,
        )
    }
}

private fun text(row: JsonObject?, key: String): String =
    row?.get(key)?.takeUnless(Js::isNullish)?.let(Js::text).orEmpty()

private fun objects(value: JsonElement?): List<JsonObject> = (value as? JsonArray).orEmpty().mapNotNull {
    it as? JsonObject
}

private val HOURS_IN_NOTES = Regex("\\d+(?=\\s*hr)", RegexOption.IGNORE_CASE)
private const val COVERAGE_PREVIEW = 5
private const val AGREEMENTS_WEIGHT = 1.3f
