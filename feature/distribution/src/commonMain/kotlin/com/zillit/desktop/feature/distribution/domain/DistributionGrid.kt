package com.zillit.desktop.feature.distribution.domain

/**
 * The grid's pure rules, lifted from the web's `DistributionAcessgrid.jsx`
 * so a test can hold them still: which rows show, which columns head the
 * matrix, what a row is called, and how the pages cut.
 */

/** One column of the matrix: a Home unit or a tool, headed by its label key. */
data class DistributionColumn(val unitId: String, val unitName: String)

/** The page-size choices the web's table offers (`showSizeChanger`). */
val DISTRIBUTION_PAGE_SIZES: List<Int> = listOf(10, 20, 50, 100)

/** The web's default page — ten people at a time. */
const val DISTRIBUTION_DEFAULT_PAGE_SIZE: Int = 10

/**
 * The rows, in the server's order.
 *
 * Kept: accepted crew (by the directory's status or the row's own) and every
 * external. Then the outsiders-only switch, then the search — on the row's
 * `user_name`, the literal `outsider`, and the directory's full name (the
 * web matches the first two; the third is the name the grid actually prints).
 *
 * NEVER re-sorted: the backend orders by department priority (ZL-16934), and
 * the web's alphabetical sort was removed for that reason.
 */
fun List<DistributionUser>.visibleRows(
    people: Map<String, DistributionPerson>,
    query: String,
    externalOnly: Boolean,
): List<DistributionUser> {
    val needle = query.trim()
    return filter { row ->
        val person = people[row.userId]
        val accepted = person?.status == DistributionUser.ACCEPTED || row.status == DistributionUser.ACCEPTED
        val external = row.isExternal || person?.isExternal == true
        accepted || external
    }.filter { row ->
        !externalOnly || row.isExternal || people[row.userId]?.isExternal == true
    }.filter { row ->
        needle.isBlank() ||
            row.userName.contains(needle, ignoreCase = true) ||
            row.outsider.contains(needle, ignoreCase = true) ||
            people[row.userId]?.fullName.orEmpty().contains(needle, ignoreCase = true)
    }
}

/**
 * The columns for [section]: every unit any row carries, first appearance
 * wins the name, sorted by the translated name (ZL-16805) — [translate] is
 * the dictionary, injected so the rule has no global.
 */
fun List<DistributionUser>.columns(
    section: DistributionSection,
    translate: (String) -> String,
): List<DistributionColumn> {
    val seen = LinkedHashMap<String, String>()
    forEach { user ->
        user.units.filter { it.inSection(section) }.forEach { unit ->
            seen.putIfAbsent(unit.unitId, unit.unitName)
        }
    }
    return seen.entries
        .map { (id, name) -> DistributionColumn(id, name) }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { translate(it.unitName) })
}

/** The name the row prints — the directory's, then the wire's, then the id. */
fun DistributionUser.displayName(person: DistributionPerson?): String =
    person?.fullName?.takeIf { it.isNotBlank() }
        ?: userName.takeIf { it.isNotBlank() }
        ?: userId

/**
 * The line under the name. The web prints the translated designation, and
 * for an outsider the literal "outsider"; this client prints the outsider's
 * email instead, which is what a coordinator uses to tell two contacts apart.
 */
fun DistributionUser.subtitle(
    person: DistributionPerson?,
    translate: (String) -> String,
    noDetails: String,
): String {
    val designation = person?.designation?.takeIf { it.isNotBlank() }?.let(translate)
    if (designation != null) return designation
    val email = person?.email?.takeIf { it.isNotBlank() }
    return email ?: noDetails
}

/** Page [page] (1-based) of [pageSize] rows; a page past the end is the last one. */
data class DistributionPage<T>(
    val rows: List<T>,
    val page: Int,
    val pageCount: Int,
    val firstIndex: Int,
    val lastIndex: Int,
    val total: Int,
) {
    val canGoBack: Boolean get() = page > 1
    val canGoForward: Boolean get() = page < pageCount
}

fun <T> List<T>.paged(page: Int, pageSize: Int): DistributionPage<T> {
    val size = pageSize.coerceAtLeast(1)
    val pageCount = ((this.size + size - 1) / size).coerceAtLeast(1)
    val current = page.coerceIn(1, pageCount)
    val from = (current - 1) * size
    val to = (from + size).coerceAtMost(this.size)
    return DistributionPage(
        rows = if (from < this.size) subList(from, to) else emptyList(),
        page = current,
        pageCount = pageCount,
        firstIndex = if (isEmpty()) 0 else from + 1,
        lastIndex = to,
        total = this.size,
    )
}
