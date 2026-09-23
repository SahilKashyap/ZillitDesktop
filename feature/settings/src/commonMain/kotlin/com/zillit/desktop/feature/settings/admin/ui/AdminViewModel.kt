package com.zillit.desktop.feature.settings.admin.ui

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.mvvm.ZillitViewModel
import com.zillit.desktop.core.socket.SocketEventBus
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.settings.admin.data.ADMIN_SYNC_EVENTS
import com.zillit.desktop.feature.settings.admin.data.ADMIN_SYNC_PAGES
import com.zillit.desktop.feature.settings.admin.domain.AdminRepository
import com.zillit.desktop.feature.settings.admin.domain.CrewStatus
import com.zillit.desktop.feature.settings.admin.domain.DeletionSchedule
import com.zillit.desktop.feature.settings.admin.domain.NewPreApproval
import com.zillit.desktop.feature.settings.admin.domain.NewSosRecipient
import com.zillit.desktop.feature.settings.admin.domain.RightsChange
import com.zillit.desktop.feature.settings.admin.domain.SosEntryType
import com.zillit.desktop.feature.settings.admin.domain.UnitKind
import com.zillit.desktop.feature.settings.admin.domain.cascadeFrom

/**
 * The administration pages.
 *
 * ## Reload rather than patch
 *
 * Every mutation here is followed by a fresh read of the list it changed, and
 * nothing is applied optimistically. That is deliberate and it is the opposite
 * of what the unit picker in Settings does.
 *
 * These changes have consequences the client cannot predict. Deleting a
 * department can be refused because a budget is posted against it; creating one
 * can be refused as a duplicate the client has not seen; granting a right can
 * cascade into others on the server. In every one of those cases an optimistic
 * update shows an administrator a production that does not exist. The list is
 * short and the reload is one call.
 *
 * ## One view model, sixteen pages
 *
 * They differ in which list they load and which call a button makes. Everything
 * else — the search box, the error strip, the confirmation, the reload — is the
 * same, and sixteen view models would be that machinery sixteen times.
 */
