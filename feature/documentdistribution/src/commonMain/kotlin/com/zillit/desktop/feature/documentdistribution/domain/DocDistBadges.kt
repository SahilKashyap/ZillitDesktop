package com.zillit.desktop.feature.documentdistribution.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * One unread leaf of the document-distribution ledger.
 *
 * A `document_distribution_label` row names the entity it is about in
 * `reference_id` and its unit (`…_folder_label`, `…_document_label`,
 * `…_distribution_label`, `…_publication_label`, `…_template_label`,
 * `…_preset_label`), and carries the folder path it sits in — `level_1..3`
 * plus a `levels[]` tail for deeper trees, with `root` standing for the top
 * (`BadgeDB.getDocumentDistributionBadgeCounts`). [ancestry] is every folder
 * id on that path, plus the folder itself for a folder event, so a row
 * counts once on each folder bubble above it — see [DocDistRowRef.ancestry].
 */
data class DocDistBadgeLeaf(
    val unit: String,
    val referenceId: String,
    val ancestry: Set<String>,
    val unread: Int,
)

/**
 * The tool's unread rows, and the reads the screens make: a folder entered
 * reads its whole subtree ([DocDistRowRef.isUnderFolder]); a file previewed
 * reads that file's; a side section opened reads its units whole.
 */
interface DocDistBadges {
    val leaves: Flow<List<DocDistBadgeLeaf>> get() = emptyFlow()

    fun readFolder(folderId: String) {}

    fun readFile(fileId: String) {}

    fun readUnit(unit: String) {}

    companion object {
        val None: DocDistBadges = object : DocDistBadges {}

        const val TOOL = "document_distribution_label"
        const val UNIT_FOLDER = "document_distribution_folder_label"
        const val UNIT_DOCUMENT = "document_distribution_document_label"
        const val UNIT_DISTRIBUTION = "document_distribution_distribution_label"
        const val UNIT_PRESET = "document_distribution_preset_label"
        const val UNIT_PUBLICATION = "document_distribution_publication_label"
        const val UNIT_TEMPLATE = "document_distribution_template_label"

        /**
         * Every `tool` spelling the service has stamped on a doc-dist row —
         * iOS `DDFileTransfer.toolAliases`, which its entity read and its
         * delete sweep both match (`LocalNoticifationRealm.swift:2826-2830`).
         * The Film Tools tile maps all three onto the tool, so a read that
         * matched one spelling could leave a tile number nothing clears.
         */
        val TOOL_ALIASES: Set<String> = setOf(TOOL, "document_distribution_tool", "document_distribution")

        /**
         * The units whose rows roll up into folder bubbles — iOS
         * `DDBadgeReader.swift:118-121`. The four shared-event units
         * (distribution, preset, publication, template) badge the toolbar
         * only: a preset rename filed with a folder id in `level_1` once
         * badged that folder too, and the library read 11 where the phone
         * read 9.
         */
        val FOLDER_CASCADE_UNITS: Set<String> = setOf(UNIT_FOLDER, UNIT_DOCUMENT)

        /** `level_1` of a row at the top of the library: no folder to badge. */
        const val ROOT = "root"
    }
}

/**
 * A ledger row as the doc-dist rules read it: the ids it can name a folder
 * or a document by. The host fills it from the wire row; nothing here knows
 * the ledger.
 */
data class DocDistRowRef(
    val unit: String = "",
    val referenceId: String = "",
    val level1: String = "",
    val level2: String = "",
    val level3: String = "",
    /**
     * `levels[]` as sent — depth four and below. Entries are raw ids or
     * `level_N:<id>` strings (iOS `extraLevels`), or one id per object,
     * which the host flattens to its value.
     */
    val deeper: List<String> = emptyList(),
    /** `reference_data.folder_id` / `parent_id` / `document_id` — what the delete sweep also keys on. */
    val folderId: String = "",
    val parentId: String = "",
    val documentId: String = "",
    /** `reference_data.ignore` — a row that never counted. */
    val ignored: Boolean = false,
) {
    /** The ids of [deeper], each as written and as its `level_N:` tail. */
    private val deeperIds: Set<String>
        get() = deeper.flatMap { listOf(it.trim(), it.substringAfterLast(':').trim()) }
            .filter { it.isNotEmpty() }
            .toSet()

    /**
     * Every folder id this row badges — iOS `DDBadgeReader.aggregate`
     * (`DDBadgeReader.swift:98-145`): the folder path (`level_1..3`, then the
     * `levels[]` tail), plus the folder itself for a folder event. Only the
     * [DocDistBadges.FOLDER_CASCADE_UNITS] have one; the root marker and blank
     * ids badge nothing.
     */
    fun ancestry(): Set<String> {
        if (unit !in DocDistBadges.FOLDER_CASCADE_UNITS) return emptySet()
        val ids = (listOf(level1, level2, level3) + deeper.map { it.substringAfterLast(':') })
            .map { it.trim() }
            .toMutableSet()
        if (unit == DocDistBadges.UNIT_FOLDER) ids += referenceId.trim()
        return ids.filterTo(linkedSetOf()) { it.isNotEmpty() && it != DocDistBadges.ROOT }
    }

    /**
     * Whether entering [folderId] reads this row — iOS `markDDEntityAsRead`
     * (`LocalNoticifationRealm.swift:2767-2776`, from
     * `DDLibraryViewModel.enterFolder`): the folder's own events and every
     * row filed anywhere beneath it, by `reference_id` or `level_1..3`.
     *
     * The `levels[]` tail is matched too, which iOS misses: a document five
     * folders down names its upper folders in `level_1..3` and its nearer
     * ones only there, so iOS left it badging a folder already entered.
     */
    fun isUnderFolder(folderId: String): Boolean {
        val id = folderId.trim()
        if (id.isEmpty() || id == DocDistBadges.ROOT) return false
        return referenceId == id || level1 == id || level2 == id || level3 == id || id in deeperIds
    }

    /**
     * Whether deleting [ids] retires this row — iOS
     * `removeDocumentDistributionBadges` (`LocalNoticifationRealm.swift:2820-2859`):
     * the entity acted on, its folder path at every depth, and the
     * `reference_data` ids, so deleting a top folder clears its whole
     * subtree. An `ignore` row never counted and is left alone.
     */
    fun isRetiredBy(ids: Set<String>): Boolean {
        if (ignored || ids.isEmpty()) return false
        val named = setOf(referenceId, level1, level2, level3, folderId, parentId, documentId) + deeperIds
        return named.any { it.isNotBlank() && it in ids }
    }
}

/** The leaves cut the way the screen asks: a folder's bubble, a file's dot, a section's pill. */
data class DocDistUnread(val leaves: List<DocDistBadgeLeaf> = emptyList()) {

    /**
     * Every folder or document row filed anywhere under the folder, the
     * folder's own events included. The shared-event units never reach a
     * bubble, whatever the host put in their ancestry (see
     * [DocDistBadges.FOLDER_CASCADE_UNITS]).
     */
    fun folder(folderId: String): Int =
        leaves.filter { it.unit in DocDistBadges.FOLDER_CASCADE_UNITS && folderId in it.ancestry }.sumOf { it.unread }

    fun file(fileId: String): Int =
        leaves.filter { it.unit == DocDistBadges.UNIT_DOCUMENT && it.referenceId == fileId }.sumOf { it.unread }

    fun unit(vararg units: String): Int = leaves.filter { it.unit in units }.sumOf { it.unread }

    companion object {
        val None = DocDistUnread()
    }
}
