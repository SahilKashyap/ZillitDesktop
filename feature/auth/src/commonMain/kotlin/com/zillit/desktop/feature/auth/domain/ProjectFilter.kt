package com.zillit.desktop.feature.auth.domain

/**
 * The top-level filter above the production list.
 *
 * The same four the web offers (`Projects.jsx`). A closed set rather than a
 * free-form predicate so the chip row, the filter and the tests cannot drift
 * apart.
 */
enum class ProjectFilter(val label: String) {
    All("All projects"),
    Entertainment("Entertainment"),
    Personal("Personal"),
    Favourites("Favourites"),
    ;

    fun matches(project: Project): Boolean = when (this) {
        All -> true
        Entertainment -> !project.isPersonal
        Personal -> project.isPersonal
        Favourites -> project.isFavourite
    }
}

/**
 * Applies the search box and the filter, then orders the result.
 *
 * A pure function, deliberately outside the composable and outside the
 * ViewModel: this is the screen's only real logic, and it is worth testing
 * without a UI or a coroutine harness. The web does the same work inline inside
 * a 809-line component, where none of it can be exercised.
 *
 * Search matches **name, code and parent name** — a coordinator who knows only
 * the project code should not have to remember the title.
 */
fun List<Project>.filterProjects(
    query: String,
    filter: ProjectFilter,
    /** Each production's badge — the ledger's number, not the listing's stale `unread` field. */
    unread: (Project) -> Int = { it.unreadCount },
): List<Project> {
    val trimmed = query.trim()

    return asSequence()
        .filter(filter::matches)
        .filter { it.matchesQuery(trimmed) }
        .sortedWith(projectOrder(unread))
        .toList()
}

private fun Project.matchesQuery(query: String): Boolean {
    if (query.isEmpty()) return true
    return name.contains(query, ignoreCase = true) ||
        code.contains(query, ignoreCase = true) ||
        parentName?.contains(query, ignoreCase = true) == true
}

/**
 * Most unread first, then pending last, then favourites, then alphabetical.
 *
 * Android's order, as the phone actually shows it: `ProjectListActivity`
 * runs four stable sorts (name, favourite, unread, pending — 271-274) and
 * its adapter then re-sorts by unread (`ProjectListAdapter.kt:189`), so
 * unread ends up the primary key and the activity's order breaks ties.
 * Pending productions sink because they cannot be opened — leaving them
 * interleaved means the one production a user can actually enter may sit
 * below three they cannot.
 */
private fun projectOrder(unread: (Project) -> Int): Comparator<Project> =
    compareByDescending<Project> { unread(it) }
        .thenBy { it.isPending }
        .thenByDescending { it.isFavourite }
        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }

/**
 * The ranges of [text] that match [query], for highlighting.
 *
 * Returned as ranges rather than pre-styled text so the design system decides
 * how a match looks — the web hardcodes a `<mark>` element and its colour into
 * the list component.
 *
 * Overlapping matches cannot occur: the scan always advances past the match it
 * just found.
 */
fun highlightRanges(text: String, query: String): List<IntRange> {
    val needle = query.trim()
    if (needle.isEmpty() || text.isEmpty()) return emptyList()

    return buildList {
        var index = text.indexOf(needle, ignoreCase = true)
        while (index >= 0) {
            add(index until index + needle.length)
            index = text.indexOf(needle, startIndex = index + needle.length, ignoreCase = true)
        }
    }
}