@Suppress("TooManyFunctions") // One handler per action; the count is the page count.
class AdminViewModel(
    private val repository: AdminRepository,
    /**
     * The production's name, for the deletion confirmation.
     *
     * A lambda: the graph outlives a production switch, and a dialog naming the
     * wrong production is the worst possible place for a stale value.
     */
    private val productionName: () -> String = { "" },
    /**
     * Fired after the tool switches save, so the Tools grid rereads its list
     * at once. The `project:tools:update` socket covers other devices; the
     * device that flipped the switch should not wait for its own echo.
     */
    private val onToolsChanged: () -> Unit = {},
    /**
     * The socket, so a second coordinator's changes land on the page being
     * looked at. Null in tests and on a build with no socket.
     */
    private val events: SocketEventBus? = null,
    /**
     * Whether the person at the keyboard administers this production.
     *
     * The screen already refuses everyone else — it renders `NotAnAdmin()`
     * and returns before drawing a single control. This is the second layer,
     * and it is the one that matters here: every write on this surface
     * rewrites the production's own rights, up to granting somebody else
     * administrator. A default of true keeps the tests' construction honest;
     * the app passes the real thing.
     */
    private val isAdmin: () -> Boolean = { true },
) : ZillitViewModel<AdminUiState, AdminEvent, AdminEffect>(AdminUiState()) {

    /** Destinations already read, so returning to one is not a refetch. */
    private val loaded = mutableSetOf<AdminDestination>()

    init {
        listenForChanges()
    }

    /**
     * Somebody else changed the crew, the departments or the queues.
     *
     * Two things happen, and both matter. The page on screen reloads, so the
     * admin sees it. And every *other* affected page is dropped from [loaded],
     * so opening it next reads afresh rather than showing the copy held from
     * before the change — the cache is what would otherwise make this look
     * fixed while still being wrong.
     */
    private fun listenForChanges() {
        val bus = events ?: return

        launch {
            bus.onAny(ADMIN_SYNC_EVENTS).collect { message ->
                val touched = ADMIN_SYNC_PAGES[message.event].orEmpty()
                loaded -= touched
                if (currentState.destination in touched) load(currentState.destination)
            }
        }
    }

    // Exhaustive dispatch over the sealed event set. The branch count is the
    // page count, not complexity — splitting it hides the vocabulary.
    @Suppress("CyclomaticComplexMethod", "LongMethod")
    override fun onEvent(event: AdminEvent) {
        when (event) {
            is AdminEvent.Opened -> open(event.destination)
            AdminEvent.Refresh -> load(currentState.destination)
            is AdminEvent.SearchChanged -> setState { copy(query = event.query) }
            AdminEvent.DismissOutcome -> setState { copy(outcome = null) }
            AdminEvent.DismissError -> setState { copy(error = null) }

            is AdminEvent.OpenName -> setState {
                copy(
                    form = AdminForm.Name(
                        kind = event.kind,
                        value = event.initial,
                        targetId = event.targetId,
                    ),
                )
            }

            is AdminEvent.OpenPreApproval -> setState {
                // Opens on the department already selected, if the admin was
                // looking at one — it is almost always the one they mean.
                copy(form = AdminForm.PreApproval(departmentId = selection.departmentId))
            }

            is AdminEvent.OpenSos ->
                setState { copy(form = AdminForm.Sos(NewSosRecipient(entryType = event.kind))) }

            AdminEvent.OpenCompany -> setState { copy(form = AdminForm.Company(company)) }

            AdminEvent.OpenProductionName ->
                setState { copy(form = AdminForm.ProductionName(productionName)) }

            is AdminEvent.FieldChanged -> onFieldChanged(event)
            is AdminEvent.CustomFieldsChanged -> setState {
                val company = form as? AdminForm.Company ?: return@setState this
                copy(form = company.copy(draft = company.draft.copy(customFields = event.fields)))
            }
            is AdminEvent.SosDraftChanged -> setState {
                copy(form = (form as? AdminForm.Sos)?.copy(draft = event.draft, error = null) ?: form)
            }
            is AdminEvent.CompanyDraftChanged -> setState {
                copy(form = (form as? AdminForm.Company)?.copy(draft = event.draft, error = null) ?: form)
            }

            AdminEvent.CloseForm -> setState { copy(form = null) }
            AdminEvent.SubmitForm -> submit()

            is AdminEvent.SelectDepartment -> selectDepartment(event.departmentId)
            is AdminEvent.SelectCrew -> selectCrew(event.userId)

            is AdminEvent.AdminAccessChanged -> onAdminAccessChanged(event)
            is AdminEvent.CrewActiveChanged -> onCrewActiveChanged(event)

            is AdminEvent.ToolEnabledChanged -> setState {
                copy(
                    tools = tools.map { tool ->
                        if (tool.identifier == event.identifier) tool.copy(enabled = event.enabled) else tool
                    },
                )
            }
            AdminEvent.SaveTools -> mutate(str(S.desktop_tools_updated)) {
                repository.setToolsEnabled(currentState.tools).also { result ->
                    if (result is ZillitResult.Success) onToolsChanged()
                }
            }

            is AdminEvent.MoveTool ->
                mutate(str(S.desktop_tool_moved)) { repository.moveTool(event.identifier, event.groupIdentifier) }

            is AdminEvent.RightsToggled -> onRightsToggled(event)

            is AdminEvent.UnitEnabledChanged -> mutate(
                if (event.enabled) str(S.desktop_unit_switched_on) else str(S.desktop_unit_switched_off),
            ) { repository.setUnitEnabled(event.unitId, event.enabled) }

            is AdminEvent.MoveDepartment -> setState {
                copy(selection = selection.copy(order = selection.order.moved(event.departmentId, event.by)))
            }
            AdminEvent.ResetOrder -> setState { copy(selection = selection.copy(order = departments)) }
            AdminEvent.SaveOrder -> saveOrder()

            is AdminEvent.Ask -> setState { copy(confirming = event.confirmation) }
            AdminEvent.DismissConfirmation -> setState { copy(confirming = null) }
            AdminEvent.ConfirmAction -> confirm()

            AdminEvent.CancelDeletion -> mutate(str(S.desktop_deletion_called_off)) { repository.cancelDeletion() }
        }
    }

    /**
     * Arrives on a page.
     *
     * Clears the search and the selection with it: a query typed on the crew
     * page filtering the departments list is the kind of thing nobody reports
     * as a bug, they just find the page broken.
     */
    private fun open(destination: AdminDestination) {
        setState {
            copy(
                destination = destination,
                query = "",
                error = null,
                outcome = null,
                form = null,
                confirming = null,
                selection = AdminSelection(),
                hasLoaded = destination in loaded,
            )
        }
        if (destination !in loaded) load(destination)
    }

    /**
     * Reads whatever this page shows.
     *
     * Several pages read the same list — job titles and the crew order are both
     * the department tree — and asking again for one already held would show a
     * spinner over data that is on screen.
     */
    @Suppress("CyclomaticComplexMethod") // One branch per page; a lookup table.
    private fun load(destination: AdminDestination) {
        setState { copy(isLoading = true, error = null) }

        launch {
            val result: ZillitResult<Unit> = when (destination) {
                AdminDestination.Departments,
                AdminDestination.JobTitles,
                AdminDestination.CrewOrder,
                -> repository.departments().onLoaded { rows ->
                    setState {
                        copy(
                            departments = rows,
                            // The arranged order starts as the loaded one. Only
                            // replaced when the admin has not begun arranging —
                            // a background reload must not undo their work.
                            selection = selection.copy(
                                order = if (selection.order.isEmpty()) rows else selection.order,
                            ),
                        )
                    }
                }

                AdminDestination.Crew, AdminDestination.Rights ->
                    repository.crew().onLoaded { rows -> setState { copy(crew = rows) } }

                AdminDestination.PreApproved ->
                    repository.preApproved().onLoaded { rows -> setState { copy(preApproved = rows) } }

                AdminDestination.ToolAvailability ->
                    repository.tools().onLoaded { rows -> setState { copy(tools = rows) } }

                // The grouping page wants the always-on tools too: one of them
                // still belongs to a group and can be moved.
                AdminDestination.ToolGroups -> loadGrouping()

                AdminDestination.ProductionName -> {
                    setState { copy(productionName = productionName()) }
                    ZillitResult.Success(Unit)
                }

                AdminDestination.CompanyDetails ->
                    repository.companyDetails().onLoaded { details -> setState { copy(company = details) } }

                AdminDestination.Watermark ->
                    repository.watermarkUrl().onLoaded { url -> setState { copy(watermarkUrl = url) } }

                AdminDestination.Sos ->
                    repository.sosRecipients().onLoaded { rows -> setState { copy(sos = rows) } }

                AdminDestination.HomeUnits -> loadUnits(UnitKind.Home)
                AdminDestination.RemoteUnits -> loadUnits(UnitKind.Remote)
                AdminDestination.ShootingUnits -> loadUnits(UnitKind.Shooting)

                AdminDestination.DeleteProduction -> {
                    setState { copy(productionName = productionName()) }
                    ZillitResult.Success(Unit)
                }
            }

            loaded += destination
            setState {
                copy(
                    isLoading = false,
                    hasLoaded = true,
                    // Whatever was listed stays. A dropped request is not
                    // evidence that a production lost its departments.
                    error = result.errorOrNull()?.readable,
                )
            }
        }
    }

    /** Both halves of the grouping page, and a partial answer is still one. */
    private suspend fun loadGrouping(): ZillitResult<Unit> {
        val groups = repository.toolGroups()
        val tools = repository.tools(includeAlwaysOn = true)

        groups.getOrNull()?.let { rows -> setState { copy(toolGroups = rows) } }
        tools.getOrNull()?.let { rows -> setState { copy(tools = rows) } }

        // Only a total failure is worth reporting: a group list with no tools
        // under it is still a page an admin can rename a group on.
        return if (groups is ZillitResult.Failure && tools is ZillitResult.Failure) {
            groups
        } else {
            ZillitResult.Success(Unit)
        }
    }

    private suspend fun loadUnits(kind: UnitKind): ZillitResult<Unit> =
        repository.units(kind).onLoaded { rows -> setState { copy(units = rows) } }

    private fun onFieldChanged(event: AdminEvent.FieldChanged) = setState {
        val updated = when (val form = form) {
            is AdminForm.Name -> form.copy(value = event.value, error = null)

            is AdminForm.ProductionName -> form.copy(value = event.value, error = null)

            is AdminForm.PreApproval -> when (event.field) {
                AdminField.FirstName -> form.copy(firstName = event.value)
                AdminField.LastName -> form.copy(lastName = event.value)
                AdminField.Email -> form.copy(email = event.value)
                AdminField.CountryCode -> form.copy(countryCode = event.value)
                AdminField.Phone -> form.copy(phone = event.value)
                // The job titles belong to the department, so the old one
                // cannot survive the move — keeping it would pre-approve
                // someone into a job their department does not have.
                AdminField.Department -> form.copy(departmentId = event.value, jobTitleId = null)
                AdminField.JobTitle -> form.copy(jobTitleId = event.value)
                AdminField.Name -> form
            }.copy(error = null)

            else -> form
        }
        copy(form = updated)
    }

    /**
     * Sends the open form.
     *
     * One place, because every form ends the same way: validate, call, reload,
     * close. What differs is one call, and that is the `when` below.
     */
    private fun submit() {
        when (val form = currentState.form) {
            is AdminForm.Name -> submitName(form)
            is AdminForm.PreApproval -> submitPreApproval(form)
            is AdminForm.Sos -> submitSos(form)
            is AdminForm.Company -> submitCompany(form)
            is AdminForm.ProductionName -> submitProductionName(form)
            null -> Unit
        }
    }

    @Suppress("CyclomaticComplexMethod") // Four kinds × create/rename; a lookup table.
    private fun submitName(form: AdminForm.Name) {
        if (!form.isValid) {
            setState {
                copy(
                    form = form.copy(
                        error = str(form.kind.nameTooShortKey),
                    ),
                )
            }
            return
        }

        val name = form.value.trim()
        val kind = form.kind
        val departmentId = currentState.selection.departmentId
        val unitKind = currentState.unitKind

        mutate(if (form.isRename) str(S.desktop_renamed_to, name) else str(S.desktop_name_added, name)) {
            when {
                kind == NameKind.Department -> repository.createDepartment(name)

                kind == NameKind.JobTitle && departmentId != null ->
                    repository.createJobTitle(departmentId, name)

                kind == NameKind.ToolGroup && form.targetId != null ->
                    repository.renameToolGroup(form.targetId, name)

                kind == NameKind.ToolGroup -> repository.createToolGroup(name)

                kind == NameKind.Unit && unitKind != null && form.targetId != null ->
                    repository.renameUnit(unitKind, form.targetId, name)

                kind == NameKind.Unit && unitKind != null -> repository.createUnit(unitKind, name)

                // Reachable only if a form is opened without the thing it names
                // being selected — a wiring mistake rather than user input.
                else -> ZillitResult.Failure(ZillitError.Validation(str(S.desktop_nothing_selected_to_add_to)))
            }
        }
    }

    private fun submitPreApproval(form: AdminForm.PreApproval) {
        if (!form.isValid) {
            setState {
                copy(
                    form = form.copy(
                        error = str(S.desktop_pre_approval_fields_needed),
                    ),
                )
            }
            return
        }

        // Both or neither. A number with no country code cannot be dialled from
        // a unit abroad, which is exactly when an SOS list matters.
        val code = form.countryCode.trim().takeIf { it.isNotBlank() }
        val phone = form.phone.trim().takeIf { it.isNotBlank() }
        if ((code == null) != (phone == null)) {
            setState { copy(form = form.copy(error = PHONE_PAIR_REQUIRED)) }
            return
        }

        mutate(str(S.desktop_can_now_join_with_code, form.firstName.trim())) {
            repository.addPreApproved(
                NewPreApproval(
                    firstName = form.firstName,
                    lastName = form.lastName,
                    departmentId = form.departmentId.orEmpty(),
                    designationId = form.jobTitleId.orEmpty(),
                    email = form.email.trim().takeIf { it.isNotBlank() },
                    countryCode = code,
                    phone = phone,
                ),
            )
        }
    }

    private fun submitSos(form: AdminForm.Sos) {
        if (!form.draft.isComplete) {
            setState {
                copy(
                    form = form.copy(
                        error = when (form.draft.entryType) {
                            SosEntryType.Crew -> str(S.desktop_choose_someone_on_crew)
                            SosEntryType.Outsider ->
                                str(S.desktop_outsider_fields_needed)
                        },
                    ),
                )
            }
            return
        }

        mutate(str(S.desktop_recipient_added)) { repository.addSosRecipient(form.draft) }
    }

    private fun submitCompany(form: AdminForm.Company) {
        val email = form.draft.email.trim()
        if (email.isNotBlank() && !email.looksLikeEmail) {
            setState { copy(form = form.copy(error = str(S.desktop_not_an_email_address))) }
            return
        }

        val code = form.draft.countryCode.trim()
        val phone = form.draft.phone.trim()
        if (code.isBlank() != phone.isBlank()) {
            setState { copy(form = form.copy(error = PHONE_PAIR_REQUIRED)) }
            return
        }
        if (phone.isNotBlank() && phone.length !in PHONE_LENGTH) {
            setState { copy(form = form.copy(error = str(S.desktop_phone_number_length))) }
            return
        }

        mutate(str(S.desktop_company_details_saved)) { repository.saveCompanyDetails(form.draft) }
    }

    private fun submitProductionName(form: AdminForm.ProductionName) {
        if (!form.isValid) {
            setState { copy(form = form.copy(error = str(S.desktop_project_name_length))) }
            return
        }

        val name = form.value.trim()
        mutate(str(S.desktop_renamed_to, name)) {
            repository.renameProduction(name).also { result ->
                // The name is on the window chrome and the rail; the rest of
                // the app has to be told rather than left to notice.
                if (result is ZillitResult.Success) sendEffect(AdminEffect.ProductionChanged)
            }
        }
    }

    /**
     * Opens a department's job titles.
     *
     * Null closes it. On the ordering page the same selection marks which row
     * the move buttons act on, which is why this is one event rather than two.
     */
    private fun selectDepartment(departmentId: String?) =
        setState { copy(selection = selection.copy(departmentId = departmentId)) }

    /**
     * Opens one person's rights, and reads them.
     *
     * Read on selection rather than with the crew list: a production has forty
     * people and an admin opens one of them, so forty rights calls at load time
     * would be thirty-nine nobody asked for.
     */
    private fun selectCrew(userId: String?) {
        setState { copy(selection = selection.copy(userId = userId, rights = emptyList())) }
        val resolved = userId ?: return

        setState { copy(selection = selection.copy(isLoadingRights = true)) }
        launch {
            when (val result = repository.rights(resolved)) {
                is ZillitResult.Success -> setState {
                    copy(selection = selection.copy(rights = result.data, isLoadingRights = false))
                }

                is ZillitResult.Failure -> setState {
                    copy(
                        selection = selection.copy(isLoadingRights = false),
                        error = result.error.readable,
                    )
                }
            }
        }
    }

    /** Granting asks first; revoking does not. Both phone clients agree. */
    private fun onAdminAccessChanged(event: AdminEvent.AdminAccessChanged) {
        val person = currentState.crew.firstOrNull { it.userId == event.userId } ?: return
        if (event.isAdmin) {
            setState { copy(confirming = AdminConfirmation.GrantAdmin(person.userId, person.fullName)) }
        } else {
            mutate(str(S.desktop_no_longer_administrator, person.fullName)) {
                repository.setAdminAccess(person.userId, false)
            }
        }
    }

    /** Removing asks first; putting someone back does not. */
    private fun onCrewActiveChanged(event: AdminEvent.CrewActiveChanged) {
        val person = currentState.crew.firstOrNull { it.userId == event.userId } ?: return
        val device = person.deviceId ?: return

        if (event.isActive) {
            mutate(str(S.desktop_back_on_the_project, person.fullName)) {
                repository.setCrewStatus(person.userId, device, CrewStatus.Accepted)
            }
        } else {
            setState {
                copy(confirming = AdminConfirmation.RemoveFromCrew(person.userId, device, person.fullName))
            }
        }
    }

    /**
     * One click, however many calls it takes.
     *
     * Downloading implies viewing and clearing viewing clears the rest, and the
     * server does neither — see [cascadeFrom]. The calls run in order and stop
     * at the first failure, then the rights are re-read: a half-applied cascade
     * is exactly the state the screen must not guess at.
     */
    private fun onRightsToggled(event: AdminEvent.RightsToggled) {
        val userId = currentState.selection.userId ?: return
        val current = currentState.selection.rights.firstOrNull {
            it.toolIdentifier == event.toggle.toolIdentifier && it.section == event.toggle.section
        } ?: return
        val unitId = current.unitId ?: return
        if (current.locked(event.toggle.access)) return

        val changes = RightsChange(
            userId = userId,
            unitId = unitId,
            section = event.toggle.section,
            access = event.toggle.access,
            enable = event.toggle.enable,
        ).cascadeFrom(current)

        setState { copy(isSaving = true, error = null) }
        launch {
            val failure = changes.firstNotNullOfOrNull { change ->
                (repository.changeRights(change) as? ZillitResult.Failure)?.error
            }
            setState { copy(isSaving = false, error = failure?.readable) }
            // Re-read either way. On success it confirms what the server made
            // of the cascade; on failure it is the only way to know how far it
            // got.
            selectCrew(userId)
        }
    }

    private fun saveOrder() {
        val order = currentState.selection.order
        if (order.isEmpty()) return

        mutate(str(S.desktop_crew_list_order_saved)) {
            repository.reorderDepartments(order.map { it.id })
        }
    }

    /**
     * Does the confirmed thing.
     *
     * The dialog closes first: leaving it up behind a spinner makes a slow call
     * look like a click that missed, and the second click is the one that gets
     * reported as "it deleted two".
     */
    @Suppress("CyclomaticComplexMethod") // One branch per destructive action.
    private fun confirm() {
        val confirmation = currentState.confirming ?: return
        setState { copy(confirming = null) }

        when (confirmation) {
            is AdminConfirmation.RemoveDepartment ->
                mutate(str(S.desktop_name_deleted, confirmation.name)) {
                    repository.deleteDepartment(confirmation.id)
                }

            is AdminConfirmation.RemoveJobTitle ->
                mutate(str(S.desktop_name_deleted, confirmation.name)) {
                    repository.deleteJobTitle(confirmation.departmentId, confirmation.id)
                }

            is AdminConfirmation.RemoveToolGroup ->
                mutate(str(S.desktop_name_deleted, confirmation.name)) {
                    repository.deleteToolGroup(confirmation.id)
                }

            is AdminConfirmation.RemoveUnit ->
                mutate(str(S.desktop_name_deleted, confirmation.name)) {
                    repository.deleteUnit(confirmation.kind, confirmation.id)
                }

            is AdminConfirmation.RemoveSos ->
                mutate(str(S.desktop_name_removed, confirmation.name)) {
                    repository.removeSosRecipient(confirmation.id)
                }

            is AdminConfirmation.RemoveFromCrew ->
                mutate(str(S.desktop_is_off_the_project, confirmation.name)) {
                    repository.setCrewStatus(confirmation.userId, confirmation.deviceId, CrewStatus.Removed)
                }

            is AdminConfirmation.GrantAdmin ->
                mutate(str(S.desktop_can_now_administer, confirmation.name)) {
                    repository.setAdminAccess(confirmation.userId, true)
                }

            is AdminConfirmation.ClearWatermark ->
                mutate(str(S.desktop_watermark_removed)) { repository.clearWatermark() }

            is AdminConfirmation.ClearCompanyLogo ->
                mutate(str(S.desktop_company_logo_removed)) { repository.clearCompanyLogo() }

            is AdminConfirmation.DeleteProduction ->
                mutate(str(S.desktop_deletion_scheduled_in_hours, confirmation.hours)) {
                    repository.scheduleDeletion(confirmation.hours).also { result ->
                        if (result is ZillitResult.Success) {
                            setState {
                                copy(
                                    deletion = DeletionSchedule(
                                        isScheduled = true,
                                        hours = confirmation.hours,
                                    ),
                                )
                            }
                        }
                    }
                }
        }
    }

    /**
     * Runs a change, then re-reads the page.
     *
     * The single path every mutation takes. The reload is not optional — see
     * the class note on why nothing here is applied optimistically — and the
     * form closes only on success, so a rejected name is still in the box to be
     * corrected rather than retyped.
     */
    private fun mutate(success: String, block: suspend () -> ZillitResult<Unit>) {
        if (!isAdmin()) {
            setState { copy(error = str(S.desktop_only_admin_can_change)) }
            return
        }
        if (currentState.isSaving) return
        setState { copy(isSaving = true, error = null) }

        launch {
            when (val result = block()) {
                is ZillitResult.Success -> {
                    setState { copy(isSaving = false, outcome = success, form = null) }
                    load(currentState.destination)
                }

                is ZillitResult.Failure -> setState {
                    copy(
                        isSaving = false,
                        // On the form when there is one, so a rejected name is
                        // reported where the name is rather than behind the
                        // dialog.
                        error = if (form == null) result.error.readable else null,
                        form = form.withError(result.error.readable),
                    )
                }
            }
        }
    }

    /**
     * Applies a loaded list and forgets its type.
     *
     * The loader's `when` has to produce one type across sixteen branches that
     * each read something different; this is what makes each branch a
     * one-liner instead of a five-line `when (result)`.
     */
    private fun <T> ZillitResult<T>.onLoaded(apply: (T) -> Unit): ZillitResult<Unit> = when (this) {
        is ZillitResult.Success -> ZillitResult.Success(apply(data))
        is ZillitResult.Failure -> this
    }

    private companion object {
        val PHONE_LENGTH = 5..20

        /** Said the same way on both forms that take a number. */
        val PHONE_PAIR_REQUIRED: String get() = str(S.desktop_phone_pair_required)
    }
}

