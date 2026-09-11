package com.zillit.desktop.feature.accounthub.domain

/** What an import does to the chart of accounts it lands in. */
enum class CoaImportMode(val wire: String, val label: String, val detail: String) {
    Append(
        "append",
        "Merge into the chart",
        "New codes are added beside the ones already there.",
    ),

    /**
     * The imported codes become the only active ones.
     *
     * Existing codes are hidden rather than deleted, so orders and invoices
     * already coded against them keep working — which is why this is offered
     * at all rather than being too dangerous to expose.
     */
    Override(
        "override",
        "Replace the chart",
        "These become the only active codes. The rest are hidden, not deleted, " +
            "so anything already coded to them still works.",
    ),
    ;

    companion object {
        val Default = Append
    }
}

/** A top-level grouping in the parsed file. */
data class ParsedSection(val id: String = "", val name: String = "")

/** A code the parse found, under a section or under another code. */
data class ParsedCode(
    val code: String = "",
    val name: String = "",
    val amount: Double = 0.0,
    /** The section a header belongs to. */
    val sectionId: String = "",
    /** The header a nominal belongs to. */
    val parentCode: String = "",
)

/** A line the parse could not put a code to. */
data class ParsedUncoded(val name: String = "", val amount: Double = 0.0)

/**
 * What the server made of an uploaded budget file, before anything is written.
 *
 * The dry run is the whole point of the flow: the parse is a guess at somebody
 * else's spreadsheet, and it lands in the chart of accounts every other tool
 * codes against. Reviewing it is not a formality.
 */
data class ParsedBudget(
    val currency: String = "",
    val sections: List<ParsedSection> = emptyList(),
    val headers: List<ParsedCode> = emptyList(),
    val nominals: List<ParsedCode> = emptyList(),
    val uncoded: List<ParsedUncoded> = emptyList(),
    /** Duplicate codes, orphaned parents and the like, in the server's words. */
    val warnings: List<String> = emptyList(),
    val serverTotal: Double? = null,
) {
    /**
     * What the budget comes to.
     *
     * The server's figure where it gives one. Otherwise the headers plus the
     * uncoded lines — **not** the nominals, which roll up into their headers
     * and would be counted twice.
     */
    val total: Double
        get() = serverTotal ?: (headers.sumOf { it.amount } + uncoded.sumOf { it.amount })

    /** Nothing to save: a file that parsed to no codes at all. */
    val isEmpty: Boolean get() = headers.isEmpty() && uncoded.isEmpty()
}

/** What the new version will be called. */
data class BudgetImportMeta(
    val version: String = "",
    val label: String = "",
    val description: String = "",
) {
    val isComplete: Boolean get() = version.isNotBlank() && label.isNotBlank()
}

/** The file that was uploaded, as the commit identifies it. */
data class BudgetUpload(
    val uploadId: String = "",
    val fileName: String = "",
    val detectedFormat: String = "",
    val document: AgreementDocument? = null,
)

object BudgetImports {

    /**
     * The next free version for this production.
     *
     * Versions are unique **per production**, not per budget name — the
     * server's key is (project, version). So a brand-new Sound Budget in a
     * production already at v3 gets v4, not v1; numbering per name would
     * collide the moment two budgets reached the same number.
     *
     * The parse is tolerant, because the column is free text: an optional
     * leading `v` and digits, anything else ignored. "Rev A" and "Final"
     * simply do not take part. The final probe is the guarantee — whatever the
     * parse made of it, the answer is one no existing row holds.
     *
     * Comparison is exact. The server stores plain strings, so `V4` and `v4`
     * are genuinely different rows there, and matching case-insensitively
     * would report a collision that cannot happen.
     */
    fun suggestNextVersion(existing: List<BudgetVersion>): String {
        val taken = existing.map { it.version.trim() }.filter { it.isNotEmpty() }.toSet()
        val highest = taken.mapNotNull { VERSION.matchEntire(it)?.groupValues?.get(1)?.toIntOrNull() }
            .maxOrNull() ?: 0
        var next = highest + 1
        while ("v$next" in taken) next++
        return "v$next"
    }

    /** The highest version already used, for the "latest is v3" line. */
    fun latestVersion(existing: List<BudgetVersion>): String? =
        existing.map { it.version.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { version ->
                VERSION.matchEntire(version)?.groupValues?.get(1)?.toIntOrNull()?.let { it to version }
            }
            .maxByOrNull { it.first }
            ?.second

    /** A readable name from a filename: no extension, no underscores. */
    fun labelFrom(fileName: String): String =
        fileName.substringBeforeLast('.')
            .replace('_', ' ')
            .replace('-', ' ')
            .split(' ')
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .ifBlank { "Imported budget" }

    /** An optional `v`, then digits, then anything. */
    private val VERSION = Regex("""\s*[vV]?(\d+).*""")
}
