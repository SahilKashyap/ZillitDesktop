package com.zillit.desktop.feature.budget.domain

import kotlin.time.Instant
import kotlinx.datetime.Month
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * The rules the web's `budgetutil.js`, `CommonBudget.jsx` and
 * `AddAndShowDepartmentList.jsx` apply to a budget list, as functions of the
 * list alone — so a test can pin each without a view model or a server.
 */
object BudgetRules {

    /**
     * The versions worth showing, newest first — `getLatestBudgetDoc` with
     * `isGettingSortedList` and `sortBasedOnUpdated` (`budgetutil.js:152-168`).
     * Deleted rows are dropped, not greyed. The web calls this sort on every
     * refresh because "from backend the list is not getting sorted".
     */
    fun sorted(documents: List<BudgetDocument>): List<BudgetDocument> =
        documents.filterNot { it.deleted }.sortedByDescending { it.updatedMillis }

    /** The one budget a discussion may be opened on — the newest version. */
    fun latest(documents: List<BudgetDocument>): BudgetDocument? = sorted(documents).firstOrNull()

    /**
     * One row per department, newest budget first — `DepartmentBudget.jsx:
     * 105-121`: department-type rows, sorted by `created` descending, then
     * reduced to the first of each department.
     */
    fun departmentsWithBudgets(documents: List<BudgetDocument>): List<BudgetDocument> =
        documents
            .filter { it.type == BudgetType.Department }
            .sortedByDescending { it.createdMillis }
            .distinctBy { it.departmentId }

    /**
     * Which departments a person sees in the directory
     * (`AddAndShowDepartmentList.jsx:updateDepartment`, lines 199-244).
     *
     * Someone with no main-budget right at all is not a production-wide
     * reader: unless they are an admin, the directory shrinks to their own
     * department — and their department is *added* when it has no budget yet,
     * so they still have somewhere to upload. Everyone else sees every
     * department that has a budget.
     */
    fun visibleDepartments(
        withBudgets: List<String>,
        viewer: BudgetViewer,
        ownDepartmentId: String,
    ): List<String> {
        val productionWide = viewer.canViewMain || viewer.canPostMain
        if (productionWide) return withBudgets
        if (viewer.isAdmin) {
            return if (ownDepartmentId.isNotBlank() && ownDepartmentId !in withBudgets) {
                withBudgets + ownDepartmentId
            } else {
                withBudgets
            }
        }
        if (ownDepartmentId.isBlank()) return emptyList()
        return listOf(ownDepartmentId)
    }

    /** The department search is hidden along with the other departments (`:213-215`). */
    fun showsDepartmentSearch(viewer: BudgetViewer): Boolean =
        viewer.canViewMain || viewer.canPostMain || viewer.isAdmin

    /**
     * The upload's title. Main: `'Budget (Full) -' + dateStr` — with the
     * web's missing space kept, since the title is stored and every client
     * reads it back verbatim (`CommonBudget.jsx:965`). Department: the
     * department's name, "Label" stripped, then ` - date`
     * (`AddAndShowDepartmentList.jsx:342-345`).
     */
    fun uploadTitle(type: BudgetType, departmentName: String, dateLabel: String): String =
        when (type) {
            BudgetType.Department ->
                "${capitalizeWords(departmentName).replace("Label", "").trim()} - $dateLabel"
            else -> "Budget (Full) -$dateLabel"
        }

    /**
     * `dayjs(date).format('MMM DD, YYYY')` upper-cased — "SEP 05, 2026" —
     * the web's `dateStr` (`ShowDrawerForDepartments.jsx:76`,
     * `DocumentModal.jsx:812`).
     */
    fun dateLabel(epochMillis: Long, zone: TimeZone = TimeZone.currentSystemDefault()): String {
        val date = Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(zone).date
        val month = date.month.shortName()
        val day = date.day.toString().padStart(2, '0')
        return "$month $day, ${date.year}".uppercase()
    }

    /**
     * The upload dialog's date must not be after today
     * (`DocumentModal.jsx:disabledDateAfterToday`).
     */
    fun dateIsAllowed(chosenMillis: Long, nowMillis: Long, zone: TimeZone = TimeZone.currentSystemDefault()): Boolean {
        val chosen = Instant.fromEpochMilliseconds(chosenMillis).toLocalDateTime(zone).date
        val today = Instant.fromEpochMilliseconds(nowMillis).toLocalDateTime(zone).date
        return chosen <= today
    }

    /**
     * The thumbnail the web pins on an upload by extension
     * (`CommonBudget.jsx:987-1002`): a stock spreadsheet or PDF picture in the
     * production's storage. Box productions use numeric file ids.
     */
    fun stockThumbnail(fileName: String, isBox: Boolean): String {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        return when {
            extension in SPREADSHEETS -> if (isBox) BOX_XLS_THUMB else AWS_XLS_THUMB
            extension == "pdf" -> if (isBox) BOX_PDF_THUMB else AWS_PDF_THUMB
            else -> ""
        }
    }

    /** Only PDFs go up (`commonFunctionForBudget.js:selectFile`, `accept=".pdf"`). */
    fun acceptsFile(fileName: String): Boolean = fileName.substringAfterLast('.', "").equals("pdf", ignoreCase = true)

    /**
     * Group name: mandatory, 3–25 characters, and at least one member
     * (`MembersModal.jsx:handleCreateGroup`). Null when the group may be made.
     */
    fun groupComplaint(name: String, memberIds: Collection<String>): String? {
        val trimmed = name.trim()
        return when {
            trimmed.isEmpty() -> "Group name is mandatory."
            memberIds.isEmpty() -> "Please select a member to proceed."
            trimmed.length < GROUP_NAME_MIN || trimmed.length > GROUP_NAME_MAX ->
                "A group name is between $GROUP_NAME_MIN and $GROUP_NAME_MAX characters."
            else -> null
        }
    }

    /** Budgets by episode, in the order episodes first appear (`groupBy(data, 'episode')`). */
    fun byEpisode(documents: List<BudgetDocument>): Map<String, List<BudgetDocument>> =
        documents.filterNot { it.deleted }.groupBy { it.episode }

    /**
     * A person's name search over the version picker (`CommonBudget.jsx:
     * 1900-1913`): the query matches *uploaders*, and the versions those
     * uploaders posted are what remain.
     */
    fun versionsByUploader(
        documents: List<BudgetDocument>,
        query: String,
        nameOf: (String) -> String?,
    ): List<BudgetDocument> {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return documents
        return documents.filter { document ->
            (nameOf(document.uploadedById) ?: document.uploadedByName).trim().lowercase().contains(needle)
        }
    }

    /** The web's `capitalizeWords`: each word's first letter up, the rest as typed. */
    fun capitalizeWords(text: String): String =
        text.split(' ').joinToString(" ") { word ->
            word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }

    private fun Month.shortName(): String = name.take(MONTH_ABBREVIATION).lowercase()
        .replaceFirstChar { it.titlecase() }

    private val SPREADSHEETS = setOf("xlsx", "xls", "csv")
    private const val MONTH_ABBREVIATION = 3
    private const val GROUP_NAME_MIN = 3
    private const val GROUP_NAME_MAX = 25

    private const val AWS_XLS_THUMB = "65e80a572e5d77a85339161a/home/actual/9kc172w6swExcel.png"
    private const val AWS_PDF_THUMB = "6777b7ede9c303151d721ba9/home/actual/pdf1738063181064.png"
    private const val BOX_XLS_THUMB = "1581028887852"
    private const val BOX_PDF_THUMB = "1581037016800"
}
