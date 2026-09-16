package com.zillit.desktop.feature.budget.domain

/**
 * Which budget a document belongs to.
 *
 * The wire spells these `main` and `department` (`CommonBudget.jsx:962`,
 * `AddAndShowDepartmentList.jsx:339`). [Unknown] keeps a document the server
 * invents tomorrow visible rather than dropping it on the floor.
 */
enum class BudgetType(val wire: String) {
    Main("main"),
    Department("department"),
    Unknown(""),
    ;

    companion object {
        fun ofWire(raw: String?): BudgetType =
            entries.firstOrNull { it.wire == raw?.trim()?.lowercase() } ?: Unknown
    }
}

/**
 * Which of the two tiles opened the tool — the web's `status` prop
 * (`FullBudget.jsx:27`, `DepartmentBudget.jsx:18`).
 *
 * The two are separate routes on the web with one shared body; here they are
 * one screen with a mode, and the mode decides the list (one budget's
 * versions, or a department directory first), the tool the rooms are listed
 * under, and which rights row gates posting.
 */
enum class BudgetMode(val type: BudgetType, val tool: String, val title: String) {
    Main(BudgetType.Main, BudgetViewer.MAIN_TOOL, "Budget (Full)"),
    Department(BudgetType.Department, BudgetViewer.DEPARTMENT_TOOL, "Budget (Department)"),
}

/** The file a budget document carries — the same attachment shape chat and the boards use. */
data class BudgetFile(
    val media: String,
    val name: String = "",
    val contentType: String = "",
    val bucket: String = "",
    val region: String = "",
    val sizeBytes: Long = 0,
    val thumbnail: String = "",
) {
    /** A document whose file was deleted keeps its row but has nothing to open. */
    val isPresent: Boolean get() = media.isNotBlank()

    /** The web branches its icon and its download path on the name alone (`CommonBudget.jsx:1957`). */
    val isSpreadsheet: Boolean get() = name.contains("xls", ignoreCase = true)
}

/**
 * One budget document — one version of a budget.
 *
 * A budget is a series: every upload is a new document under the same type
 * (and department), and the newest by [updatedMillis] is *the* budget
 * (`budgetutil.js:getLatestBudgetDoc`). Older versions stay readable but
 * cannot be discussed (`CommonBudget.jsx:2135`).
 */
data class BudgetDocument(
    val id: String,
    val type: BudgetType,
    val departmentId: String = "",
    val departmentName: String = "",
    /** "Budget (Full) -SEP 15, 2026" / "Camera - SEP 15, 2026" — set by the uploader. */
    val title: String = "",
    /** Television productions file a budget per episode; blank elsewhere. */
    val episode: String = "",
    val file: BudgetFile? = null,
    val uploadedById: String = "",
    val uploadedByName: String = "",
    val createdMillis: Long = 0,
    val updatedMillis: Long = 0,
    /** Server-side "you have opened this" stamp; a positive value means visited. */
    val userVisit: Long = 0,
    val deleted: Boolean = false,
)

/** Someone who can see a budget — the audience the members dialog offers. */
data class BudgetMember(
    val userId: String,
    val fullName: String,
    val profileMedia: String = "",
    val departmentName: String = "",
)

/** The two audiences, as `/budget-users/{id}` returns them. */
data class BudgetMembers(
    val main: List<BudgetMember> = emptyList(),
    val department: List<BudgetMember> = emptyList(),
)

/** What the counts endpoint counts. */
enum class BudgetActivity(val wire: String, val title: String) {
    View("view", "View count"),
    Download("download", "Download count"),
}

/** One row of "who has viewed / downloaded" — `{user_id, view_count, download_count}`. */
data class BudgetActivityRow(
    val userId: String,
    val viewCount: Int = 0,
    val downloadCount: Int = 0,
) {
    fun countOf(activity: BudgetActivity): Int = when (activity) {
        BudgetActivity.View -> viewCount
        BudgetActivity.Download -> downloadCount
    }
}

/** A department of the production, as the directory and the picker list them. */
data class BudgetDepartment(
    val id: String,
    val name: String,
)

/**
 * What the upload sends besides the file: the budget's date (its title on
 * screen), the episode on a television production, and — for a department
 * budget — whose department it is.
 */
data class BudgetUpload(
    val type: BudgetType,
    val file: BudgetFile,
    val title: String,
    val departmentId: String = "",
    val episode: String = "",
)

/**
 * One row of the conversation list beside a budget — a person with a private
 * thread about it, or a room made for it. The socket's `budget:recent:list`
 * answers both kinds in one array (`CommonBudget.jsx:getUsersDetails`).
 */
sealed interface BudgetChatEntry {
    /** What the row is keyed by: the user id or the room id. */
    val key: String
    val name: String

    data class Person(
        val userId: String,
        override val name: String,
        val designation: String = "",
        val isAdmin: Boolean = false,
        val deviceId: String = "",
        val hasLeft: Boolean = false,
    ) : BudgetChatEntry {
        override val key: String get() = userId
    }

    data class Group(
        val roomId: String,
        override val name: String,
        val ownedBy: String = "",
        val memberIds: List<String> = emptyList(),
        val pictureMedia: String = "",
        val departmentId: String = "",
        val budgetDocumentId: String = "",
    ) : BudgetChatEntry {
        override val key: String get() = roomId
    }
}
