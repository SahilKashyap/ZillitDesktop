package com.zillit.desktop.feature.dealmemo.ui.preview

import com.zillit.desktop.feature.dealmemo.domain.preview.NominalForm
import com.zillit.desktop.feature.dealmemo.domain.rules.BulkRuleRow
import com.zillit.desktop.feature.dealmemo.ui.DealCoaAccount

/** The production's chart of accounts, fetched once for every nominal picker. */
data class CoaState(
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val failed: Boolean = false,
    val accounts: List<DealCoaAccount> = emptyList(),
)

/** "Update Nominals" (`UpdateNominalsModal.jsx`): the codes being edited against the codes on file. */
data class NominalsEditorState(
    val form: NominalForm,
    /** `nominalCodeSignature` of what was loaded — typing then clearing is not a change. */
    val baseline: String,
    val saving: Boolean = false,
    /** "Unsaved changes" — asked on Cancel or Escape while dirty. */
    val confirmLeave: Boolean = false,
) {
    val dirty: Boolean get() = form.signature != baseline
}

/** Where a rules grid can pull more rules from — Production's non-union pay rules on a deal. */
data class RuleImportSource(val label: String, val rows: List<BulkRuleRow> = emptyList(), val loading: Boolean = true)

/** "Update Non-Union Pay breakdown" — the full-page rules grid over one deal's rules. */
data class RulesEditorState(
    val rows: List<BulkRuleRow>,
    /** A Save was attempted, so incomplete cells now show red. */
    val tried: Boolean = false,
    val saving: Boolean = false,
    val importSource: RuleImportSource? = null,
    /** "Added n · m already here", after an import. */
    val importNote: Pair<Int, Int>? = null,
    /** Offers "Import union rules" — the project's rule book, not a deal's. */
    val agreementImport: Boolean = false,
) {
    val readyCount: Int get() = rows.count { it.ready }
    val allReady: Boolean get() = readyCount == rows.size
}
