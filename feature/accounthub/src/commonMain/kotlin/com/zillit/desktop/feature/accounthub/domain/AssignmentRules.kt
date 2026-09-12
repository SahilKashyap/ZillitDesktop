package com.zillit.desktop.feature.accounthub.domain

/**
 * One auto-assignment rule — who a document lands with when its conditions match.
 *
 * `/api/v2/account-hub/assignment-rules`, module-keyed. The wire shape is the
 * same for purchase orders, invoices, cards and cash; only [module] differs.
 * Conditions OR: any department, vendor or nominal in the lists, or an amount
 * at or over [amountMin], sends the document to [assignTo].
 */
data class AssignmentRule(
    val id: String,
    val module: String = "",
    val departments: List<String> = emptyList(),
    val vendors: List<String> = emptyList(),
    val nominalCodes: List<String> = emptyList(),
    /** Blank while empty; the wire takes a number or null. */
    val amountMin: String = "",
    val assignTo: String = "",
    val isActive: Boolean = true,
    val priority: Int = 0,
    /** Whether the server holds this row; a new one has a local id. */
    val persisted: Boolean = false,
) {
    val amountMinValue: Double? get() = amountMin.trim().replace(",", "").toDoubleOrNull()
}

/**
 * The diff a save applies — the web's `persistRuleDiff`.
 *
 * Removed rows are deleted best-effort, changed rows are patched, new rows
 * are created; an unchanged persisted row is left alone.
 */
data class AssignmentRuleDiff(
    val removed: List<AssignmentRule>,
    val updated: List<AssignmentRule>,
    val created: List<AssignmentRule>,
) {
    val isEmpty: Boolean get() = removed.isEmpty() && updated.isEmpty() && created.isEmpty()
}

object AssignmentRules {
    fun diff(initial: List<AssignmentRule>, current: List<AssignmentRule>): AssignmentRuleDiff {
        val currentIds = current.map { it.id }.toSet()
        val removed = initial.filter { it.persisted && it.id !in currentIds }
        val byId = initial.associateBy { it.id }
        val updated = current.filter { it.persisted && byId[it.id] != null && byId[it.id] != it }
        val created = current.filterNot { it.persisted }
        return AssignmentRuleDiff(removed, updated, created)
    }

    fun newRule(localId: String, module: String, defaultAssignee: String): AssignmentRule =
        AssignmentRule(id = localId, module = module, assignTo = defaultAssignee)
}
