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
 * counts once on each folder bubble above it.
 */
data class DocDistBadgeLeaf(
    val unit: String,
    val referenceId: String,
    val ancestry: Set<String>,
    val unread: Int,
)

/**
 * The tool's unread rows, and the reads the web's library makes
 * (`Library.jsx:157-290`): a folder entered reads the folder's own events;
 * a file previewed reads that file's; a side section opened reads its
 * unit whole.
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
    }
}

/** The leaves cut the way the screen asks: a folder's bubble, a file's dot, a section's pill. */
data class DocDistUnread(val leaves: List<DocDistBadgeLeaf> = emptyList()) {

    /** Every row filed anywhere under the folder, the folder's own events included. */
    fun folder(folderId: String): Int = leaves.filter { folderId in it.ancestry }.sumOf { it.unread }

    fun file(fileId: String): Int =
        leaves.filter { it.unit == DocDistBadges.UNIT_DOCUMENT && it.referenceId == fileId }.sumOf { it.unread }

    fun unit(vararg units: String): Int = leaves.filter { it.unit in units }.sumOf { it.unread }

    companion object {
        val None = DocDistUnread()
    }
}
