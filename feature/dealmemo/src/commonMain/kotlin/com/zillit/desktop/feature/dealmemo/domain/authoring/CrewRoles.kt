package com.zillit.desktop.feature.dealmemo.domain.authoring

import com.zillit.desktop.feature.dealmemo.domain.DealCrewLabels
import com.zillit.desktop.feature.dealmemo.domain.DealCrewUser
import com.zillit.desktop.feature.dealmemo.domain.DealLabels
import com.zillit.desktop.feature.dealmemo.domain.DepartmentCatalogue
import com.zillit.desktop.feature.dealmemo.domain.preview.DealUnit
import com.zillit.desktop.feature.dealmemo.domain.rates.CoveredDepartment
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/** A department or designation choice: its identifier, label, and whether only the master lists it. */
data class RoleOption(val value: String, val label: String, val system: Boolean)

/** A crew member the picker offers. */
data class CrewOption(
    val userId: String,
    val name: String,
    val role: String,
    val department: String,
    val pending: Boolean,
) {
    /** `"{name} — {role}"`, or the name alone. */
    val triggerLabel: String get() = if (role.isNotEmpty()) "$name — $role" else name

    val search: String get() = "$name $role $department"

    /** The row's second line. */
    val subline: String get() = role.ifEmpty { department }.ifEmpty { "Crew" }
}

/**
 * Crew Details' role pickers and crew picker (`Step2Crew.jsx`,
 * `crewOptions.js`, `crewAutoSelect.js`): the agreement's rate card first,
 * then the production's master list; the crew who may still be given a deal;
 * and the role a picked person brings with them.
 */
object CrewRoles {

    /** `department_camera` and `camera_department` both slug to `camera`. */
    fun slug(identifier: String): String = identifier.removePrefix("department_").removeSuffix("_department")

    /** `deptLabelFromIdentifier`: the master row by identifier or slug, else the humanised identifier. */
    fun departmentLabel(identifier: String, catalogue: DepartmentCatalogue): String {
        val row = catalogue.departments.firstOrNull {
            it.identifier == identifier || slug(it.identifier) == slug(identifier)
        }
        return row?.let { DealLabels.formatLabel(it.nameKey) }
            ?: DealLabels.formatLabel(identifier.removePrefix("department_"))
    }

    /** `designationLabelFromIdentifier`: the master designation's name, else the shared resolver. */
    fun designationLabel(identifier: String, catalogue: DepartmentCatalogue): String {
        catalogue.departments.forEach { department ->
            department.designations.firstOrNull { it.identifier == identifier }?.let {
                return DealLabels.formatLabel(it.nameKey)
            }
        }
        return DealCrewLabels(catalogue = catalogue).designationLabel(identifier)
    }

    /** The rate card's departments in its order (deduped by slug), then the master's unseen ones in admin order. */
    fun departmentOptions(covered: List<CoveredDepartment>, catalogue: DepartmentCatalogue): List<RoleOption> {
        val seen = mutableSetOf<String>()
        val out = mutableListOf<RoleOption>()
        covered.forEach { role ->
            val id = role.departmentIdentifier
            if (id.isBlank() || !seen.add(slug(id))) return@forEach
            out += RoleOption(id, departmentLabel(id, catalogue), system = false)
        }
        catalogue.departments.forEach { department ->
            if (department.identifier.isEmpty() || !seen.add(slug(department.identifier))) return@forEach
            out += RoleOption(department.identifier, DealLabels.formatLabel(department.nameKey), system = true)
        }
        return out
    }

    /**
     * The department's rate-card designations A→Z, then the master's unseen
     * ones A→Z — nothing before a department is chosen.
     */
    fun designationOptions(
        department: String,
        covered: List<CoveredDepartment>,
        catalogue: DepartmentCatalogue,
    ): List<RoleOption> {
        if (department.isEmpty()) return emptyList()
        val seen = mutableSetOf<String>()
        val agreement = covered.filter { it.departmentIdentifier == department }
            .flatMap { it.designations }
            .filter { it.isNotEmpty() && seen.add(it) }
            .map { RoleOption(it, designationLabel(it, catalogue), system = false) }
            .sortedBy { it.label.lowercase() }
        val system = catalogue.departments.firstOrNull { it.identifier == department }?.designations.orEmpty()
            .filter { it.identifier.isNotEmpty() && seen.add(it.identifier) }
            .map { RoleOption(it.identifier, DealLabels.formatLabel(it.nameKey), system = true) }
            .sortedBy { it.label.lowercase() }
        return agreement + system
    }

    /**
     * Accepted and pending members without a live deal, and the deal's own
     * crew member put back first when the filter hid them — so an edited deal
     * never blanks its picker.
     */
    fun crewOptions(
        crew: List<DealCrewUser>,
        taken: Set<String>,
        selectedUserId: String,
        fallbackName: String,
    ): List<CrewOption> {
        val options = crew.filter { it.pickable && it.userId !in taken }.map(::optionOf)
        if (selectedUserId.isEmpty() || options.any { it.userId == selectedUserId }) return options
        val user = crew.firstOrNull { it.userId == selectedUserId }
        val synthetic = CrewOption(
            userId = selectedUserId,
            name = user?.fullName?.ifEmpty { null } ?: fallbackName.ifEmpty { "Selected crew member" },
            role = user?.designationName?.let { DealLabels.formatLabel(it) }.orEmpty(),
            department = user?.departmentName?.let { DealLabels.formatLabel(it) }.orEmpty(),
            pending = user?.status == "pending",
        )
        return listOf(synthetic) + options
    }

    private fun optionOf(user: DealCrewUser) = CrewOption(
        userId = user.userId,
        name = user.fullName,
        role = user.designationName?.let { DealLabels.formatLabel(it) }.orEmpty(),
        department = user.departmentName?.let { DealLabels.formatLabel(it) }.orEmpty(),
        pending = user.status == "pending",
    )

    /**
     * `handleUserChange`: the person, their legal name and joined unit, and —
     * when the rate card or master lists their department — their department,
     * with their designation only if that department lists it too.
     */
    fun withCrewMember(
        form: DealForm,
        userId: String?,
        crew: List<DealCrewUser>,
        units: List<DealUnit>,
        covered: List<CoveredDepartment>,
        catalogue: DepartmentCatalogue,
    ): DealForm {
        val user = crew.firstOrNull { it.userId == userId && it.pickable } ?: return form.with("userId", "")
        val patch = LinkedHashMap<String, JsonElement>()
        user.joinUnitId?.takeIf { id -> units.any { it.id == id } }?.let { patch["unit"] = JsonPrimitive(it) }
        val userDepartment = user.departmentIdentifier.orEmpty()
        val userDesignation = user.designationIdentifier.orEmpty()
        val department = userDepartment.takeIf { dept ->
            dept.isNotEmpty() && departmentOptions(covered, catalogue).any { it.value == dept }
        }
        if (department != null) {
            val designation = userDesignation.takeIf { desig ->
                desig.isNotEmpty() && designationOptions(department, covered, catalogue).any { it.value == desig }
            }
            patch["department"] = JsonPrimitive(department)
            patch["designation"] = JsonPrimitive(designation.orEmpty())
            patch["jobTitle"] = JsonPrimitive(designation?.let { designationLabel(it, catalogue) }.orEmpty())
            patch["customJobTitle"] = JsonPrimitive("")
        }
        patch["userId"] = JsonPrimitive(user.userId)
        patch["fullLegalName"] = JsonPrimitive(user.fullName.ifEmpty { form.text("fullLegalName") })
        return form.with(patch)
    }
}
