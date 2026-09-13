package com.zillit.desktop.feature.accounthub.domain

/**
 * Where a code sits in the nominal taxonomy.
 *
 * The wire keys stay `header`/`section`/`category`/`sub_category`; the labels
 * are what accountants actually call them, and they do not match. A `section`
 * is presented as "Headers", a `category` as "Nominal", a `sub_category` as
 * "Code" — renaming the enum to match the labels would break every payload.
 *
 * The top level's label is deliberately blank: its tag is hidden in the tree.
 * [tagLabel] supplies "Group" for the flat surfaces that need something to
 * print in a Type column.
 */
enum class CoaLineType(val wire: String, val label: String) {
    Header("header", ""),
    Section("section", "Headers"),
    Category("category", "Nominal"),
    SubCategory("sub_category", "Code"),
    ;

    /**
     * How far down the tree this sits.
     *
     * Declaration order *is* the hierarchy, so the ordinal is the depth rather
     * than a number repeated beside each entry that could drift out of step
     * with it. Reordering these entries reorders the chart — which is the
     * point, and is why they are not alphabetical.
     */
    val depth: Int get() = ordinal

    val tagLabel: String get() = label.ifBlank { "Group" }

    /** The level immediately above, or null at the top. */
    val parentType: CoaLineType?
        get() = entries.firstOrNull { it.depth == depth - 1 }

    /** The level immediately below, or null for the leaf. */
    val childType: CoaLineType?
        get() = entries.firstOrNull { it.depth == depth + 1 }

    /**
     * The "+" action's tooltip, in the chart's own vocabulary — the web's
     * `ADD_CHILD_LABEL`. Null for the leaf, which takes no children.
     */
    val addChildLabel: String?
        get() = when (this) {
            Header -> "Add Header for Group"
            Section -> "Add Nominal for Headers"
            Category -> "Add Sub-code for nominals"
            SubCategory -> null
        }

    companion object {
        fun from(wire: String?): CoaLineType? =
            entries.firstOrNull { it.wire == wire?.lowercase() }
    }
}

/** The five double-entry classes. */
enum class CoaCostType(val wire: String, val label: String) {
    Asset("asset", "Asset"),
    Liability("liability", "Liability"),
    Capital("capital", "Capital"),
    Income("income", "Income"),
    Expense("expense", "Expense"),
    ;

    /** Everything that is not an expense — the balance-sheet view. */
    val isBalanceSheet: Boolean get() = this != Expense

    companion object {
        /**
         * Defaults to [Expense].
         *
         * That is the column default on both the budget import and a manual
         * add, so an untagged row belongs to the cost side — the web's
         * `isExpenseRow` reads a missing class the same way.
         */
        fun from(wire: String?): CoaCostType =
            entries.firstOrNull { it.wire == wire?.lowercase() } ?: Expense
    }
}

/**
 * One row of the chart of accounts.
 *
 * ## The hierarchy is a breadcrumb, not a pointer
 *
 * There is no `parent_id`. Every row carries the id of *each* ancestor —
 * [headId], [sectionId], [categoryId] — including a self-reference at its own
 * level (a section's [sectionId] is its own id). Building the tree is therefore
 * one group-by pass rather than a recursive walk, and asking "what is above
 * this row" is O(1) at any depth.
 */
data class CoaAccount(
    val id: String,
    val code: String = "",
    val name: String = "",
    val lineType: CoaLineType = CoaLineType.Category,
    val costType: CoaCostType = CoaCostType.Expense,
    val headId: String? = null,
    val sectionId: String? = null,
    val categoryId: String? = null,
    val subCategoryId: String? = null,
    val isActive: Boolean = true,
    /**
     * Whether transactions may code against this row.
     *
     * Opt-out like [isActive]: a row written before the field existed reads as
     * postable, and defaulting it the other way would silently make every
     * legacy code unusable on every line-item picker in the platform.
     */
    val isPosting: Boolean = true,
    /**
     * Where the row came from — `budget` for a code the import created.
     *
     * A budget row's cost type and structure are locked: the budget's own
     * taxonomy is what the cost report reads, and the server refuses a change
     * of class on such a row.
     */
    val source: String = "",
) {
    val isFromBudget: Boolean get() = source.equals(BUDGET_SOURCE, ignoreCase = true)

    /** The immediate parent's id, or null for a top-level row. */
    val parentId: String?
        get() = when (lineType) {
            CoaLineType.Header -> null
            CoaLineType.Section -> headId
            CoaLineType.Category -> sectionId
            CoaLineType.SubCategory -> categoryId
        }

    /** The ancestors' ids, top down, without the self-reference at this row's own level. */
    val ancestorIds: List<String>
        get() = listOfNotNull(headId, sectionId, categoryId).filter { it != id }

    val display: String get() = listOf(code, name).filter { it.isNotBlank() }.joinToString(" · ")

    /**
     * The label every surface prints — the web's `coaLabel`.
     *
     * The name is optional, so a nameless code reads as the bare code rather
     * than a dangling "5000 — ".
     */
    fun label(separator: String = "—"): String = if (name.isNotEmpty()) "$code $separator $name" else code

    companion object {
        const val BUDGET_SOURCE = "budget"
    }
}

