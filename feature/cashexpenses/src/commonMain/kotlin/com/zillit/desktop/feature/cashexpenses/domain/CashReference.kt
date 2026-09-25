package com.zillit.desktop.feature.cashexpenses.domain

import com.zillit.desktop.core.common.Money
import com.zillit.desktop.core.common.ZillitResult

// The production's reference data this module reads but does not own — its
// currencies, departments and chart of accounts, all Account Hub documents —
// and the attachment picker, which belongs to the desktop. Each arrives
// through a host seam on the view model; see `CashExpensesViewModel`.

/** One currency the production transacts in — Production Setup's Project Currencies. */
data class CashCurrency(val code: String, val symbol: String = "")

/**
 * The production's currencies and the one every aggregate is shown in.
 *
 * The web's `useCashCurrency` (`useCashCurrency.js`): a record renders in its
 * own currency, then the project default, then [LEGACY_DEFAULT] so a symbol
 * shows before the default resolves.
 */
data class CashCurrencies(
    val currencies: List<CashCurrency> = emptyList(),
    /** Production Setup's default; null until it is read, or on a project that has none. */
    val defaultCode: String? = null,
) {
    /** The code aggregates render in — never blank. */
    val default: String get() = defaultCode?.takeIf { it.isNotBlank() } ?: LEGACY_DEFAULT

    /**
     * A record's currency — `record.currency || record.transaction_currency ||
     * default` (`resolveCashCurrency`). The DTOs already fold
     * `transaction_currency` into each record's `currency`, so the record's
     * own code is the one argument.
     */
    fun codeFor(recordCurrency: String?): String = recordCurrency?.takeIf { it.isNotBlank() } ?: default

    fun codeFor(float: CashFloat): String = codeFor(float.currency)

    fun codeFor(batch: ClaimBatch): String = codeFor(batch.currency)

    fun codeFor(topUp: CashTopUp): String = codeFor(topUp.currency)

    /** An amount belonging to one record, in that record's currency. */
    fun format(amount: Double?, recordCurrency: String?): String = Money.format(amount, codeFor(recordCurrency))

    /** A total with no single record behind it — in the project default. */
    fun formatAggregate(amount: Double?): String = Money.format(amount, default)

    fun symbolFor(code: String?): String =
        currencies.firstOrNull { it.code.equals(code, ignoreCase = true) }?.symbol.orEmpty()

    companion object {
        const val LEGACY_DEFAULT = "GBP"
    }
}

/** A department of the production — the Account Hub's department list. */
data class CashDepartment(val id: String, val name: String)

/**
 * One row of the chart of accounts, as the coding pickers need it.
 *
 * [balanceSheet] is the account class — asset, liability or capital against
 * the P&L's income and expense; [postable] the row's own "may be coded
 * against" flag; [leaf] whether no other row sits below it.
 */
data class CashAccount(
    val code: String,
    val name: String = "",
    val balanceSheet: Boolean = false,
    val postable: Boolean = true,
    val leaf: Boolean = true,
) {
    /** "5010 — Materials", or the bare code — the web's `coaLabel`. */
    val label: String get() = if (name.isNotBlank()) "$code — $name" else code
}

/** Nominal codes as the ledger reads them. */
object CashNominals {

    /**
     * A code the chart does not hold goes out as `[[code]]` — the web's
     * `wrapNominal` (`lib/coa.js`), which tells the ledger to create it.
     *
     * Applied only when a payload is built, never to what is held, so every
     * reader keeps the clean code. An empty or unread chart wraps nothing: a
     * load race must not flag every existing code as new. A code in the chart
     * in another case is the existing one.
     */
    fun wrap(code: String?, chart: List<CashAccount>): String {
        val clean = code?.trim().orEmpty()
        if (clean.isEmpty() || chart.isEmpty()) return clean
        if (chart.any { it.code == clean || it.code.equals(clean, ignoreCase = true) }) return clean
        return "[[$clean]]"
    }
}

/** Departments joined onto the crew. */
object CashDepartments {

