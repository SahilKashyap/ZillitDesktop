package com.zillit.desktop.feature.dealmemo.domain.preview

import com.zillit.desktop.feature.dealmemo.domain.DealDoc
import com.zillit.desktop.feature.dealmemo.domain.DocRead
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Employment-status predicates (`dealCrew.js`) — the ids come from each
 * agreement's own `emp_statuses`, so they are read by shape, never by a list.
 */
object CrewStatus {

    /** `ltd`, `ltd-inside`, or anything naming a loan-out. */
    fun isLoanOut(id: String?): Boolean {
        val s = id.orEmpty().lowercase()
        return s == "ltd" || s == "ltd-inside" || "loanout" in s || "loan-out" in s
    }

    /** PAYE by exclusion: not blank, not loan-out, not Schedule D or 1099. Blank is false. */
    fun isPaye(id: String?): Boolean {
        val s = id.orEmpty().lowercase()
        return s.isNotEmpty() && !isLoanOut(s) && "schedule" !in s && "sch-d" !in s && "1099" !in s
    }

    /** Could be PAYE — blank answers yes. For asking the questions, never for requiring them. */
    fun mayBePaye(id: String?): Boolean = id.isNullOrEmpty() || isPaye(id)
}

/** One HMRC answer as the forms offer it. */
data class UkOption(
    val value: String?,
    val marker: String?,
    val short: String,
    val warn: String? = null,
    val lead: String? = null,
    val bullets: List<String> = emptyList(),
)

/** A read-only memo row the UK block contributes. */
data class UkMemoRow(val label: String, val value: String?, val mono: Boolean = false)

/**
 * The UK HMRC new-starter block — `crew_details.uk` (`ukPayrollFields.js`),
 * the only territory in the web's payroll registry today.
 *
 * The copy is HMRC's, hard-coded English on purpose; do not paraphrase.
 */
object UkPayroll {

    const val KEY = "uk"
    const val LABEL = "UK Payroll (HMRC)"
    const val ROUTE_STATEMENT = "statement"
    const val ROUTE_P45 = "p45"
    const val PENSION_DEFAULT = "opt_in"

    val P45_KEYS = listOf("p45_previous_pay", "p45_previous_tax", "p45_leaving_date", "p45_previous_paye_ref")

    private val P45_LABELS = mapOf(
        "p45_previous_pay" to "P45 previous pay",
        "p45_previous_tax" to "P45 previous tax",
        "p45_leaving_date" to "P45 leaving date",
        "p45_previous_paye_ref" to "P45 previous PAYE reference",
    )

    private const val NOT_ANSWERED = "Starter statement, or P45 details from the previous employer"
    private const val PENSION_WARN =
        "Do not choose this statement if you're in receipt of a State, Works or Private Pension."
    private val BENEFITS = listOf("Jobseeker's Allowance", "Employment and Support Allowance", "Incapacity Benefit")

    val STARTER_STATEMENTS = listOf(
        UkOption(
            "A", "A", "Statement A", warn = PENSION_WARN,
            lead = "This is my first job since 6 April and since 6 April I have not received payments from any of " +
                "the following:",
            bullets = BENEFITS,
        ),
        UkOption(
            "B", "B", "Statement B", warn = PENSION_WARN,
            lead = "Since 6 April I have had another job but I do not have a P45. And/or since 6 April I have " +
                "received payments from any of the following:",
            bullets = BENEFITS,
        ),
        UkOption(
            "C", "C", "Statement C",
            lead = "Choose this statement if:",
            bullets = listOf("You have another job, and/or", "You're in receipt of a State, Works or Private Pension"),
        ),
    )

    val STUDENT_LOAN_PLANS = listOf(
        UkOption(
            "plan_1", "1", "Plan 1", lead = "You have a Plan 1 Student Loan if:",
            bullets = listOf(
                "You lived in Northern Ireland when you started your course, or",
                "You lived in England or Wales and started your course before 1 September 2012",
            ),
        ),
        UkOption(
            "plan_2", "2", "Plan 2", lead = "You have a Plan 2 Student Loan if:",
            bullets = listOf(
                "You lived in England or Wales and started your course on or after 1 September 2012 and before " +
                    "1 August 2023",
                "Your loan is a Part Time Maintenance Loan",
                "Your loan is an Advanced Learner Loan",
                "Your loan is a Postgraduate Healthcare Loan",
            ),
        ),
        UkOption(
            "plan_4", "4", "Plan 4", lead = "You have a Plan 4 Student Loan if:",
            bullets = listOf("You lived in Scotland when you started your course"),
        ),
        UkOption(
            "plan_5", "5", "Plan 5", lead = "You have a Plan 5 Student Loan if:",
            bullets = listOf("You lived in England or Wales and started your course on or after 1 August 2023"),
        ),
        UkOption(
            null, null, "None of these", lead = "Choose this if:",
            bullets = listOf(
                "You have no Student Loan, or",
                "You are repaying your loan direct to the Student Loans Company by direct debit, or",
                "You finished or left your studies before the last 6 April",
            ),
        ),
    )