/** What an edit answered — the saved row and how many descendants its class change reached. */
data class CoaUpdate(val account: CoaAccount, val cascadedDescendants: Int = 0)

/**
 * The stat strip over the chart — the web's five cards.
 *
 * Counted over the tab's rows *before* Show inactive filters them, as the web
 * counts: the strip describes the chart, not the current filter.
 */
data class CoaStats(
    val total: Int = 0,
    val headers: Int = 0,
    val nominals: Int = 0,
    val codes: Int = 0,
    val active: Int = 0,
) {
    companion object {
        fun of(rows: List<CoaAccount>) = CoaStats(
            total = rows.size,
            headers = rows.count { it.lineType == CoaLineType.Header },
            // The web's own mapping: "Nominals" counts sections and "Codes"
            // counts categories, whatever the level labels say.
            nominals = rows.count { it.lineType == CoaLineType.Section },
            codes = rows.count { it.lineType == CoaLineType.Category },
            active = rows.count { it.isActive },
        )
    }
}

/** A row with its children resolved. */
data class CoaNode(val account: CoaAccount, val children: List<CoaNode> = emptyList()) {
    val id: String get() = account.id

    /** This node and everything beneath it, depth-first. */
    fun flatten(): List<CoaNode> = listOf(this) + children.flatMap { it.flatten() }
}

/**
 * Ordering, tree-building and parent validation for the chart.
 *
 * Kept out of the UI because two consumers need identical answers: the chart
 * screen's tree and its table. Two implementations of "which code comes first"
 * is how the web ended up with a tree and a table that disagreed.
 */
object ChartOfAccounts {

    /**
     * Ascending code order — the web's `compareCoaCodeNumeric`.
     *
     * `code` is free text, so it is read as a **number** first: "9" before
     * "99" before "1000", never string order. A code carrying a letter is not
     * sortable as a number and goes to the end ("125L", "ADMIN"); so does a
     * blank one, which parsed as zero would sort to the very top.
     *
     * Two refinements the web makes and this follows. A hyphenated budget code
     * ("1100-10") orders by its leading number, so it files beside "1100"
     * instead of sinking to the tail. And ties — equal values, or two
     * unsortable codes — fall back to a natural text order, where "1100-2"
     * comes before "1100-10".
     *
     * Dotted sub-codes read as decimals, so "1100.10" sorts *before* "1100.2".
     * That is deliberate and is the opposite of segment-wise ordering.
     */
    fun compareCodes(a: String?, b: String?): Int {
        val left = a.orEmpty().trim()
        val right = b.orEmpty().trim()
        val leftNumber = codeNumber(left)
        val rightNumber = codeNumber(right)
        return when {
            leftNumber != null && rightNumber != null ->
                leftNumber.compareTo(rightNumber).takeIf { it != 0 } ?: naturalCompare(left, right)
            // Exactly one is a number: it wins, pushing the unsortable one down.
            leftNumber != null -> -1
            rightNumber != null -> 1
            else -> naturalCompare(left, right)
        }
    }

    /**
     * A code's numeric value, or null when it is not a number.
     *
     * A whole-string parse, never a prefix one: "125L" is not 125. The grammar
     * is a plain decimal on purpose — the JVM's own parser also accepts "1f"
     * and "1d", which would file two text codes among the numbers.
     */
    private fun codeNumber(code: String): Double? {
        if (code.isEmpty()) return null
        if (DECIMAL.matches(code)) return code.toDoubleOrNull()?.takeIf { it.isFinite() }
        return HYPHENATED.matchEntire(code)?.groupValues?.get(1)?.toDoubleOrNull()
    }

