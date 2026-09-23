package com.zillit.desktop.feature.assetreport.ui

import com.zillit.desktop.core.common.looksLikeRawId
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.assetreport.domain.AssetAttachment
import com.zillit.desktop.feature.assetreport.domain.AssetCategory
import com.zillit.desktop.feature.assetreport.domain.AssetCurrencies
import com.zillit.desktop.feature.assetreport.domain.AssetDepartment
import com.zillit.desktop.feature.assetreport.domain.AssetExportFormat
import com.zillit.desktop.feature.assetreport.domain.AssetFileRules
import com.zillit.desktop.feature.assetreport.domain.AssetFormat
import com.zillit.desktop.feature.assetreport.domain.AssetLine
import com.zillit.desktop.feature.assetreport.domain.AssetPerson
import com.zillit.desktop.feature.assetreport.domain.AssetRecord
import com.zillit.desktop.feature.assetreport.domain.AssetViewer
import com.zillit.desktop.feature.assetreport.domain.extensionOf

/** The category segment's three positions. */
enum class CategoryFilter(private val labelKey: String) {
    All(S.all),
    Keep(S.asset_cat_keep),
    Sell(S.asset_cat_sell),
    ;

    val label: String get() = str(labelKey)
}

data class AssetUiState(
    val lines: List<AssetLine> = emptyList(),
    val vendors: Map<String, String> = emptyMap(),
    /** The admin listing's order, which the picker keeps. */
    val departments: List<AssetDepartment> = emptyList(),
    val departmentsLoading: Boolean = true,
    val people: Map<String, AssetPerson> = emptyMap(),
    val currencies: AssetCurrencies = AssetCurrencies(),
    /** The picked display currency; blank follows the production's default. */
    val currencyCode: String = "",
    val isLoading: Boolean = true,
    val viewer: AssetViewer = AssetViewer(),
    val query: String = "",
    val categoryFilter: CategoryFilter = CategoryFilter.All,
    /** Department ids; empty is every department. */
    val departmentFilter: List<String> = emptyList(),
    /** The open line and its record; null is the table. */
    val detail: AssetDetail? = null,
    val exporting: AssetExportFormat? = null,
    val error: String? = null,
) {
    val activeCurrency: String get() = currencyCode.ifBlank { currencies.defaultCode }

    val currencySymbol: String get() = currencies.symbolFor(activeCurrency)

    /** One amount, from its line's currency into the one on screen. */
    fun amount(value: Double, lineCurrency: String): Double =
        currencies.convert(value, lineCurrency, activeCurrency)

    fun money(value: Double, lineCurrency: String): String =
        AssetFormat.money(amount(value, lineCurrency), currencySymbol)

    /** The web's lookup: a vendor the list does not know is a dash, never an id. */
    fun vendorName(id: String): String = vendors[id]?.takeIf { it.isNotBlank() } ?: "—"

    /**
     * A department's name.
     *
     * Names from the directory are often label keys (`accounts_department_label`),
     * so they go through the dictionary; the feed has also been seen carrying a
     * key where an id belongs, which resolves the same way. An id nobody can name
     * is a dash rather than hex.
     */
    fun departmentName(id: String): String {
        val named = departments.firstOrNull { it.id == id }?.name
        val raw = named ?: id.takeIf { it.isNotBlank() && !it.looksLikeRawId() && it.contains('_') }
        return raw?.let(::readable)?.takeIf { it.isNotBlank() } ?: "—"
    }

    /** "Name · Designation", or null for someone the crew list has lost. */
    fun author(userId: String): String? {
        val person = people[userId] ?: return null
        val designation = readable(person.designation)
        return listOf(person.name.trim(), designation).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { null }
    }

    /**
     * What the table shows: departments (only a privileged viewer can narrow
     * them), the category, then the web's search — one haystack of description,
     * vendor, code, ref and department, run together as the web runs them.
     */
    val visible: List<AssetLine>
        get() {
            val needle = query.trim().lowercase()
            val wanted = departmentFilter.toSet()
            return lines.filter { line ->
                (wanted.isEmpty() || line.departmentId in wanted) &&
                    categoryFilter.admits(line.category) &&
                    (needle.isEmpty() || haystack(line).contains(needle))
            }
        }

    /** The pinned total, in the currency on screen; tax lines never count. */
    val total: Double
        get() = visible.filterNot { it.isTax }.sumOf { amount(it.total, it.currency) }

    private fun haystack(line: AssetLine): String = buildString {
        append(line.description)
        append(vendorName(line.vendorId))
        append(line.account)
        append(line.poNumber)
        append(departmentName(line.departmentId))
    }.lowercase()

    private companion object {
        /** A label key reads through the dictionary; a plain name is left as typed. */
        fun readable(value: String): String {
            val text = value.trim()
            return if (text.contains('_') && !text.contains(' ')) text.localised() else text
        }
    }
}

