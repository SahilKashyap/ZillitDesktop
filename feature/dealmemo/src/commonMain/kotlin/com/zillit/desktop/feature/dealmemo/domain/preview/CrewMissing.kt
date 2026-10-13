package com.zillit.desktop.feature.dealmemo.domain.preview

import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.dealmemo.domain.DealDoc
import com.zillit.desktop.feature.dealmemo.domain.DocRead

/**
 * The crew form's red "still to fill" state (`CrewDetailsEditorPanel.jsx`
 * `missingPaths` + `payrollMissing`): exactly what the Send for Approval /
 * Sign pop-up is waiting on — every required path still empty, unfiltered,
 * plus the territory block's own missing labels. It gates nothing; keep it on
 * [CrewRequirements.missingLabels]' inputs or the form and the pop-up disagree.
 */
class CrewMissing private constructor(
    private val hints: Map<String, String>,
    val payroll: Boolean,
    val steps: Set<CrewStep>,
) {
    /** The paths still empty, in the requiredness model's order. */
    val paths: Set<String> get() = hints.keys

    val any: Boolean get() = hints.isNotEmpty() || payroll

    /** The hint under a red field, or null when the field is fine. */
    fun hint(path: String): String? = hints[path]

    /** The first red thing on [step] in the order it is drawn — a path, or [PAYROLL] — or null. */
    fun firstOn(step: CrewStep): String? =
        DRAWN.getValue(step).firstOrNull { if (it == PAYROLL) payroll else it in hints }

    companion object {
        /** Stands for the UK payroll card, which has no path of its own. */
        const val PAYROLL = "uk"

        /** `stepOfPath`: by prefix; no loan-out path can be required, so that step never reds. */
        fun stepOf(path: String): CrewStep = when {
            path.startsWith("bank.") -> CrewStep.Bank
            path.startsWith("emergency_") -> CrewStep.Emergency
            path.startsWith("representative_details.") -> CrewStep.Representative
            else -> CrewStep.Crew
        }

        /** Each step's fields top to bottom, which is where the form scrolls to first. */
        private val DRAWN: Map<CrewStep, List<String>> = mapOf(
            CrewStep.Crew to listOf(
                "full_legal_name", "preferred_name", "screen_credit_designation", "gender", "dob", "email", "mobile",
                "insurance_no", "tax_code", "right_to_work", "passport_attachment", "home_address", PAYROLL,
            ),
            CrewStep.Emergency to listOf(
                "emergency_contact_name", "emergency_contact_number", "emergency_details.email",
                "emergency_details.address",
            ),
            CrewStep.Representative to listOf(
                "representative_details.name", "representative_details.phone_number",
                "representative_details.email", "representative_details.address",
            ),
            CrewStep.Bank to listOf(
                "bank.account_holder_name", "bank.name", "bank.account_number", "bank.sort_code", "bank.iban_number",
                "bank.swift_code",
            ),
            CrewStep.LoanOut to emptyList(),
        )

        val REQUIRED: String get() = str(S.docusign_prop_required)

        /**
         * [deal] is the saved deal (its marks and territory), [draft] what has been typed. The territory block is
         * read off the draft-merged deal — the pop-up's own argument — so a block the draft lacks is missing.
         */
        fun of(deal: DealDoc, draft: CrewDraft): CrewMissing {
            val marked = CrewRequirements.markedPaths(deal)
            val missing = CrewRequirements.requiredPaths(marked, draft)
                .filter { CrewRequirements.isMissing(it, CrewRequirements.valueAt(draft, it)) }
            val merged = deal.withDraft(draft)
            val payroll = UkPayroll.appliesTo(merged) &&
                UkPayroll.missingLabels(DocRead.obj(merged.crew, UkPayroll.KEY)).isNotEmpty()
            val steps = missing.map(::stepOf).toSet() + if (payroll) setOf(CrewStep.Crew) else emptySet()
            return CrewMissing(missing.associateWith { hintFor(it, marked) }, payroll, steps)
        }

        /** The step the form opens on: the first with something red, else the first. */
        fun openingStep(deal: DealDoc, draft: CrewDraft): Int {
            val red = of(deal, draft).steps
            return CrewFormRules.steps(deal, draft).indexOfFirst { it in red }.coerceAtLeast(0)
        }

        /**
         * "Required", or — for a floor either/or pair the sender left alone — which half to enter instead; typing
         * either clears both, because the pair stops being required.
         */
        private fun hintFor(path: String, marked: List<String>): String {
            val pair = CrewRequirements.EITHER_OR.firstOrNull { path in it && it.none { half -> half in marked } }
            val other = pair?.firstOrNull { it != path } ?: return REQUIRED
            return str(S.desktop_dm_required_or_enter, CrewRequirements.labelOf(other))
        }
    }
}
