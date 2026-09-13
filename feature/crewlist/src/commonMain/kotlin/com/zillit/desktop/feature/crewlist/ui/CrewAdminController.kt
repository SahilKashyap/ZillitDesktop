package com.zillit.desktop.feature.crewlist.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localisedMessage
import com.zillit.desktop.feature.crewlist.domain.CompanyField
import com.zillit.desktop.feature.crewlist.domain.CompanyLogo
import com.zillit.desktop.feature.crewlist.domain.CompanyRules
import com.zillit.desktop.feature.crewlist.domain.CrewListHost
import com.zillit.desktop.feature.crewlist.domain.CrewListRepository
import com.zillit.desktop.feature.crewlist.domain.DepartmentOrder
import com.zillit.desktop.feature.crewlist.domain.OrderedDepartment
import com.zillit.desktop.feature.crewlist.domain.PeopleOrder

/**
 * The two admin editors the crew list opens in place, as the web mounts them
 * on its own screen: the department listing order (`ChangePriorityList`) and
 * the company details the letterhead prints (`CompanyDetails`).
 *
 * Administrators only — a role, not a rights-grid grant, so there is nobody to
 * ask on the viewer's behalf; the controls are hidden and the handlers refuse.
 */
internal class CrewAdminController(
    private val store: CrewStore,
    private val repository: CrewListRepository,
    private val host: CrewListHost,
    private val onCompanySaved: () -> Unit,
    private val onDepartmentsSaved: () -> Unit,
) {

    @Suppress("CyclomaticComplexMethod") // Event fan-out: one line per act.
    fun onEvent(event: CrewListEvent.Admin) {
        if (!store.state.viewer.isAdmin) return
        when (event) {
            CrewListEvent.Admin.OpenDepartments -> openDepartments()
            CrewListEvent.Admin.CloseDepartments -> closeDepartments()
            is CrewListEvent.Admin.MoveDepartment -> editOrder { move(event.from, event.to) }
            is CrewListEvent.Admin.PositionDepartment -> position(event.index, event.position)
            is CrewListEvent.Admin.SearchDepartments -> store.update {
                copy(departments = departments?.copy(query = event.query))
            }
            CrewListEvent.Admin.ResetDepartments -> editOrder { reset() }
            CrewListEvent.Admin.SaveDepartments -> saveDepartments()
            is CrewListEvent.Admin.ResolveDiscard ->
                if (event.save) {
                    store.update { copy(departments = departments?.copy(confirmDiscard = false)) }
                    saveDepartments()
                } else {
                    store.update { copy(departments = null) }
                }
            is CrewListEvent.Admin.OpenPeople -> openPeople(event.department)
            CrewListEvent.Admin.ClosePeople -> store.update { copy(departments = departments?.copy(people = null)) }
            is CrewListEvent.Admin.MovePerson -> editPeople { copy(order = order.move(event.from, event.to)) }
            is CrewListEvent.Admin.PositionPerson -> positionPerson(event.index, event.position)
            is CrewListEvent.Admin.SearchPeople -> editPeople { copy(query = event.query) }
            CrewListEvent.Admin.SavePeople -> savePeople()
            CrewListEvent.Admin.OpenCompany -> openCompany()
            CrewListEvent.Admin.CloseCompany -> store.update { copy(company = null) }
            is CrewListEvent.Admin.EditCompany -> store.update {
                copy(company = company?.copy(details = event.details, problems = emptyList()))
            }
            CrewListEvent.Admin.ToggleCompanyDetails -> store.update {
                copy(company = company?.copy(detailsExpanded = !company.detailsExpanded))
            }
            CrewListEvent.Admin.PickLogo -> pickLogo()
            CrewListEvent.Admin.AskRemoveLogo -> store.update {
                copy(company = company?.copy(confirmRemoveLogo = true))
            }
            is CrewListEvent.Admin.ResolveRemoveLogo -> store.update {
                val editor = company ?: return@update this
                copy(
                    company = if (event.remove) {
                        editor.copy(confirmRemoveLogo = false, pickedLogo = null, logoImage = null, removeLogo = true)
                    } else {
                        editor.copy(confirmRemoveLogo = false)
                    },
                )
            }
            CrewListEvent.Admin.SaveCompany -> saveCompany()
        }
    }

    // --- department order ----------------------------------------------------------------------

    private fun openDepartments() {
        store.update { copy(departments = DepartmentOrderState()) }
        store.launch {
            when (val loaded = host.departments()) {
                is ZillitResult.Success -> store.update {
                    copy(departments = departments?.copy(order = DepartmentOrder(loaded.data), loading = false))
                }
                is ZillitResult.Failure -> {
                    val message = loaded.error.readable()
                    store.update { copy(departments = departments?.copy(loading = false, failure = message)) }
                }
            }
        }
    }

    /** Cancel with changes asks first — "Do you want to save changes?" — as the web's warning does. */
    private fun closeDepartments() {
        val editor = store.state.departments ?: return
        if (editor.order.isChanged && !editor.saving) {
            store.update { copy(departments = editor.copy(confirmDiscard = true)) }
        } else {
            store.update { copy(departments = null) }
        }
    }

    private fun editOrder(change: DepartmentOrder.() -> DepartmentOrder) = store.update {
        copy(departments = departments?.let { it.copy(order = it.order.change()) })
    }

    private fun position(index: Int, position: Int) {
        val editor = store.state.departments ?: return
        val moved = editor.order.moveToPosition(index, position)
        if (moved == null) {
            val size = editor.order.current.size
            val message = store.copy("invalid_position_popup", "Priority must be between %s and %s")
                .replaceFirst("%s", "1")
                .replaceFirst("%s", size.toString())
            store.toast(message, CrewListEffect.Tone.Error)
            return
        }
        store.update { copy(departments = editor.copy(order = moved)) }
    }

    private fun saveDepartments() {
        val editor = store.state.departments ?: return
        if (editor.saving || !editor.order.isChanged) return
        store.update { copy(departments = editor.copy(saving = true, confirmDiscard = false)) }
        store.launch {
            when (val saved = host.reorderDepartments(editor.order.current.map { it.id })) {
                is ZillitResult.Success -> {
                    store.update { copy(departments = null) }
                    store.toast(
                        store.copy("project_departments_reordered_successfully", "Department order saved."),
                        CrewListEffect.Tone.Success,
                    )
                    onDepartmentsSaved()
                }
                is ZillitResult.Failure -> {
                    store.update { copy(departments = departments?.copy(saving = false)) }
                    store.toast(saved.error.readable(), CrewListEffect.Tone.Error)
                }
            }
        }
    }

    // --- the people inside a department ---------------------------------------------------------

    private fun openPeople(department: OrderedDepartment) {
        store.update { copy(departments = departments?.copy(people = PeopleOrderState(PeopleOrder(department)))) }
        store.launch {
            val loaded = repository.departmentPeople(department.id)
            store.update {
                val editor = departments ?: return@update this
                val people = editor.people?.takeIf { it.order.department.id == department.id } ?: return@update this
                copy(
                    departments = editor.copy(
                        people = when (loaded) {
                            is ZillitResult.Success ->
                                people.copy(order = PeopleOrder(department, loaded.data), loading = false)
                            is ZillitResult.Failure -> people.copy(loading = false, failure = loaded.error.readable())
                        },
                    ),
                )
            }
        }
    }

    private fun editPeople(change: PeopleOrderState.() -> PeopleOrderState) = store.update {
        val editor = departments ?: return@update this
        copy(departments = editor.copy(people = editor.people?.change()))
    }

    private fun positionPerson(index: Int, position: Int) {
        val people = store.state.departments?.people ?: return
        val moved = people.order.moveToPosition(index, position)
        if (moved == null) {
            val message = store.copy("invalid_position_popup", "Priority must be between %s and %s")
                .replaceFirst("%s", "1")
                .replaceFirst("%s", people.order.current.size.toString())
            store.toast(message, CrewListEffect.Tone.Error)
            return
        }
        editPeople { copy(order = moved) }
    }

    /** The web keeps the list open after saving, showing the server's own words. */
    private fun savePeople() {
        val people = store.state.departments?.people ?: return
        if (people.saving || people.loading) return
        editPeople { copy(saving = true) }
        store.launch {
            when (val saved = repository.reorderPeople(people.order.current.map { it.userId })) {
                is ZillitResult.Success -> {
                    editPeople { copy(saving = false, order = order.saved()) }
                    store.toast(saved.data.localisedMessage().ifBlank { "Order saved." }, CrewListEffect.Tone.Success)
                    onDepartmentsSaved()
                }
                is ZillitResult.Failure -> {
                    editPeople { copy(saving = false) }
                    val message = saved.error.readable().ifBlank { "Failed to save the new order." }
                    store.toast(message, CrewListEffect.Tone.Error)
                }
            }
        }
    }

    // --- company details --------------------------------------------------------------------------

    private fun openCompany() {
        store.update { copy(company = CompanyEditorState()) }
        store.launch {
            when (val loaded = repository.companyDetails()) {
                is ZillitResult.Success -> {
                    store.update { copy(company = company?.copy(details = loaded.data, loading = false)) }
                    val logo = loaded.data.logo ?: return@launch
                    val image = host.logoImage(logo) ?: return@launch
                    store.update {
                        val editor = company ?: return@update this
                        // Not over a logo picked while the stored one was still loading.
                        if (editor.pickedLogo != null || editor.removeLogo) {
                            this
                        } else {
                            copy(company = editor.copy(logoImage = image))
                        }
                    }
                }
                is ZillitResult.Failure -> {
                    val message = loaded.error.readable()
                    store.update { copy(company = company?.copy(loading = false, failure = message)) }
                }
            }
        }
    }

    private fun pickLogo() {
        store.launch {
            val picked = host.pickLogo() ?: return@launch
            val image = host.decode(picked.bytes)
            if (image == null) {
                store.toast("That file is not an image the logo can use.", CrewListEffect.Tone.Error)
                return@launch
            }
            store.update {
                copy(company = company?.copy(pickedLogo = picked, logoImage = image, removeLogo = false))
            }
        }
    }

    /**
     * The web's save: validate; upload a freshly picked logo; remove a cleared
     * one through its own route; patch every field; tell the designer, whose
     * letterhead just changed.
     */
    private fun saveCompany() {
        val editor = store.state.company ?: return
        if (editor.saving || editor.loading) return
        val problems = CompanyRules.validate(editor.details) { key, fallback -> store.copy(key, fallback) }
        if (problems.isNotEmpty()) {
            store.update { copy(company = editor.copy(problems = problems)) }
            // The phone pair has no field of its own to show under, as on the web.
            problems.firstOrNull { it.field == CompanyField.Phone }
                ?.let { store.toast(it.message, CrewListEffect.Tone.Error) }
            return
        }
        store.update { copy(company = editor.copy(saving = true, problems = emptyList())) }
        store.launch {
            val logo: CompanyLogo? = editor.pickedLogo?.let { picked ->
                when (val uploaded = host.uploadLogo(picked)) {
                    is ZillitResult.Success -> uploaded.data
                    is ZillitResult.Failure -> {
                        store.update { copy(company = company?.copy(saving = false)) }
                        store.toast(uploaded.error.readable(), CrewListEffect.Tone.Error)
                        return@launch
                    }
                }
            }
            when (val saved = repository.saveCompanyDetails(editor.details, logo, editor.removeLogo)) {
                is ZillitResult.Success -> {
                    store.update { copy(company = null) }
                    store.toast(store.copy("CompanyDetailsSaved", "Company details saved"), CrewListEffect.Tone.Success)
                    onCompanySaved()
                }
                is ZillitResult.Failure -> {
                    store.update { copy(company = company?.copy(saving = false)) }
                    store.toast(saved.error.readable().ifBlank { "Something went wrong" }, CrewListEffect.Tone.Error)
                }
            }
        }
    }
}