    /** Digit runs compare by value, everything else without regard to case. */
    internal fun naturalCompare(a: String, b: String): Int {
        val left = CHUNK.findAll(a).map { it.value }.toList()
        val right = CHUNK.findAll(b).map { it.value }.toList()
        for (index in 0 until minOf(left.size, right.size)) {
            val order = compareChunks(left[index], right[index])
            if (order != 0) return order
        }
        return left.size.compareTo(right.size).takeIf { it != 0 } ?: a.compareTo(b)
    }

    private fun compareChunks(a: String, b: String): Int {
        val bothDigits = a.first().isDigit() && b.first().isDigit()
        if (!bothDigits) return a.lowercase().compareTo(b.lowercase())
        val left = a.trimStart('0')
        val right = b.trimStart('0')
        return left.length.compareTo(right.length).takeIf { it != 0 } ?: left.compareTo(right)
    }

    /**
     * Flat rows to a forest, children sorted by code.
     *
     * Rows whose parent is absent from [rows] — normally because the parent is
     * inactive and Show inactive is off — are **orphans**. They are returned
     * separately rather than dropped: a code the server still accepts postings
     * against, missing from the screen that is supposed to list every code, is
     * worse than one shown without its ancestry.
     */
    fun tree(rows: List<CoaAccount>): CoaForest {
        val childrenOf = rows.groupBy { it.parentId }
        val known = rows.map { it.id }.toSet()
        val byCode = Comparator<CoaAccount> { x, y -> compareCodes(x.code, y.code) }

        fun build(account: CoaAccount, seen: Set<String>): CoaNode = CoaNode(
            account = account,
            children = childrenOf[account.id].orEmpty()
                // A row naming itself, or a loop in bad data, must not recurse forever.
                .filter { it.id !in seen }
                .sortedWith(byCode)
                .map { build(it, seen + it.id) },
        )

        val roots = rows.filter { it.parentId == null }.sortedWith(byCode).map { build(it, setOf(it.id)) }
        val orphans = rows.filter { it.parentId != null && it.parentId !in known }
            .sortedWith(byCode)
            .map { build(it, setOf(it.id)) }
        return CoaForest(roots = roots, orphans = orphans)
    }

    /**
     * The tree cut down to a search — the web's `TreeView` filter.
     *
     * A node stays when its code or name contains [term] or when something
     * beneath it does, so every hit keeps the path above it. Blank keeps all.
     */
    fun filter(nodes: List<CoaNode>, term: String): List<CoaNode> {
        val needle = term.trim()
        if (needle.isEmpty()) return nodes

        fun keep(node: CoaNode): CoaNode? {
            val children = node.children.mapNotNull(::keep)
            val hit = node.account.code.contains(needle, ignoreCase = true) ||
                node.account.name.contains(needle, ignoreCase = true)
            return if (hit || children.isNotEmpty()) node.copy(children = children) else null
        }
        return nodes.mapNotNull(::keep)
    }

    /**
     * Which rows may be the parent of a row of [lineType].
     *
     * Active rows exactly one level above, never the row itself. Empty for a
     * header, which has no parent — a legitimate answer, not a failure.
     */
    fun parentOptions(rows: List<CoaAccount>, lineType: CoaLineType, excludingId: String? = null): List<CoaAccount> {
        val required = lineType.parentType ?: return emptyList()
        return rows.filter { it.lineType == required && it.isActive && it.id != excludingId }
            .sortedWith { a, b -> compareCodes(a.code, b.code) }
    }

    /**
     * Why this parent will not do, or null when it will — the web's
     * `validateParent`.
     *
     * The parent is **optional** below the top: a row saved without one sits at
     * the root of the tree. What is refused is a parent at the wrong level, the
     * row itself, or an id the chart does not hold.
     */
    fun parentProblem(
        lineType: CoaLineType,
        parentId: String?,
        rows: List<CoaAccount>,
        selfId: String? = null,
    ): String? {
        val required = lineType.parentType
        val wanted = parentId?.takeIf { it.isNotBlank() }
        val parent = wanted?.let { id -> rows.firstOrNull { it.id == id } }
        return when {
            required == null -> if (wanted != null) "Header rows cannot have a parent." else null
            wanted == null -> null
            wanted == selfId -> "A row cannot be its own parent."
            parent == null -> "Parent not found in this project."
            parent.lineType != required ->
                "Parent must be a ${required.tagLabel}; \"${parent.code}\" is a ${parent.lineType.tagLabel}."
            else -> null
        }
    }

