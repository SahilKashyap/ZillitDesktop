package com.zillit.desktop.feature.settings.admin.ui

import com.zillit.desktop.feature.settings.admin.domain.CompanyDetails
import com.zillit.desktop.feature.settings.admin.domain.Department
import com.zillit.desktop.feature.settings.admin.domain.NewSosRecipient

/**
 * Everything an administrator can do on these pages.
 *
 * Grouped by shape rather than by page: several pages create a named thing and
 * several delete one, and expressing that once means the view model has one
 * branch for "a name was submitted" instead of six near-identical ones.
 */
sealed interface AdminEvent {

    /** The window arrived on a page. Loads once; coming back keeps what it had. */
    data class Opened(val destination: AdminDestination) : AdminEvent

    data object Refresh : AdminEvent

    data class SearchChanged(val query: String) : AdminEvent

    data object DismissOutcome : AdminEvent

    data object DismissError : AdminEvent

    // -- forms ---------------------------------------------------------------

    /** Opens the name form — for a new thing, or to rename [targetId]. */
    data class OpenName(
        val kind: NameKind,
        val targetId: String? = null,
        val initial: String = "",
    ) : AdminEvent

    data class OpenPreApproval(val nothing: Unit = Unit) : AdminEvent

    data class OpenSos(val kind: SosKind) : AdminEvent

    /** Opens the company form on what is currently saved. */
    data object OpenCompany : AdminEvent

    data object OpenProductionName : AdminEvent

    /**
     * Any single-field edit inside the open form.
     *
     * One event with a field selector rather than one per field: the company
     * form alone has seven, and seven more events would be seven more branches
     * saying `copy(x = value)`.
     */
    data class FieldChanged(val field: AdminField, val value: String) : AdminEvent

    /** Company custom fields, which are a list rather than a field. */
    data class CustomFieldsChanged(val fields: List<CompanyFieldDraft>) : AdminEvent

    data class SosDraftChanged(val draft: NewSosRecipient) : AdminEvent

    data class CompanyDraftChanged(val draft: CompanyDetails) : AdminEvent

    data object CloseForm : AdminEvent

    /** Sends whatever the open form holds. */
    data object SubmitForm : AdminEvent

    // -- selection -------------------------------------------------------------

    /** Opens a department's job titles, or picks the one being reordered. */
    data class SelectDepartment(val departmentId: String?) : AdminEvent

    /** Opens one person's rights. */
    data class SelectCrew(val userId: String?) : AdminEvent

    // -- direct actions -----------------------------------------------------------

    /** Grants or revokes administering the production. Granting asks first. */
    data class AdminAccessChanged(val userId: String, val isAdmin: Boolean) : AdminEvent

    /** Takes someone off the production, or puts them back. Removing asks first. */
    data class CrewActiveChanged(val userId: String, val isActive: Boolean) : AdminEvent

    /**
     * Ticks a tool on the availability page.
     *
     * Local until [SaveTools]: this page is a checklist an admin works through,
     * and a request per tick would be twenty requests and no way to change your
     * mind. Both phone clients do the same.
     */
    data class ToolEnabledChanged(val identifier: String, val enabled: Boolean) : AdminEvent

    data object SaveTools : AdminEvent

    /** Moves a tool between groups. A blank [groupIdentifier] ungroups it. */
    data class MoveTool(val identifier: String, val groupIdentifier: String) : AdminEvent

    /** Grants or revokes one right, with whatever else that implies. */
    data class RightsToggled(val toggle: RightsToggle) : AdminEvent

    /** Shooting units only. */
    data class UnitEnabledChanged(val unitId: String, val enabled: Boolean) : AdminEvent

    // -- reordering ---------------------------------------------------------------

    /** Moves a department up or down the crew list, locally. */
    data class MoveDepartment(val departmentId: String, val by: Int) : AdminEvent

    /** Puts the arranged order back to what the server last said. */
    data object ResetOrder : AdminEvent

    data object SaveOrder : AdminEvent

    // -- destructive ------------------------------------------------------------------

    /** Asks before doing something that cannot be undone from this page. */
    data class Ask(val confirmation: AdminConfirmation) : AdminEvent

    data object ConfirmAction : AdminEvent

    data object DismissConfirmation : AdminEvent

    /** Calls off a scheduled deletion. No confirmation — this is the safe direction. */
    data object CancelDeletion : AdminEvent
}

/**
 * A single-field edit, by which field.
 *
 * Named rather than positional so the view model's `when` reads as the form it
 * is editing. Only fields that are plain text are here — pickers carry their
 * own events, because choosing a department also has to clear the job title.
 */
enum class AdminField {
    /** The name form, and the production-name form. */
    Name,

    // -- pre-approval ------------------------------------------------------
    FirstName,
    LastName,
    Email,
    CountryCode,
    Phone,
    Department,
    JobTitle,
}

/**
 * Reordering, as one operation.
 *
 * Extracted so the rule is testable without a view model: a move that would
 * fall off either end is not an error, it simply does not happen — the buttons
 * at the ends are disabled, so one arriving here is a keyboard repeat.
 */
fun List<Department>.moved(departmentId: String, by: Int): List<Department> {
    val from = indexOfFirst { it.id == departmentId }.takeIf { it >= 0 } ?: return this
    val to = (from + by).coerceIn(0, lastIndex)
    if (to == from) return this

    return toMutableList().apply { add(to, removeAt(from)) }
}