private fun CategoryFilter.admits(category: AssetCategory): Boolean = when (this) {
    CategoryFilter.All -> true
    CategoryFilter.Keep -> category == AssetCategory.Keep
    CategoryFilter.Sell -> category == AssetCategory.Sell
}

/**
 * One file in the detail's attachment list: stored, or picked and waiting for
 * Save. A pick's bytes stay in the view model — state compares by value, and a
 * `ByteArray` in it would make every comparison an array walk.
 */
sealed interface DraftFile {
    val key: String
    val name: String
    val extension: String
    val isImage: Boolean

    data class Saved(val attachment: AssetAttachment) : DraftFile {
        override val key: String get() = "saved:${attachment.media}"
        override val name: String get() = attachment.displayName
        override val extension: String get() = attachment.extension
        override val isImage: Boolean get() = attachment.isImage
    }

    data class Pending(val localId: String, override val name: String, val sizeBytes: Long) : DraftFile {
        override val key: String get() = "pending:$localId"
        override val extension: String get() = extensionOf(name)
        override val isImage: Boolean get() = extension in AssetFileRules.IMAGE_EXTENSIONS
    }
}

/**
 * The open asset. Nothing here writes on change: the category and the files
 * wait for the header's Save, the note for its own.
 *
 * [record] arrives by hydration and editing stays locked until it has — a
 * blank note saved over one merely not fetched destroys it.
 */
data class AssetDetail(
    val line: AssetLine,
    val isHydrating: Boolean = true,
    /** The record could not be read; the editors stay shut until a retry lands. */
    val hydrationFailed: Boolean = false,
    val record: AssetRecord? = null,
    val categoryDraft: AssetCategory = line.category,
    val noteDraft: String = "",
    val files: List<DraftFile> = emptyList(),
    val isSaving: Boolean = false,
    /** The note's Save reads "Saved" for a moment after it lands. */
    val noteJustSaved: Boolean = false,
    /** The unsaved-changes question is on screen. */
    val confirmLeave: Boolean = false,
    /** The file open in the large viewer, by [DraftFile.key]. */
    val viewing: String? = null,
) {
    /** No record yet: the first write must create it. */
    val isNew: Boolean get() = record?.id.isNullOrBlank()

    val isLocked: Boolean get() = isHydrating || hydrationFailed || isSaving

    val noteDirty: Boolean get() = !isHydrating && noteDraft != record?.comments.orEmpty()

    val metaDirty: Boolean
        get() = !isHydrating && (
            categoryDraft != (record?.category ?: AssetCategory.None) ||
                files.map { it.key } != record?.attachments.orEmpty().map { DraftFile.Saved(it).key }
            )

    val anyDirty: Boolean get() = noteDirty || metaDirty

    val pendingCount: Int get() = files.count { it is DraftFile.Pending }

    val viewingFile: DraftFile? get() = viewing?.let { key -> files.firstOrNull { it.key == key } }
}