    /**
     * Whether [code] is already taken, by an active row or a retired one.
     *
     * The code is the natural key and the server's uniqueness spans inactive
     * rows too, so a retired code cannot be created again — it has to be
     * reactivated.
     */
    fun codeTaken(rows: List<CoaAccount>, code: String, excludingId: String? = null): Boolean =
        rows.any { it.id != excludingId && it.code.equals(code.trim(), ignoreCase = true) }

    /** Rows matching [term] in code or name. Blank matches nothing, not everything. */
    fun search(rows: List<CoaAccount>, term: String): List<CoaAccount> {
        val needle = term.trim()
        if (needle.isEmpty()) return emptyList()
        return rows.filter {
            it.code.contains(needle, ignoreCase = true) || it.name.contains(needle, ignoreCase = true)
        }
    }

    /**
     * The row's ancestry as the table's second line prints it: each
     * ancestor's name, or its code when it has none, top down.
     *
     * Read from the breadcrumb columns rather than by chasing parents, so a
     * missing middle level does not hide the levels above it.
     */
    fun breadcrumb(byId: Map<String, CoaAccount>, account: CoaAccount): List<String> =
        account.ancestorIds.mapNotNull { byId[it] }.map { it.name.ifEmpty { it.code } }

    /** The row's ancestry, top down, following each level's parent. */
    fun path(rows: List<CoaAccount>, account: CoaAccount): List<CoaAccount> {
        val byId = rows.associateBy { it.id }
        val out = mutableListOf<CoaAccount>()
        var cursor = account.parentId?.let { byId[it] }
        var guard = 0
        while (cursor != null && guard < CoaLineType.entries.size) {
            out.add(0, cursor)
            cursor = cursor.parentId?.let { byId[it] }
            guard++
        }
        return out
    }

    /** How many rows sit directly beneath [id] — the re-type warning's count. */
    fun childCount(rows: List<CoaAccount>, id: String): Int = rows.count { it.parentId == id }

    /**
     * The parent a row keeps when its line type changes — the web's re-default.
     *
     * A header has none. Otherwise the current parent survives if it still sits
     * one level above; failing that, the row the form was opened from, if it
     * does; failing that, none — so a type change never strands the form on a
     * parent it cannot save.
     */
    fun reparent(
        lineType: CoaLineType,
        currentParentId: String?,
        preselected: CoaAccount?,
        rows: List<CoaAccount>,
    ): String? {
        val required = lineType.parentType ?: return null
        val current = rows.firstOrNull { it.id == currentParentId }
        return when {
            current?.lineType == required -> current.id
            preselected?.lineType == required -> preselected.id
            else -> null
        }
    }

    /**
     * The postable leaves a line item may code against — what every
     * code typeahead (bank nominal, tax nominal, pay rule nominal) offers.
     */
    fun leaves(rows: List<CoaAccount>): List<CoaAccount> = rows
        .filter { it.isActive && it.isPosting }
        .filter { it.lineType == CoaLineType.Category || it.lineType == CoaLineType.SubCategory }
        .sortedWith { a, b -> compareCodes(a.code, b.code) }

    /** Leaves whose code or name starts with or contains [term], code matches first. */
    fun suggest(rows: List<CoaAccount>, term: String, limit: Int = SUGGEST_LIMIT): List<CoaAccount> {
        val needle = term.trim()
        val pool = leaves(rows)
        if (needle.isEmpty()) return pool.take(limit)
        val starts = pool.filter { it.code.startsWith(needle, ignoreCase = true) }
        val contains = pool.filter {
            it !in starts && (it.code.contains(needle, true) || it.name.contains(needle, true))
        }
        return (starts + contains).take(limit)
    }

    private const val SUGGEST_LIMIT = 8
    private val DECIMAL = Regex("""[+-]?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?""")
    private val HYPHENATED = Regex("""(\d+)[\d-]*""")
    private val CHUNK = Regex("""\d+|\D+""")
}

/** How the chart is drawn — the web's Tree | Table switch. */
enum class ChartMode(val label: String) { Tree("Tree"), Table("Table") }

/**
 * The table's sortable columns. [key] is the word the footer prints
 * ("Sorted by cost ↑"), as the web prints its sort key.
 */
enum class ChartSortKey(val label: String, val key: String) {
    Code("Code", "code"),
    Type("Type", "type"),
    Name("Name", "name"),
    CostType("Cost type", "cost"),
}

/** A column and a direction. */
data class ChartSort(val key: ChartSortKey = ChartSortKey.Code, val ascending: Boolean = true) {
    /** Clicking the active column flips it; clicking another sorts it ascending. */
    fun toggled(next: ChartSortKey): ChartSort =
        if (next == key) copy(ascending = !ascending) else ChartSort(next, ascending = true)

