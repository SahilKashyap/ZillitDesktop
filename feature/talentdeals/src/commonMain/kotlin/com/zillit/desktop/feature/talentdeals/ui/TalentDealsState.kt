package com.zillit.desktop.feature.talentdeals.ui

import com.zillit.desktop.feature.talentdeals.domain.SigningDay
import com.zillit.desktop.feature.talentdeals.domain.TalentAttachment
import com.zillit.desktop.feature.talentdeals.domain.TalentContract
import com.zillit.desktop.feature.talentdeals.domain.TalentCurrencies
import com.zillit.desktop.feature.talentdeals.domain.TalentCurrency
import com.zillit.desktop.feature.talentdeals.domain.TalentDealsViewer
import com.zillit.desktop.feature.talentdeals.domain.TalentField
import com.zillit.desktop.feature.talentdeals.domain.TalentForm
import com.zillit.desktop.feature.talentdeals.domain.TalentRoles
import com.zillit.desktop.feature.talentdeals.domain.TalentTerritory
import com.zillit.desktop.feature.talentdeals.domain.TalentUnion
import com.zillit.desktop.feature.talentdeals.domain.TalentValidation

/** The register: search, rows, delete — and the role picker that starts Add Contract. */
data class RegisterState(
    val contracts: List<TalentContract> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    val query: String = "",
    val deleteTarget: TalentContract? = null,
    val deleting: Boolean = false,
    val rolePickerOpen: Boolean = false,
) {
    /** Search is client-side over the whole list, as All Deals does it. */
    val visible: List<TalentContract>
        get() {
            val needle = query.trim().lowercase()
            return if (needle.isEmpty()) contracts else contracts.filter { needle in it.searchText() }
        }
}

/** One contract, read-only. */
data class DetailState(
    val contractId: String? = null,
    val contract: TalentContract? = null,
    val loading: Boolean = true,
    val error: String? = null,
    /** The union's display name from the same lookup the form reads; blank falls back to the slug. */
    val unionName: String = "",
)

/** Exhibit G for the talent on screen — fetched only while the panel is open. */
data class ExhibitState(
    val open: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val days: List<SigningDay> = emptyList(),
    /** The row picked on each day that has several matching rows: report key → row key. */
    val picked: Map<String, String> = emptyMap(),
)

/** The Add / Edit form with everything around it. */
data class FormState(
    val role: String = "",
    val reference: String = "",
    val status: String = "issued",
    /** Edit: the contract being changed; null on Add. */
    val contractId: String? = null,
    /** Edit, row still on its way — the form shows its silhouette meanwhile. */
    val loadingRow: Boolean = false,
    val form: TalentForm = TalentForm(),
    val unions: List<TalentUnion> = emptyList(),
    val unionsLoading: Boolean = false,
    /** Errors only appear once someone tries to save. */
    val attempted: Boolean = false,
    val saving: Boolean = false,
    /** The name the Exhibit G lookup runs on — committed when the Name field loses focus. */
    val lookupName: String = "",
) {
    val isEdit: Boolean get() = contractId != null

    val unionOptions: List<TalentUnion> get() = TalentRoles.unionOptions(role, unions, form.union)

    val unionRequired: Boolean get() = TalentRoles.unionRequired(unionOptions)

    val exhibitApplies: Boolean get() = TalentRoles.exhibitGApplies(role, form.union)

    /** Every field that blocks the save; shown only after an attempt. */
    val errors: Set<TalentField> get() = TalentValidation.errors(form, unionRequired)

    val shownErrors: Set<TalentField> get() = if (attempted) errors else emptySet()
}

/** The production data the pickers read, loaded once per production. */
data class ReferenceState(
    val territories: List<TalentTerritory> = emptyList(),
    val currencies: TalentCurrencies = TalentCurrencies(),
) {
    /** The currency picker's rows for a form whose saved currency is [ensure]. */
    fun currencyOptions(ensure: String): List<TalentCurrency> = currencies.options(ensure)
}

/** The in-app file viewer. */
@Suppress("ArrayInDataClass") // The bytes are read once, to draw; nothing compares them.
data class FileViewState(
    val attachment: TalentAttachment,
    val loading: Boolean = true,
    val error: String? = null,
    val pages: List<TalentPdfPage> = emptyList(),
    /** An image's bytes, shown as they are. */
    val image: ByteArray? = null,
    /** The type cannot be previewed here. */
    val unsupported: Boolean = false,
)

data class TalentDealsUiState(
    val viewer: TalentDealsViewer = TalentDealsViewer(),
    val route: TalentRoute = TalentRoute.Register,
    val register: RegisterState = RegisterState(),
    val detail: DetailState = DetailState(),
    val form: FormState = FormState(),
    val exhibit: ExhibitState = ExhibitState(),
    val reference: ReferenceState = ReferenceState(),
    val viewing: FileViewState? = null,
) {
    /** The tool grid has answered and the viewer may not even enter. */
    val noAccess: Boolean get() = viewer.rightsLoaded && !viewer.canView

    /** What the currency field holds: the chosen one, else the project's default. */
    val activeCurrency: String get() = form.form.currency.ifBlank { reference.currencies.defaultCode.orEmpty() }

    val symbol: String
        get() = activeCurrency.takeIf { it.isNotBlank() }
            ?.let { code -> reference.currencies.get(code).symbol.ifBlank { code } }
            .orEmpty()
}
