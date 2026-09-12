package com.zillit.desktop.feature.bankrec.domain

import com.zillit.desktop.core.common.ZillitResult
import kotlinx.coroutines.flow.Flow

/** A statement file chosen or dropped, not yet stored. */
class PickedStatement(val name: String, val bytes: ByteArray) {
    val extension: String get() = name.substringAfterLast('.', "").lowercase()
}

/**
 * Where a statement file comes from and where it is kept.
 *
 * Two steps rather than one, because the dialog is two steps: the file is
 * chosen, shown, and only stored when the accountant presses import — as the
 * web's drop zone does. A cancelled picker answers success with null.
 */
interface StatementFiles {
    suspend fun pick(): ZillitResult<PickedStatement?>

    suspend fun upload(file: PickedStatement): ZillitResult<StatementUpload>

    companion object {
        /** The web's `accept` list, by extension — an OS reports `.ofx` and `.qif` as anything. */
        val EXTENSIONS: Set<String> = setOf("csv", "ofx", "qfx", "qif", "mt940", "sta", "940", "pdf")

        const val MAX_BYTES: Long = 20L * 1024 * 1024
    }
}

/** Puts an exported file where the accountant can open it. */
fun interface BankRecFiles {
    suspend fun saveAndOpen(fileName: String, bytes: ByteArray): ZillitResult<Unit>
}

/** A person, as the module names them. */
data class BankRecPerson(val name: String, val designation: String = "") {
    /** Two initials for the sign-off avatar — the web's `initialsFromName`. */
    val initials: String
        get() = name.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.take(2)
            .joinToString("") { it.first().uppercase() }
}

/**
 * The crew, for the names this service stores as ids.
 *
 * Signed-off-by, performed-by: every one of them an id on the wire, and an id is
 * never shown — a miss reads as a dash.
 */
fun interface BankRecDirectory {
    fun person(userId: String): BankRecPerson?

    /** Whoever is signed in — the portal summary's "Prepared by". Null when the host cannot say. */
    fun me(): BankRecPerson? = null
}

/** A tax type from Production Setup, as the quick forms offer it. */
data class TaxOption(
    val identifier: String,
    val label: String,
    /** A percentage — 20, not 0.2 — or null for a type with no rate set. */
    val ratePercent: Double?,
    val country: String = "",
) {
    /** `VAT · 20%`, as the web's option reads. */
    val optionLabel: String
        get() = ratePercent?.let { "$label · ${percentText(it)}%" } ?: label

    companion object {
        /** The custom-rate choice every tax selector ends with. */
        const val OTHER = "other"

        fun percentText(value: Double): String =
            if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
    }
}

/** A chart-of-accounts code a posting can be coded to. */
data class NominalCode(val code: String, val name: String)

/**
 * The production settings the quick forms read.
 *
 * All four belong to other services — tax types and the chart to the account
 * hub, the lock to the cost report, departments to admin — which is why they
 * are a seam and not calls of this module's own. Each answers empty on failure:
 * a selector with nothing in it is honest, an error over a working form is not.
 */
interface BankRecLookups {
    suspend fun taxTypes(): List<TaxOption>

    /** Postable codes only — nominals and codes, never the headers above them. */
    suspend fun nominalCodes(): List<NominalCode>

    /**
     * The cost report's lock, `YYYY-MM-DD`, or null when nothing is closed.
     *
     * No posting may be dated on or before it: the server refuses, and the
     * form says so before it has to.
     */
    suspend fun lockedThrough(): String?

    /** Department names by id, for an invoice's reference line. */
    suspend fun departments(): Map<String, String>

    /** Who the exports are for. */
    fun company(): CompanyDetails

    companion object {
        val None: BankRecLookups = object : BankRecLookups {
            override suspend fun taxTypes(): List<TaxOption> = emptyList()
            override suspend fun nominalCodes(): List<NominalCode> = emptyList()
            override suspend fun lockedThrough(): String? = null
            override suspend fun departments(): Map<String, String> = emptyMap()
            override fun company(): CompanyDetails = CompanyDetails()
        }
    }
}

/**
 * The module's tab counts, from the notification ledger.
 *
 * Keyed by the ledger's `level_1` — one key per tab — under the account hub's
 * `bank_recon_label` unit. A tab's count clears when the tab is looked at,
 * which is the web's rule: chips mean "something happened here since you last
 * came", not "work outstanding".
 */
interface BankRecBadges {
    val counts: Flow<Map<String, Int>>

    /** Clears one tab's chip — here at once, and on the server for the viewer's other devices. */
    suspend fun markRead(level1: String)
}
