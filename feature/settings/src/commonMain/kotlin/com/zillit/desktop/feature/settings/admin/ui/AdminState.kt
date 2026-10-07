package com.zillit.desktop.feature.settings.admin.ui

import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.settings.admin.domain.AdminUnit
import com.zillit.desktop.feature.settings.admin.domain.CompanyDetails
import com.zillit.desktop.feature.settings.admin.domain.CompanyField
import com.zillit.desktop.feature.settings.admin.domain.CrewMember
import com.zillit.desktop.feature.settings.admin.domain.Department
import com.zillit.desktop.feature.settings.admin.domain.DeletionSchedule
import com.zillit.desktop.feature.settings.admin.domain.NewSosRecipient
import com.zillit.desktop.feature.settings.admin.domain.PreApprovedCrew
import com.zillit.desktop.feature.settings.admin.domain.ProductionTool
import com.zillit.desktop.feature.settings.admin.domain.RightsSection
import com.zillit.desktop.feature.settings.admin.domain.SosEntryType
import com.zillit.desktop.feature.settings.admin.domain.SosRecipient
import com.zillit.desktop.feature.settings.admin.domain.ToolGroup
import com.zillit.desktop.feature.settings.admin.domain.ToolRights
import com.zillit.desktop.feature.settings.admin.domain.UnitKind

/**
 * What every administration page shares.
 *
 * Sixteen pages, one state class, because they all do the same four things: ask
 * the server for a list, show whether that worked, let one thing be changed,
 * and say what happened. Sixteen state classes would be the same four fields
 * sixteen times, and a bug fixed in one of them.
 *
 * The per-page differences are the *lists*, and those are the fields below.
 */
data class AdminUiState(
    /** Which page is open, so the loader knows what to fetch. */
    val destination: AdminDestination = AdminDestination.Departments,
    val isLoading: Boolean = false,
    /** True once this destination has answered — "empty" and "not asked" differ. */
    val hasLoaded: Boolean = false,
    val error: String? = null,
    /** What just happened, for the strip above the page. */
    val outcome: String? = null,
    /** Filters whichever list is open. */
    val query: String = "",
    /**
     * A change is in flight.
     *
     * One flag rather than a set of ids: unlike the approval queues, these
     * pages change one thing at a time, and a second click during a save is
     * almost always the same click landing twice.
     */
    val isSaving: Boolean = false,

    val departments: List<Department> = emptyList(),
    val crew: List<CrewMember> = emptyList(),
    val preApproved: List<PreApprovedCrew> = emptyList(),
    val tools: List<ProductionTool> = emptyList(),
    val toolGroups: List<ToolGroup> = emptyList(),
    val sos: List<SosRecipient> = emptyList(),
    val units: List<AdminUnit> = emptyList(),
    /** The shooting units User Management's Change Unit picker offers — `join/unit`. */
    val joinUnits: List<AdminUnit> = emptyList(),
    /** The admin at the keyboard: User Management never lists them, as the web does not. */
    val selfUserId: String? = null,
    val company: CompanyDetails = CompanyDetails(),
    val watermarkUrl: String? = null,
    val productionName: String = "",
    val deletion: DeletionSchedule = DeletionSchedule(),

    /** The dialog or inline form this page currently has open. */
    val form: AdminForm? = null,
    /** The destructive action waiting on a yes. */
    val confirming: AdminConfirmation? = null,

    /** Job titles and crew order both work inside one selection. */
    val selection: AdminSelection = AdminSelection(),
) {
    val departmentsMatching: List<Department>
        get() = departments.filter { it.name.contains(query.trim(), ignoreCase = true) }

    /**
     * User Management's rows: the web's filter — no one who left, is pending
     * or was rejected, never the reader — then its search over name and the
     * designation as shown.
     */
    val crewMatching: List<CrewMember>
        get() = crew.filter {
            it.isListed && it.userId != selfUserId && it.matches(query) { key -> key.localised() }
        }

    val preApprovedMatching: List<PreApprovedCrew> get() = preApproved.filter { it.matches(query) }

    val toolsMatching: List<ProductionTool>
        get() = tools.filter { it.name.contains(query.trim(), ignoreCase = true) }

    val unitsMatching: List<AdminUnit>
        get() = units.filter { it.name.contains(query.trim(), ignoreCase = true) }

    /** The department whose job titles or crew order are open. */
    val selectedDepartment: Department?
        get() = departments.firstOrNull { it.id == selection.departmentId }

    /** Which unit kind this destination is about. */
    val unitKind: UnitKind?
        get() = when (destination) {
            AdminDestination.HomeUnits -> UnitKind.Home
            AdminDestination.RemoteUnits -> UnitKind.Remote
            AdminDestination.ShootingUnits -> UnitKind.Shooting
            else -> null
        }
}

