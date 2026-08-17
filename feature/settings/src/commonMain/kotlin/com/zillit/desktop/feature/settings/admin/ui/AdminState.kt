package com.zillit.desktop.feature.settings.admin.ui

import com.zillit.desktop.feature.settings.admin.domain.AccessType
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
    val company: CompanyDetails = CompanyDetails(),
    val watermarkUrl: String? = null,
    val productionName: String = "",
    val deletion: DeletionSchedule = DeletionSchedule(),

    /** The dialog or inline form this page currently has open. */
    val form: AdminForm? = null,
    /** The destructive action waiting on a yes. */
    val confirming: AdminConfirmation? = null,

    /** Job titles, crew order and rights all work inside one selection. */
    val selection: AdminSelection = AdminSelection(),
) {
    val departmentsMatching: List<Department>
        get() = departments.filter { it.name.contains(query.trim(), ignoreCase = true) }

    val crewMatching: List<CrewMember> get() = crew.filter { it.matches(query) }

    val preApprovedMatching: List<PreApprovedCrew> get() = preApproved.filter { it.matches(query) }

    val toolsMatching: List<ProductionTool>
        get() = tools.filter { it.name.contains(query.trim(), ignoreCase = true) }

    val unitsMatching: List<AdminUnit>
        get() = units.filter { it.name.contains(query.trim(), ignoreCase = true) }

    /** The department whose job titles or crew order are open. */
    val selectedDepartment: Department?
        get() = departments.firstOrNull { it.id == selection.departmentId }

    /** The crew member whose rights are open. */
    val selectedCrew: CrewMember?
        get() = crew.firstOrNull { it.userId == selection.userId }

    fun rights(section: RightsSection): List<ToolRights> =
        selection.rights.filter { it.section == section }

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
 * Job titles are inside a department, rights are inside a person, and the crew
 * order is a list being rearranged before it is saved. Held together rather
 * than as three loose fields so that leaving a page can clear all of it at
 * once — a stale department id surviving into the rights page is how a screen
 * ends up showing someone else's data.
 */
data class AdminSelection(
    val departmentId: String? = null,
    val userId: String? = null,
    /** The person's rights, once fetched. */
    val rights: List<ToolRights> = emptyList(),
    val isLoadingRights: Boolean = false,
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
                isRename -> "Rename ${kind.noun}"
                else -> "New ${kind.noun}"
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
enum class NameKind(val noun: String) {
    Department("department"),
    JobTitle("job title"),
    ToolGroup("tool group"),
    Unit("unit"),
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
        override val title = "Delete this department?"
        override val message =
            "“$name” goes from this production. Crew filed under it keep their job title " +
                "but lose their department until someone gives them a new one."
        override val confirmLabel = "Delete department"
    }

    data class RemoveJobTitle(
        val departmentId: String,
        val id: String,
        val name: String,
    ) : AdminConfirmation {
        override val title = "Delete this job title?"
        override val message = "“$name” stops being offered when crew join or change department."
        override val confirmLabel = "Delete job title"
    }

    data class RemoveToolGroup(val id: String, val name: String) : AdminConfirmation {
        override val title = "Delete this group?"
        override val message = "“$name” goes from the Film Tools grid. The tools themselves stay."
        override val confirmLabel = "Delete group"
    }

    data class RemoveUnit(val kind: UnitKind, val id: String, val name: String) : AdminConfirmation {
        override val title = "Delete this unit?"
        override val message =
            "“$name” goes from this production. Crew attached to it stop receiving its " +
                "call sheets and notices."
        override val confirmLabel = "Delete unit"
    }

    data class RemoveSos(val id: String, val name: String) : AdminConfirmation {
        override val title = "Remove this recipient?"
        override val message = "$name stops being alerted when someone on this production raises an SOS."
        override val confirmLabel = "Remove"
    }

    data class RemoveFromCrew(val userId: String, val deviceId: String, val name: String) :
        AdminConfirmation {
        override val title = "Take this person off the production?"
        override val message =
            "$name loses access to everything on it. They can be put back from this page, " +
                "and nothing they posted is deleted."
        override val confirmLabel = "Remove from crew"
    }

    /** Granting is asked about; revoking is not. Both phone clients agree. */
    data class GrantAdmin(val userId: String, val name: String) : AdminConfirmation {
        override val title = "Make this person an administrator?"
        override val message =
            "$name will be able to change everything on this page — crew, departments, " +
                "permissions — and to delete the production."
        override val confirmLabel = "Grant admin rights"
    }

    data class ClearWatermark(val nothing: Unit = Unit) : AdminConfirmation {
        override val title = "Remove the watermark?"
        override val message = "Documents this production sends out stop being stamped."
        override val confirmLabel = "Remove watermark"
    }

    data class ClearCompanyLogo(val nothing: Unit = Unit) : AdminConfirmation {
        override val title = "Remove the company logo?"
        override val message = "The crew list header keeps its text and loses the image."
        override val confirmLabel = "Remove logo"
    }

    /**
     * Scheduling the production's deletion.
     *
     * [hours] is the delay, and the delay is the safety: the production stays
     * visible and the deletion can be called off until it elapses.
     */
    data class DeleteProduction(val hours: Int, val name: String) : AdminConfirmation {
        override val title = "Delete this production in $hours hours?"
        override val message =
            "Everything on “$name” goes, for everyone on it — every notice, document, " +
                "timecard and message. It can be called off from this page until the " +
                "$hours hours are up. After that it cannot."
        override val confirmLabel = "Schedule deletion"
    }
}

/** Which access checkbox was clicked, for the rights page. */
data class RightsToggle(
    val toolIdentifier: String,
    val section: RightsSection,
    val access: AccessType,
    val enable: Boolean,
)

/** A row of the company form's repeatable field list. */
typealias CompanyFieldDraft = CompanyField

/** Which kind of SOS recipient the form is adding. */
typealias SosKind = SosEntryType
