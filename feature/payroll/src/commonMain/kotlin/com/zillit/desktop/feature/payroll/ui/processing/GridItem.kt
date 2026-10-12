package com.zillit.desktop.feature.payroll.ui.processing

/**
 * One line of a Processing table that is grouped by department — the web's
 * department header rows (`PayrollGridModule.jsx` 1640-1660, 1790-1860): a
 * line naming the department, how many crew are in it and what they come to,
 * then the crew themselves.
 */
internal sealed interface GridItem<out T> {
    val key: String

    data class Header(
        val department: String,
        val label: String,
        val crew: Int,
        /** The group's money, already formatted; null where the view states none (Week to Date). */
        val money: String?,
    ) : GridItem<Nothing> {
        override val key: String get() = "dept::$department"
    }

    data class Entry<T>(val row: T, override val key: String) : GridItem<T>
}

/**
 * Rows under their department headers, departments in label order and each
 * department's rows in the order they came in — Outstanding arrives heaviest
 * first, and keeps it inside a department. The web breaks a header wherever
 * the department changes down the list, which would repeat a department's
 * header down a list sorted by money; a stable grouping reads the same.
 *
 * [department] may be blank (unassigned crew); they form a group like any other.
 */
internal fun <T> groupedByDepartment(
    rows: List<T>,
    key: (T) -> String,
    department: (T) -> String,
    label: (String) -> String,
    money: ((List<T>) -> String)?,
): List<GridItem<T>> = rows.groupBy(department)
    .toList()
    .sortedBy { (code, _) -> label(code).lowercase() }
    .flatMap { (code, group) ->
        listOf<GridItem<T>>(GridItem.Header(code, label(code), group.size, money?.invoke(group))) +
            group.map { GridItem.Entry(it, key(it)) }
    }

/** The rows with no headers — a single department's view, where the web draws none. */
internal fun <T> ungrouped(rows: List<T>, key: (T) -> String): List<GridItem<T>> =
    rows.map { GridItem.Entry(it, key(it)) }