/**
 * What is picked on the pages that work inside one thing.
 *
 * Job titles are inside a department, and the crew order is a list being
 * rearranged before it is saved. Held together rather than as loose fields so
 * that leaving a page can clear all of it at once — a stale id surviving into
 * the next page is how a screen ends up showing someone else's data.
 */
data class AdminSelection(
    val departmentId: String? = null,
    /**
     * The order being arranged, before it is saved.
     *
     * Local until Save, matching all three clients: reordering is a series of
     * small moves and writing each one would mean a request per nudge, and a
     * half-finished order on the server if the admin walks away.
     */
    val order: List<Department> = emptyList(),
) {
    /** Whether the arranged order differs from what was loaded. */
    fun isReordered(loaded: List<Department>): Boolean =
        order.isNotEmpty() && order.map { it.id } != loaded.map { it.id }
}

/**
 * The form a page has open.
 *
 * A sealed set rather than a nullable field per form, so a page can only ever
 * have one open and closing is one assignment. Each carries its own draft: an
 * admin who types a department name, opens something else and comes back has
 * not created a department, and the draft going with the form is what makes
 * that true without a cancel path per form.
 */
sealed interface AdminForm {

    /** Naming something new — a department, a job title, a group, a unit. */
    data class Name(
        val kind: NameKind,
        val value: String = "",
        /** Set when renaming; null when creating. */
        val targetId: String? = null,
        val error: String? = null,
    ) : AdminForm {
        val isRename: Boolean get() = targetId != null

        /**
         * The rule every one of these shares.
         *
         * Three characters, checked here rather than at each call site: all
         * three clients enforce it client-side, and the server's rejection for
         * a two-character name is a translation key rather than a sentence.
         */
        val isValid: Boolean get() = value.trim().length >= MIN_NAME

        val title: String
            get() = when {
                isRename -> str(kind.renameTitleKey)
                else -> str(kind.newTitleKey)
            }
    }

    /** The pre-approval form, which is the only multi-field one. */
    data class PreApproval(
        val firstName: String = "",
        val lastName: String = "",
        val departmentId: String? = null,
        val jobTitleId: String? = null,
        val email: String = "",
        val countryCode: String = "",
        val phone: String = "",
        val error: String? = null,
    ) : AdminForm {
        /**
         * Names are 3–15 characters on every client, and both are required.
         *
         * A department alone is not enough: someone let in with a department
         * and no role lands on the crew list with a blank job title, which is
         * the state the approval queue exists to prevent.
         */
        val isValid: Boolean
            get() = firstName.trim().length in NAME_LENGTH &&
                lastName.trim().length in NAME_LENGTH &&
                !departmentId.isNullOrBlank() &&
                !jobTitleId.isNullOrBlank()
    }

    /** Adding an SOS recipient — crew or outsider. */
    data class Sos(val draft: NewSosRecipient, val error: String? = null) : AdminForm

    /** The company block, edited as a whole and saved in one go. */
    data class Company(val draft: CompanyDetails, val error: String? = null) : AdminForm

    /**
     * User Management's Change Profile dialog — department, designation, unit
     * and, for the three designations that may hide a name, Keep Name Private.
     *
     * [withUnit] is false on a corporate or event production, which the web
     * offers no unit picker on (`isOtherProject()`).
     */
    data class EditCrew(
        val userId: String,
        val name: String,
        val departmentId: String? = null,
        val designationId: String? = null,
        val unitId: String? = null,
        val keepNamePrivate: Boolean = false,
        val withUnit: Boolean = true,
        val error: String? = null,
    ) : AdminForm

    /**
     * User Management's Posting Rights dialog for one person.
     *
     * Not submitted — each switch writes at once, as on the web. [busy] holds
     * the switches whose writes are out, keyed by [ToolRights.unitId] plus the
     * access type, so a second click on one cannot race its own first.
     */
    data class PostingRights(
        val userId: String,
        val section: RightsSection = RightsSection.Home,
        val rows: List<ToolRights> = emptyList(),
        val isLoading: Boolean = true,
        val busy: Set<String> = emptySet(),
        val error: String? = null,
    ) : AdminForm {
        val shown: List<ToolRights> get() = rows.filter { it.section == section }
    }

    /**
     * Who a private-name crew member may chat with — the web's Select User
     * dialog. The whole selection is sent on Submit.
     */
    data class AllowChat(
        val userId: String,
        val selected: Set<String> = emptySet(),
        val query: String = "",
        val isLoading: Boolean = true,
        val error: String? = null,
    ) : AdminForm

    /** The production's name. */
    data class ProductionName(val value: String, val error: String? = null) : AdminForm {
        /** 3–25, which is the tighter of the two limits the clients enforce. */
        val isValid: Boolean get() = value.trim().length in PRODUCTION_NAME_LENGTH
    }