    val PG_LOAN_PLANS = listOf(
        UkOption(
            "plan_1", "1", "Plan 1",
            lead = "I have a Postgraduate Loan which is not fully repaid. You have one if:",
            bullets = listOf(
                "You lived in England and started your Postgraduate Master's course on or after 1 August 2016",
                "You lived in Wales and started your Postgraduate Master's course on or after 1 August 2017",
                "You lived in England or Wales and started your Postgraduate Doctoral course on or after " +
                    "1 August 2018",
            ),
        ),
        UkOption("plan_2", "2", "Plan 2", lead = "I finished my studies before the last 6 April."),
        UkOption(
            "plan_3", "3", "Plan 3",
            lead = "I am repaying my Postgraduate Loan direct to the Student Loans Company by direct debit.",
        ),
        UkOption(null, null, "None of these", lead = "You have no Postgraduate Loan."),
    )

    val PENSION_STATUSES = listOf(UkOption("opt_in", null, "Opt in"), UkOption("opt_out", null, "Opt out"))

    /**
     * `payrollForDeal`: the territory has a block (only `uk`) AND the crew
     * member is PAYE. Fails closed on a blank territory or status.
     */
    fun appliesTo(deal: DealDoc): Boolean = appliesTo(deal.territoryCode, DocRead.text(deal.crew, "emp_status"))

    fun appliesTo(territoryCode: String?, empStatus: String?): Boolean =
        territoryCode?.lowercase() == KEY && CrewStatus.isPaye(empStatus)

    /** `ukTaxRoute`: the route is derived from what is filled — there is no key for it. */
    fun route(block: JsonObject?): String = when {
        isFilled(block?.get("starter_statement")) -> ROUTE_STATEMENT
        P45_KEYS.any { isFilled(block?.get(it)) } -> ROUTE_P45
        else -> ""
    }

    /** `ukMissingLabels`: what stands between this crew member and Send for Approval. */
    fun missingLabels(block: JsonObject?): List<String> = when (route(block)) {
        ROUTE_STATEMENT -> emptyList()
        ROUTE_P45 -> P45_KEYS.filterNot { isFilled(block?.get(it)) }.map { P45_LABELS.getValue(it) }
        else -> listOf(NOT_ANSWERED)
    }

    /**
     * `ukMemoRows`: the P45 figures on that route (always sterling), otherwise
     * the starter statement; then the loans, NI category and pension. A null
     * loan answer prints the dash, never "None of these".
     */
    fun memoRows(block: JsonObject?, money: (JsonElement?) -> String, date: (Long?) -> String): List<UkMemoRow> {
        val rows = mutableListOf<UkMemoRow>()
        if (route(block) == ROUTE_P45) {
            rows += UkMemoRow("P45 Previous Pay", money(block?.get("p45_previous_pay")))
            rows += UkMemoRow("P45 Previous Tax", money(block?.get("p45_previous_tax")))
            rows += UkMemoRow("P45 Leaving Date", date(DocRead.epoch(block, "p45_leaving_date")))
            rows += UkMemoRow("P45 Previous PAYE Ref", DocRead.text(block, "p45_previous_paye_ref"), mono = true)
        } else {
            rows += UkMemoRow("Starter Statement", optionLabel(STARTER_STATEMENTS, block, "starter_statement"))
        }
        rows += UkMemoRow("Student Loan Plan", optionLabel(STUDENT_LOAN_PLANS, block, "student_loan_plan"))
        rows += UkMemoRow("Postgraduate Loan", optionLabel(PG_LOAN_PLANS, block, "pg_loan"))
        rows += UkMemoRow("NI Category", DocRead.text(block, "ni_category"), mono = true)
        val pension = DocRead.text(block, "pension_status") ?: PENSION_DEFAULT
        rows += UkMemoRow("Pension", PENSION_STATUSES.firstOrNull { it.value == pension }?.short)
        return rows
    }

    private fun optionLabel(options: List<UkOption>, block: JsonObject?, key: String): String? {
        val value = DocRead.text(block, key) ?: return null
        return options.firstOrNull { it.value == value }?.short
    }

    /** Not null, and not a string that trims to nothing. */
    private fun isFilled(element: JsonElement?): Boolean = when (element) {
        null, JsonNull -> false
        is JsonPrimitive -> element.content.isNotBlank()
        else -> true
    }
}

/** `territory_union.territory_code` — the lower-case wizard territory id. */
val DealDoc.territoryCode: String? get() = DocRead.text(DocRead.obj(json, "territory_union"), "territory_code")
