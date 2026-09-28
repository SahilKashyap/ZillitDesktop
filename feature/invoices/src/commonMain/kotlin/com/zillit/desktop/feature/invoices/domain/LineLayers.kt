package com.zillit.desktop.feature.invoices.domain

/**
 * A tracking set — one analytical dimension ("Locations", "Episodes") a line
 * item can be tagged with, from `GET /account-hub/tracking-sets` (the web's
 * `trackingSetsApi.listSets({ includeNodes: true, activeOnly: true })`).
 *
 * A line stores its picks as `tracking_codes: { [set id]: code }`.
 */
data class TrackingSet(
    val id: String,
    val name: String = "",
    val prefix: String = "",
    /** `#RRGGBB`, the chip colour; blank uses the accent. */
    val color: String = "",
    val active: Boolean = true,
    val nodes: List<TrackingNode> = emptyList(),
) {
    /** The codes a line may pick — headers group, they are not picked (`TrackingCodesPicker`). */
    val pickable: List<TrackingNode> get() = nodes.filter { !it.isHeader && it.active }

    /** The node a stored value names — by code, or by id for rows written before codes were stored. */
    fun resolve(value: String?): TrackingNode? =
        value?.takeIf { it.isNotBlank() }?.let { v -> nodes.firstOrNull { it.code == v || it.id == v } }
}

data class TrackingNode(
    val id: String,
    val code: String = "",
    val label: String = "",
    val isHeader: Boolean = false,
    val active: Boolean = true,
) {
    val optionLabel: String get() = if (label.isBlank()) code else "$code · $label"
}

object LineLayers {
    /**
     * What Save keeps — the picker's skip-empty rule: a set left at "none"
     * gets no key, and a set that no longer exists is dropped.
     */
    fun clean(picks: Map<String, String>, sets: List<TrackingSet>): Map<String, String> {
        val active = sets.filter { it.active }.map { it.id }.toSet()
        return picks.filter { (setId, code) -> code.isNotBlank() && setId in active }
    }
}

/**
 * The production's own details, for the sales-invoice document's header —
 * the web's `useProjectInfo` (`project_name`, `company_name`,
 * `company_address`, `company_phone`, `company_email`).
 */
data class InvoiceProjectInfo(
    val projectName: String = "",
    val companyName: String = "",
    val companyAddress: String = "",
    val companyPhone: String = "",
    val companyEmail: String = "",
)