    private companion object {
        const val MIN_NAME = 3
        val NAME_LENGTH = 3..15
        val PRODUCTION_NAME_LENGTH = 3..25
    }
}

/** What a [AdminForm.Name] is naming, for the wording and the call. */
enum class NameKind(
    val renameTitleKey: String,
    val newTitleKey: String,
    /** The three-character rule, said for this kind of name. */
    val nameTooShortKey: String,
) {
    Department(S.desktop_rename_department, S.desktop_new_department, S.desktop_department_name_too_short),
    JobTitle(S.desktop_rename_job_title, S.desktop_new_job_title, S.desktop_job_title_name_too_short),
    ToolGroup(S.desktop_rename_tool_group, S.desktop_new_tool_group, S.desktop_tool_group_name_too_short),
    Unit(S.desktop_rename_unit, S.desktop_new_unit, S.desktop_unit_name_too_short),
}

/**
 * A destructive action waiting on a yes.
 *
 * Every one of these removes something other people depend on — a department
 * crew are filed under, a unit whose call sheets go somewhere. Asking is not
 * ceremony: none of them are undoable from this page.
 */
sealed interface AdminConfirmation {
    /** What the dialog says, in the words of the thing being removed. */
    val title: String
    val message: String
    val confirmLabel: String

    data class RemoveDepartment(val id: String, val name: String) : AdminConfirmation {
        override val title: String get() = str(S.desktop_delete_this_department)
        override val message: String get() = str(S.desktop_delete_department_message, name)
        override val confirmLabel: String get() = str(S.desktop_delete_department)
    }

    data class RemoveJobTitle(
        val departmentId: String,
        val id: String,
        val name: String,
    ) : AdminConfirmation {
        override val title: String get() = str(S.desktop_delete_this_job_title)
        override val message: String get() = str(S.desktop_delete_job_title_message, name)
        override val confirmLabel: String get() = str(S.desktop_delete_job_title)
    }

    data class RemoveToolGroup(val id: String, val name: String) : AdminConfirmation {
        override val title: String get() = str(S.desktop_delete_this_group)
        override val message: String get() = str(S.desktop_delete_group_message, name)
        override val confirmLabel: String get() = str(S.mtg_delete_group_title)
    }

    data class RemoveUnit(val kind: UnitKind, val id: String, val name: String) : AdminConfirmation {
        override val title: String get() = str(S.desktop_delete_this_unit)
        override val message: String get() = str(S.desktop_delete_unit_message, name)
        override val confirmLabel: String get() = str(S.desktop_delete_unit)
    }

    data class RemoveSos(val id: String, val name: String) : AdminConfirmation {
        override val title: String get() = str(S.desktop_remove_this_recipient)
        override val message: String get() = str(S.desktop_remove_recipient_message, name)
        override val confirmLabel: String get() = str(S.remove)
    }

    /**
     * Granting is asked about; revoking is not — all three clients agree. The
     * web's dialog shows who: picture, name, designation, a department pill.
     */
    data class GrantAdmin(
        val userId: String,
        val name: String,
        val designation: String? = null,
        val department: String? = null,
    ) : AdminConfirmation {
        override val title: String get() = str(S.desktop_um_confirm_admin_rights)
        override val message: String get() = str(S.admin_rights_message)
        override val confirmLabel: String get() = str(S.yes)
    }

    data class ClearWatermark(val nothing: Unit = Unit) : AdminConfirmation {
        override val title: String get() = str(S.desktop_remove_watermark_title)
        override val message: String get() = str(S.desktop_remove_watermark_message)
        override val confirmLabel: String get() = str(S.desktop_remove_watermark)
    }

    data class ClearCompanyLogo(val nothing: Unit = Unit) : AdminConfirmation {
        override val title: String get() = str(S.desktop_remove_logo_title)
        override val message: String get() = str(S.desktop_remove_logo_message)
        override val confirmLabel: String get() = str(S.desktop_remove_logo)
    }

    /**
     * Scheduling the production's deletion.
     *
     * [hours] is the delay, and the delay is the safety: the production stays
     * visible and the deletion can be called off until it elapses.
     */
    data class DeleteProduction(val hours: Int, val name: String) : AdminConfirmation {
        override val title: String get() = str(S.desktop_delete_project_in_hours_title, hours)
        override val message: String get() = str(S.desktop_delete_project_message, name, hours)
        override val confirmLabel: String get() = str(S.desktop_schedule_deletion)
    }
}

/** A row of the company form's repeatable field list. */
typealias CompanyFieldDraft = CompanyField

/** Which kind of SOS recipient the form is adding. */
typealias SosKind = SosEntryType