/** One-shot things the administration pages ask the rest of the app for. */
sealed interface AdminEffect {
    /**
     * The production record changed under everyone.
     *
     * Renaming it is the case: the name is on the window chrome, the rail and
     * every tab title, and none of those are reading this view model.
     */
    data object ProductionChanged : AdminEffect
}

/**
 * Puts an error on whichever form is open.
 *
 * A `when` rather than an interface member so [AdminForm] stays plain data — it
 * is asserted on directly in tests, and a form that could carry behaviour is a
 * form that will.
 */
private fun AdminForm?.withError(message: String): AdminForm? = when (this) {
    is AdminForm.Name -> copy(error = message)
    is AdminForm.PreApproval -> copy(error = message)
    is AdminForm.Sos -> copy(error = message)
    is AdminForm.Company -> copy(error = message)
    is AdminForm.ProductionName -> copy(error = message)
    null -> null
}

/**
 * The server's word for what went wrong, in the reader's language.
 *
 * These routes answer with translation keys — `department_is_in_use`,
 * `tool_group_in_use` — and showing one raw is showing a reader the database's
 * vocabulary. [localised] resolves what it can and passes anything else
 * through, which is the right failure: an unresolved key still names the
 * problem.
 */
private val ZillitError.readable: String get() = userMessage.localised()

/** Good enough to catch a typo, and no stricter. Addresses are validated by use. */
private val String.looksLikeEmail: Boolean
    get() = matches(Regex("[^@\\s]+@[^@\\s]+\\.[^@\\s]+"))