    fun comparator(): Comparator<CoaAccount> {
        val base: Comparator<CoaAccount> = when (key) {
            ChartSortKey.Code -> Comparator { a, b -> ChartOfAccounts.compareCodes(a.code, b.code) }
            ChartSortKey.Type -> compareBy { it.lineType.depth }
            ChartSortKey.Name -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            ChartSortKey.CostType -> compareBy { it.costType.wire }
        }
        return if (ascending) base else base.reversed()
    }
}

/**
 * One row of the bulk-add grid ("New COA Entry") — the web's `blankRow`.
 *
 * Only what the grid edits. The autosave's bookkeeping — the code and level the
 * server holds, what was last sent — lives with the saver, because none of it
 * is drawn.
 */
data class CoaBulkRow(
    val localId: String,
    val lineType: CoaLineType = CoaLineType.Category,
    val code: String = "",
    val costType: CoaCostType = CoaCostType.Expense,
    val name: String = "",
    val isActive: Boolean = true,
    val isPosting: Boolean = true,
    /** The server's id once the row has been created. */
    val serverId: String? = null,
) {
    val normalisedCode: String get() = code.trim().uppercase()
}

/** Where a grid row's autosave stands. A row never saved has no status at all. */
enum class CoaBulkStatus { Saving, Saved, Error }

/** Rules for the bulk grid, shared by the screen and its saver — the web's `coaBulkRows`. */
object CoaBulk {
    const val SAVE_DEBOUNCE_MS = 2_000L
    const val BATCH_ROWS = 5

    /** A code alone makes a row saveable; the display name is optional. */
    fun isReady(row: CoaBulkRow): Boolean = row.code.isNotBlank()

    /** Codes that cannot be created: the chart's, active or retired, and the ones this session made. */
    fun takenCodes(chart: List<CoaAccount>, createdCodes: Set<String>): Set<String> =
        chart.map { it.code.trim().uppercase() }.toSet() + createdCodes

    /**
     * The rows whose code collides — the web's `computeDupIds`.
     *
     * A code already taken flags only a row not yet saved: once a row is
     * created its own code joins [taken], and without that guard every saved
     * row would flag itself. Two grid rows sharing a code both flag.
     */
    fun duplicateIds(rows: List<CoaBulkRow>, taken: Set<String>): Set<String> {
        val firstWith = mutableMapOf<String, String>()
        val out = mutableSetOf<String>()
        rows.forEach { row ->
            val code = row.normalisedCode
            if (code.isEmpty()) return@forEach
            if (code in taken && row.serverId == null) out += row.localId
            val earlier = firstWith[code]
            if (earlier != null) {
                out += row.localId
                out += earlier
            } else {
                firstWith[code] = row.localId
            }
        }
        return out
    }

    /**
     * Why a row is flagged, for its tooltip — the web's `dupMessage`.
     *
     * The distinctions matter because the remedies differ: a live code is
     * simply taken, a retired one has to be reactivated from the chart rather
     * than created again, and a code used and removed in this session is
     * reserved just the same.
     */
    fun duplicateMessage(
        row: CoaBulkRow,
        rows: List<CoaBulkRow>,
        chart: List<CoaAccount>,
        createdCodes: Set<String>,
    ): String? {
        val code = row.normalisedCode
        if (code.isEmpty()) return null
        if (rows.any { it.localId != row.localId && it.normalisedCode == code }) {
            return "Code \"$code\" is used by another row in this list"
        }
        val match = chart.firstOrNull { it.code.trim().equals(code, ignoreCase = true) }
        return when {
            match != null && !match.isActive ->
                "Code \"$code\" was previously used and retired — reactivate it from the Chart of Accounts " +
                    "list instead of creating it again"
            match != null -> "Code \"$code\" already exists"
            code in createdCodes -> "Code \"$code\" was already used earlier in this session and can't be reused here"
            else -> null
        }
    }

    /** What a create sends: the whole row under the page's one parent. */
    fun newAccount(row: CoaBulkRow, parentId: String?) = NewAccount(
        code = row.normalisedCode,
        name = row.name.trim(),
        lineType = row.lineType,
        costType = row.costType,
        parentId = parentId,
        isPosting = row.isPosting,
        isActive = row.isActive,
    )