    /**
     * The crew with a department id wherever one can be found.
     *
     * The crew list (`project/users`) carries a department *name* and no id,
     * and the admin department list the ids and the same `department_name`.
     * A person the host already gave an id keeps it; the rest are matched by
     * name, normalised (`department_accounts` and "Accounts" both read
     * "accounts"). A name two departments share matches neither — a wrong
     * department is worse than none.
     */
    fun withIds(crew: List<AssigneeOption>, departments: List<CashDepartment>): List<AssigneeOption> {
        if (departments.isEmpty()) return crew
        val byName = departments.groupBy { it.name.departmentKey() }
            .filterValues { it.size == 1 }
            .mapValues { it.value.single().id }
        return crew.map { person ->
            if (person.departmentId.isNotBlank()) return@map person
            val id = byName[person.department.departmentKey()] ?: return@map person
            person.copy(departmentId = id)
        }
    }

    /** The department's name for [id], or null. */
    fun nameOf(id: String?, departments: List<CashDepartment>): String? =
        id?.let { wanted -> departments.firstOrNull { it.id == wanted }?.name }

    internal fun String.departmentKey(): String = normalised().removePrefix("department ").trim()
}

/**
 * A stored file, in the object the claim routes take — the web's
 * `uploadAttachment` shape: `{media, bucket, region, name, content_type,
 * content_subtype, caption}`, the MIME type split at its slash.
 */
data class CashAttachment(
    /** The storage key. */
    val media: String,
    val bucket: String = "",
    val region: String = "",
    val name: String = "",
    /** `image`, `application`… — the MIME type's first half. */
    val contentType: String = "",
    /** `png`, `pdf`… — its second half. */
    val contentSubtype: String = "",
    val caption: String = "",
) {
    val isPdf: Boolean
        get() = contentSubtype.equals(PDF, ignoreCase = true) || name.endsWith(".$PDF", ignoreCase = true) ||
            media.endsWith(".$PDF", ignoreCase = true)

    companion object {
        private const val PDF = "pdf"

        /** Splits `image/png` into its two halves; a type with no slash is all first half. */
        fun splitMime(mime: String?): Pair<String, String> {
            val value = mime?.trim().orEmpty()
            return value.substringBefore('/') to value.substringAfter('/', "")
        }
    }
}

/**
 * Picks a receipt on this machine and puts it where the cash service can
 * read it — images and PDF, 10 MB at most.
 *
 * A host seam, as the card module's `CardAttachmentUploader` is: the picker
 * belongs to the desktop and the store to the production, and the claim
 * routes take only a pointer. Success carrying null is a cancelled picker.
 */
fun interface CashAttachmentUploader {
    suspend fun pick(): ZillitResult<CashAttachment?>
}

/**
 * The crew member's floats, oldest first — the web's `sortFloatsOldestFirst`
 * (`lib/floatOrder.js`).
 *
 * Several floats can be open at once, and every surface that names "the"
 * float takes the first; unsorted, that was whatever order the API answered
 * in, so two surfaces could name two floats. Oldest first is also how the
 * cash is spent. A float with no date sorts as the oldest, as `Number(0)` does.
 */
object CashFloatOrder {
    fun oldestFirst(floats: List<CashFloat>): List<CashFloat> = floats.sortedBy { it.createdAt ?: 0L }
}

/**
 * The host seams for the production's reference data, bundled — one
 * constructor argument on the view model rather than four.
 *
 * Each answers the empty value on a failed read rather than throwing: money
 * then shows in [CashCurrencies.LEGACY_DEFAULT], pickers are empty, and
 * [CashNominals.wrap] wraps nothing.
 */
class CashReferenceSources(
    /**
     * Production Setup's Project Currencies and default — the source the
     * invoices tool reads (`accountHubRepository.currencies()`).
     */
    val currencies: suspend () -> CashCurrencies = { CashCurrencies() },
    /** The hub's department list. */
    val departments: suspend () -> List<CashDepartment> = { emptyList() },
    /** The chart of accounts, read on first need. */
    val chartAccounts: suspend () -> List<CashAccount> = { emptyList() },
    /** Picks and stores a receipt; null refuses the attach. */
    val uploader: CashAttachmentUploader? = null,
)
