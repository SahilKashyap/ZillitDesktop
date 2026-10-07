package com.zillit.desktop.feature.settings.admin.ui

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.mvvm.ZillitViewModel
import com.zillit.desktop.core.socket.SocketEventBus
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.settings.account.allowsPrivateName
import com.zillit.desktop.feature.settings.admin.data.ADMIN_SYNC_EVENTS
import com.zillit.desktop.feature.settings.admin.data.ADMIN_SYNC_PAGES
import com.zillit.desktop.feature.settings.admin.domain.AdminRepository
import com.zillit.desktop.feature.settings.admin.domain.CrewMember
import com.zillit.desktop.feature.settings.admin.domain.CrewProfileChange
import com.zillit.desktop.feature.settings.admin.domain.CrewStatus
import com.zillit.desktop.feature.settings.admin.domain.DeletionSchedule
import com.zillit.desktop.feature.settings.admin.domain.NewPreApproval
import com.zillit.desktop.feature.settings.admin.domain.NewSosRecipient
import com.zillit.desktop.feature.settings.admin.domain.SosEntryType
import com.zillit.desktop.feature.settings.admin.domain.UnitKind
import com.zillit.desktop.feature.settings.admin.domain.rightsCascade

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
     * Whether this production already has a deletion counting down.
     *
     * Read from the open production's own `mark_deleted`, so the delete page
     * opens honest after a restart, or when another admin scheduled it. Without
     * it the page always opened offering the three delays — an admin who had
     * already scheduled a deletion saw no sign of it and no way to call it off,
     * which reads exactly like the deletion never happened.
     *
     * A lambda for the same reason as [productionName]: the graph outlives a
     * production switch.
     */
    private val markedForDeletion: () -> Boolean = { false },
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
    /**
     * The admin at the keyboard. User Management leaves them off its list, as
     * the web does — an admin cannot switch off their own access or rights.
     */
    private val selfUserId: () -> String? = { null },
) : ZillitViewModel<AdminUiState, AdminEvent, AdminEffect>(AdminUiState()) {

    /** The File Cabinet page's logic, kept out of this class — see [FileCabinetController]. */
    private val cabinet = FileCabinetController(
        repository, isAdmin, { currentState }, ::setState, { launch { it() } },
    ) { load(AdminDestination.FileCabinet) }

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

            is AdminEvent.Cabinet -> cabinet.onEvent(event)

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

            is AdminEvent.AdminAccessChanged -> onAdminAccessChanged(event)
            is AdminEvent.CrewActiveChanged -> onCrewActiveChanged(event)

            is AdminEvent.OpenEditCrew -> openEditCrew(event)
            is AdminEvent.EditCrewChanged -> setState {
                copy(form = (form as? AdminForm.EditCrew)?.let { event.draft.copy(error = null) } ?: form)
            }
            is AdminEvent.OpenPostingRights -> openPostingRights(event.userId)
            is AdminEvent.RightsSectionChanged -> setState {
                copy(form = (form as? AdminForm.PostingRights)?.copy(section = event.section, error = null) ?: form)
            }
            is AdminEvent.RightToggled -> onRightToggled(event)
            is AdminEvent.OpenAllowChat -> openAllowChat(event.userId)
            is AdminEvent.AllowChatChanged -> setState {
                copy(form = (form as? AdminForm.AllowChat)?.let { event.draft.copy(error = null) } ?: form)
            }

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

            // Clears the schedule here as well as on the server: `mutate`
            // reloads the page on success, and the delete page's reload keeps
            // whatever it already held rather than refetching. Without this the
            // notice and its "call it off" button survived the call that called
            // it off.
            AdminEvent.CancelDeletion -> mutate(str(S.desktop_deletion_called_off)) {
                repository.cancelDeletion().also { result ->
                    if (result is ZillitResult.Success) setState { copy(deletion = DeletionSchedule()) }
                }
            }
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
                selfUserId = selfUserId(),
                query = "",
                error = null,
                outcome = null,
                form = null,
                confirming = null,
                selection = AdminSelection(),
                hasLoaded = destination in loaded,
                // Read on every visit, not once: a deletion may have been
                // scheduled before this app was started, or by another admin
                // since the page was last looked at. `load` is where the other
                // pages read themselves, but it is skipped for a page already
                // read — and it also runs after this screen's own writes,
                // where it would answer a deletion scheduled a second ago with
                // the production snapshot from before it.
                deletion = if (destination == AdminDestination.DeleteProduction) {
                    DeletionSchedule(isScheduled = markedForDeletion())
                } else {
                    deletion
                },
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

                AdminDestination.Crew -> loadCrew()

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
                AdminDestination.FileCabinet -> cabinet.load()
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

    /**
     * User Management: the people, plus what Change Profile picks from — the
     * department tree and the shooting units, both read on arrival as the web
     * does. Only the crew list failing fails the page; a picker with nothing
     * in it says so when it is opened.
     */
    private suspend fun loadCrew(): ZillitResult<Unit> {
        repository.departments().getOrNull()?.let { rows -> setState { copy(departments = rows) } }
        repository.units(UnitKind.Shooting).getOrNull()?.let { rows -> setState { copy(joinUnits = rows) } }
        return repository.crew().onLoaded { rows -> setState { copy(crew = rows) } }
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
            is AdminForm.EditCrew -> submitEditCrew(form)
            is AdminForm.AllowChat -> submitAllowChat(form)
            // Each switch writes as it is thrown; there is nothing to submit.
            is AdminForm.PostingRights -> Unit
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

    /** Granting asks first; revoking does not. All three clients agree. */
    private fun onAdminAccessChanged(event: AdminEvent.AdminAccessChanged) {
        val person = activePerson(event.userId) ?: return
        if (event.isAdmin) {
            setState {
                copy(
                    confirming = AdminConfirmation.GrantAdmin(
                        userId = person.userId,
                        name = person.fullName,
                        designation = person.designation,
                        department = person.department,
                    ),
                )
            }
        } else {
            mutate(str(S.desktop_no_longer_administrator, person.fullName)) {
                repository.setAdminAccess(person.userId, false)
            }
        }
    }

    /**
     * Either way at once, as the web's Active switch does — `accepted` and
     * `removed` are each other's undo, one click apart on the same row.
     */
    private fun onCrewActiveChanged(event: AdminEvent.CrewActiveChanged) {
        val person = currentState.crew.firstOrNull { it.userId == event.userId } ?: return
        val (status, said) = if (event.isActive) {
            CrewStatus.Accepted to str(S.desktop_back_on_the_project, person.fullName)
        } else {
            CrewStatus.Removed to str(S.desktop_is_off_the_project, person.fullName)
        }
        mutate(said) { repository.setCrewStatus(person.userId, person.deviceId, status) }
    }

    /**
     * The person a User Management action is for — or null, with the web's
     * warning, when they have been switched off: only Active works on them.
     */
    private fun activePerson(userId: String): CrewMember? {
        val person = currentState.crew.firstOrNull { it.userId == userId } ?: return null
        if (person.isRemoved) {
            setState { copy(error = str(S.you_can_not_perform_this_action_msg)) }
            return null
        }
        return person
    }

    /**
     * Change Profile, opened on where the person is now. The web picks by
     * name, so the ids are found by matching the row's department, designation
     * and unit names against the department tree and the unit list.
     */
    private fun openEditCrew(event: AdminEvent.OpenEditCrew) {
        val person = activePerson(event.userId) ?: return
        val state = currentState
        val department = state.departments.firstOrNull { it.name == person.department }
        val designation = department?.jobTitles?.firstOrNull { it.name == person.designation }
        val unit = state.joinUnits.firstOrNull { it.name == person.joinUnitName }
        setState {
            copy(
                form = AdminForm.EditCrew(
                    userId = person.userId,
                    name = person.fullName,
                    departmentId = department?.id,
                    designationId = designation?.id,
                    unitId = unit?.id,
                    keepNamePrivate = person.keepNamePrivate,
                    withUnit = event.withUnit,
                ),
            )
        }
    }

    /** The web's three required fields, each with its own message, then one `PUT`. */
    private fun submitEditCrew(form: AdminForm.EditCrew) {
        val department = currentState.departments.firstOrNull { it.id == form.departmentId }
        val designation = department?.jobTitles?.firstOrNull { it.id == form.designationId }
        val missing = when {
            department == null -> str(S.desktop_um_select_department_required)
            designation == null -> str(S.desktop_um_select_designation_required)
            form.withUnit && form.unitId == null -> str(S.please_select_unit)
            else -> null
        }
        if (missing != null || department == null || designation == null) {
            setState { copy(form = form.copy(error = missing)) }
            return
        }
        val change = CrewProfileChange(
            userId = form.userId,
            departmentId = department.id,
            designationId = designation.id,
            joinUnitId = form.unitId.takeIf { form.withUnit },
            // Only the three designations that may hide a name keep the switch;
            // moving someone off one of them shows their name again.
            keepNamePrivate = form.keepNamePrivate && allowsPrivateName(designation.name),
        )
        mutate(str(S.desktop_um_updated)) { repository.updateCrewProfile(change) }
    }

    private fun openPostingRights(userId: String) {
        val person = activePerson(userId) ?: return
        setState { copy(form = AdminForm.PostingRights(userId = person.userId)) }
        launch { reloadRights(person.userId) }
    }

    /**
     * Re-reads the dialog's rows, sorted by the name shown as the web sorts
     * them. Dropped if the dialog was closed or moved to someone else meanwhile.
     */
    private suspend fun reloadRights(userId: String, error: String? = null) {
        val result = repository.rights(userId)
        setState {
            val open = form as? AdminForm.PostingRights
            if (open?.userId != userId) return@setState this
            copy(
                form = when (result) {
                    is ZillitResult.Success -> open.copy(
                        rows = result.data.sortedBy { it.name.localised().lowercase() },
                        isLoading = false,
                        busy = emptySet(),
                        error = error,
                    )
                    is ZillitResult.Failure -> open.copy(
                        isLoading = false,
                        busy = emptySet(),
                        error = error ?: result.error.readable,
                    )
                },
            )
        }
    }

    /**
     * One switch in the Posting Rights dialog: painted at once, then every
     * write the web sends for it ([rightsCascade]) in order, stopping at the
     * first refusal, then a fresh read so the rows show what the server kept.
     */
    private fun onRightToggled(event: AdminEvent.RightToggled) {
        val open = currentState.form as? AdminForm.PostingRights ?: return
        val row = open.shown.firstOrNull { it.unitId == event.unitId } ?: return
        val key = event.unitId + event.access.wire
        if (key in open.busy || !row.updatable(event.access)) return
        if (!isAdmin()) {
            setState { copy(form = open.copy(error = str(S.desktop_only_admin_can_change))) }
            return
        }

        val writes = rightsCascade(row, event.access, event.enable, open.shown)
        setState {
            copy(
                form = open.copy(
                    rows = open.rows.map { candidate ->
                        if (candidate.section != open.section) return@map candidate
                        writes.filter { it.unitId == candidate.unitId }
                            .fold(candidate) { acc, write -> acc.with(write.access, write.enable) }
                    },
                    busy = open.busy + key,
                    error = null,
                ),
            )
        }
        launch {
            val refusal = writes.firstNotNullOfOrNull { write ->
                repository.writeRight(open.userId, open.section, write).errorOrNull()
            }
            reloadRights(open.userId, refusal?.readable)
        }
    }

    /** The people a private-name crew member may chat with, read when the dialog opens. */
    private fun openAllowChat(userId: String) {
        val person = activePerson(userId) ?: return
        if (!person.keepNamePrivate) return
        setState { copy(form = AdminForm.AllowChat(userId = person.userId)) }
        launch {
            val result = repository.chatAllowList(person.userId)
            setState {
                val open = form as? AdminForm.AllowChat
                if (open?.userId != person.userId) return@setState this
                copy(
                    form = when (result) {
                        is ZillitResult.Success -> open.copy(selected = result.data.toSet(), isLoading = false)
                        is ZillitResult.Failure -> open.copy(isLoading = false, error = result.error.readable)
                    },
                )
            }
        }
    }

    private fun submitAllowChat(form: AdminForm.AllowChat) {
        mutate(str(S.desktop_um_updated)) { repository.setChatAllowList(form.userId, form.selected.toList()) }
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
    is AdminForm.EditCrew -> copy(error = message)
    is AdminForm.PostingRights -> copy(error = message)
    is AdminForm.AllowChat -> copy(error = message)
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
internal val ZillitError.readable: String get() = userMessage.localised()

/** Good enough to catch a typo, and no stricter. Addresses are validated by use. */
private val String.looksLikeEmail: Boolean
    get() = matches(Regex("[^@\\s]+@[^@\\s]+\\.[^@\\s]+"))