    /**
     * What an update sends — the narrow form, plus the level and parent when
     * the row was re-typed. The code never rides along: it has no in-place
     * rename, and a changed code is a create-then-retire instead.
     */
    fun patch(row: CoaBulkRow, retype: Boolean, parentId: String?) = AccountPatch(
        name = row.name.trim(),
        costType = row.costType,
        isActive = row.isActive,
        isPosting = row.isPosting,
        structureChanged = retype,
        lineType = row.lineType.takeIf { retype },
        parentId = parentId,
    )

    /** The fields an update carries, for telling a real change from a repeat. */
    fun snapshot(row: CoaBulkRow) = CoaBulkSnapshot(row.name.trim(), row.costType, row.isActive, row.isPosting)
}

/** See [CoaBulk.snapshot]. */
data class CoaBulkSnapshot(
    val name: String,
    val costType: CoaCostType,
    val isActive: Boolean,
    val isPosting: Boolean,
)

/**
 * The chart as a forest, plus whatever could not be placed in it.
 *
 * Orphans are surfaced rather than swallowed — see [ChartOfAccounts.tree].
 */
data class CoaForest(
    val roots: List<CoaNode> = emptyList(),
    val orphans: List<CoaNode> = emptyList(),
) {
    val isEmpty: Boolean get() = roots.isEmpty() && orphans.isEmpty()

    /** Every node in display order, roots first then the unplaceable ones. */
    fun flatten(): List<CoaNode> =
        roots.flatMap { it.flatten() } + orphans.flatMap { it.flatten() }
}

/**
 * An analytical dimension parallel to the nominal chart — locations, episodes.
 *
 * [prefix] rides every code (`LOC-EUR-LON`); [color] drives the chip on every
 * line-item picker. Both are the web's `TrackingCodesTab` fields.
 */
data class TrackingSet(
    val id: String,
    val name: String = "",
    val code: String = "",
    val isActive: Boolean = true,
    val nodes: List<TrackingNode> = emptyList(),
    val prefix: String = "",
    /** A hex colour, `#FB923C`; blank falls back to the palette by position. */
    val color: String = "",
) {
    /** The prefix, or the code the older rows carried instead. */
    val shownPrefix: String get() = prefix.ifBlank { code }
}

/**
 * One code within a [TrackingSet].
 *
 * [parentId] exists in the schema for a deeper tree later; the layers tab is
 * flat, as the web's is, and never writes one.
 */
data class TrackingNode(
    val id: String,
    val setId: String = "",
    val code: String = "",
    /** The web's `label`. */
    val name: String = "",
    val parentId: String? = null,
    val isActive: Boolean = true,
    /** Optional notes, shown on hover in the picker. */
    val description: String = "",
    val sortOrder: Int = 0,
)

/** The palette a new layer is coloured from, by position — the web's `DEFAULT_COLORS`. */
object TrackingSets {
    val DEFAULT_COLORS: List<String> = listOf(
        "#FB923C", // orange — Locations
        "#3B82F6", // blue   — Episodes
        "#10B981", // teal   — Funding
        "#A855F7", // purple — Sets / Stages
        "#F43F5E", // rose   — Departments-extra
        "#EAB308", // amber  — Phase
        "#22D3EE", // cyan   — Region
        "#84CC16", // lime   — Activity
    )

    const val PREFIX_MIN = 2
    const val PREFIX_MAX = 10

    fun colorFor(index: Int): String = DEFAULT_COLORS[index % DEFAULT_COLORS.size]

    /** Upper-cased, letters and digits, at most ten — as the web normalises it as it is typed. */
    fun normalisePrefix(raw: String): String =
        raw.uppercase().filter { it.isLetterOrDigit() }.take(PREFIX_MAX)

    /**
     * A layer's codes in reading order — the web's `sortNodes`: by the stored
     * order, then by code. Flat, every code, active or not.
     */
    fun ordered(nodes: List<TrackingNode>): List<TrackingNode> =
        nodes.sortedWith(compareBy<TrackingNode> { it.sortOrder }.thenBy { it.code })

    /** Why a set cannot be saved, or null. A blank prefix is allowed: the server derives one. */
    fun setProblem(name: String, prefix: String): String? = when {
        name.isBlank() -> "Give the layer a name."
        prefix.isNotBlank() && prefix.length < PREFIX_MIN -> "A prefix is 2–10 letters or digits."
        else -> null
    }

    fun nodeProblem(code: String, label: String): String? = when {
        code.isBlank() -> "Give the code a value."
        label.isBlank() -> "Give the code a label."
        else -> null
    }
}
