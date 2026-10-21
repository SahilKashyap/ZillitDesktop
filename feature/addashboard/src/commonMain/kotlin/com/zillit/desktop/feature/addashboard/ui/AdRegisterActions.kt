package com.zillit.desktop.feature.addashboard.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.addashboard.data.AGENCY_LIST_EXTENSIONS
import com.zillit.desktop.feature.addashboard.domain.ArtisteDraft
import com.zillit.desktop.feature.addashboard.domain.ArtisteStatus
import com.zillit.desktop.feature.addashboard.domain.BankDetails
import com.zillit.desktop.feature.addashboard.domain.ArtisteDocument

/**
 * The Artiste Register, and one artiste's own page.
 *
 * Every mutating handler re-checks the posting right: the screen hiding a
 * button is not the gate, and the web was caught once with a control that was
 * hidden but still reachable.
 */
@Suppress("TooManyFunctions") // One handler per action on an artiste.
internal class RegisterActions(private val vm: AdViewModel) {

    @Suppress("CyclomaticComplexMethod") // A dispatch table; one line per event.
    fun on(event: RegisterEvent) {
        when (event) {
            is RegisterEvent.Search -> vm.update { copy(register = register.copy(search = event.text)) }
            is RegisterEvent.FilterCategory ->
                vm.update { copy(register = register.copy(category = event.category)) }

            is RegisterEvent.FilterStatus ->
                vm.update { copy(register = register.copy(status = event.status)) }

            is RegisterEvent.FilterEngagement ->
                vm.update { copy(register = register.copy(engagement = event.engagement)) }

            is RegisterEvent.UnionOnly -> vm.update { copy(register = register.copy(unionOnly = event.on)) }
            is RegisterEvent.SeeArtiste -> vm.readEntity(AdBadges.REGISTER, event.id)
            is RegisterEvent.OpenArtiste -> openArtiste(event.id)
            RegisterEvent.CloseArtiste -> vm.update { copy(artisteDetail = null) }
            is RegisterEvent.AskDelete -> askDelete(event)
            RegisterEvent.ConfirmDelete -> confirmDelete()
            RegisterEvent.CancelDelete -> vm.update { copy(register = register.copy(confirmDelete = null)) }
            is RegisterEvent.Verify -> verify(event.id)
            RegisterEvent.AskBlock -> vm.posting {
                vm.update { copy(artisteDetail = artisteDetail?.copy(confirmBlock = true)) }
            }

            is RegisterEvent.BlockReason ->
                vm.update { copy(artisteDetail = artisteDetail?.copy(blockReason = event.text)) }

            RegisterEvent.ConfirmBlock -> confirmBlock()
            RegisterEvent.CancelBlock -> vm.update {
                copy(artisteDetail = artisteDetail?.copy(confirmBlock = false, blockReason = ""))
            }

            is RegisterEvent.Unblock -> unblock(event.id)
            RegisterEvent.CopyPortalLink -> copyPortalLink()
        }
    }

    /**
     * Opening a row reads that one artiste's updates.
     *
     * Per item, not per tab: opening the register used to clear every dot at
     * once, and the sidebar's rollup then counted nothing.
     */
    private fun openArtiste(id: String) {
        vm.readEntity(AdBadges.REGISTER, id)
        val known = vm.current.artistes.firstOrNull { it.id == id }
        if (known != null) vm.update { copy(artisteDetail = ArtisteDetailState(artiste = known)) }
        vm.run {
            when (val fresh = vm.repo.artiste(id)) {
                is ZillitResult.Success -> vm.update {
                    copy(artisteDetail = (artisteDetail ?: ArtisteDetailState(fresh.data)).copy(artiste = fresh.data))
                }

                is ZillitResult.Failure -> if (known == null) vm.report(fresh.error)
            }
        }
    }

    private fun askDelete(event: RegisterEvent.AskDelete) = vm.posting {
        // A verified artiste cannot be deleted: they have been through
        // onboarding and may already carry signed days.
        if (!event.artiste.deletable) {
            vm.fail(str(S.desktop_ad_verified_not_deletable))
            return@posting
        }
        vm.update { copy(register = register.copy(confirmDelete = event.artiste)) }
    }

    /** The delete cascades to the artiste's days, so the day list reloads too. */
    private fun confirmDelete() {
        val target = vm.current.register.confirmDelete ?: return
        // Re-checked here, not only where the control is drawn: a dialog can
        // be reached by a stale click, and a hidden button is not a gate.
        if (!vm.current.viewer.canPost) {
            vm.update { copy(register = register.copy(confirmDelete = null)) }
            vm.askForPostingRights()
            return
        }
        if (!target.deletable) {
            vm.update { copy(register = register.copy(confirmDelete = null)) }
            vm.fail(str(S.desktop_ad_verified_not_deletable))
            return
        }
        vm.update { copy(register = register.copy(deleting = true)) }
        vm.run {
            when (val result = vm.repo.deleteArtiste(target.id)) {
                is ZillitResult.Success -> {
                    vm.update {
                        copy(
                            register = register.copy(confirmDelete = null, deleting = false),
                            artisteDetail = null,
                            notice = str(S.desktop_ad_artiste_deleted, target.name),
                        )
                    }
                    vm.loadRegister()
                    vm.loadDay()
                }

                is ZillitResult.Failure -> {
                    vm.update { copy(register = register.copy(deleting = false)) }
                    vm.report(result.error)
                }
            }
        }
    }

    private fun verify(id: String) = act(str(S.desktop_ad_artiste_verified)) { vm.repo.verify(id) }

    private fun confirmBlock() {
        val open = vm.current.artisteDetail ?: return
        // Same rule as the delete: the handler checks, not the screen.
        if (!vm.current.viewer.canPost) {
            vm.update { copy(artisteDetail = artisteDetail?.copy(confirmBlock = false)) }
            vm.askForPostingRights()
            return
        }
        val reason = open.blockReason.takeIf { it.isNotBlank() }
        vm.update { copy(artisteDetail = artisteDetail?.copy(busy = true)) }
        vm.run {
            when (val result = vm.repo.block(open.artiste.id, reason)) {
                is ZillitResult.Success -> {
                    vm.update {
                        copy(
                            artisteDetail = artisteDetail?.copy(
                                busy = false,
                                confirmBlock = false,
                                blockReason = "",
                            ),
                            notice = str(S.desktop_ad_artiste_blocked, open.artiste.name),
                        )
                    }
                    reloadOpen(open.artiste.id)
                }

                is ZillitResult.Failure -> {
                    vm.update { copy(artisteDetail = artisteDetail?.copy(busy = false)) }
                    vm.report(result.error)
                }
            }
        }
    }

    private fun unblock(id: String) = act(str(S.desktop_ad_artiste_unblocked)) { vm.repo.unblock(id) }

    /**
     * Mints the artiste's portal token and puts the link on the clipboard.
     *
     * The app never e-mails it — the AD sends it however they send things.
     */
    private fun copyPortalLink() {
        val open = vm.current.artisteDetail ?: return
        vm.posting {
            vm.update { copy(artisteDetail = artisteDetail?.copy(busy = true)) }
            vm.run {
                when (val token = vm.repo.portalLink(open.artiste.id)) {
                    is ZillitResult.Success -> {
                        val link = "${vm.machine.portalOrigin()}/extras-portal/${token.data}"
                        vm.machine.copyToClipboard(link)
                        vm.update {
                            copy(
                                artisteDetail = artisteDetail?.copy(busy = false, portalLink = link),
                                notice = str(S.desktop_ad_portal_link_copied),
                            )
                        }
                        vm.emit(AdEffect.Copied(link))
                    }

                    is ZillitResult.Failure -> {
                        vm.update { copy(artisteDetail = artisteDetail?.copy(busy = false)) }
                        vm.report(token.error)
                    }
                }
            }
        }
    }

    private fun reloadOpen(id: String) {
        vm.loadRegister()
        vm.run {
            (vm.repo.artiste(id) as? ZillitResult.Success)?.let { fresh ->
                vm.update { copy(artisteDetail = artisteDetail?.copy(artiste = fresh.data)) }
            }
        }
    }

    private fun act(success: String, call: suspend () -> ZillitResult<Unit>) = vm.posting {
        val openId = vm.current.artisteDetail?.artiste?.id
        vm.update { copy(artisteDetail = artisteDetail?.copy(busy = true)) }
        vm.run {
            when (val result = call()) {
                is ZillitResult.Success -> {
                    vm.update { copy(artisteDetail = artisteDetail?.copy(busy = false), notice = success) }
                    openId?.let(::reloadOpen) ?: vm.loadRegister()
                }

                is ZillitResult.Failure -> {
                    vm.update { copy(artisteDetail = artisteDetail?.copy(busy = false)) }
                    vm.report(result.error)
                }
            }
        }
    }
}

/**
 * The artiste form — Manual Entry, and editing a record that exists.
 *
 * Only identity is required here. The e-mail is format-checked but not
 * demanded, which is the web's rule and its known weakness: an external
 * artiste saved with no address has a dead portal link until somebody adds
 * one, because the code can only go to the address on file.
 */
@Suppress("TooManyFunctions") // One handler per field; a form is fields.
internal class ArtisteFormActions(private val vm: AdViewModel) {

    @Suppress("CyclomaticComplexMethod", "LongMethod") // A dispatch table; one line per field.
    fun on(event: FormEvent) {
        when (event) {
            is FormEvent.Open -> open(event.artisteId)
            FormEvent.Close -> vm.update { copy(artisteForm = null) }
            is FormEvent.External -> patch { copy(isExternal = event.on, userId = "") }
            is FormEvent.CrewSearch -> vm.update { copy(artisteForm = artisteForm?.copy(crewSearch = event.text)) }
            is FormEvent.PickCrew -> patch { copy(userId = event.userId, isExternal = false) }
            is FormEvent.FirstName -> patch { copy(firstName = event.text) }
            is FormEvent.LastName -> patch { copy(lastName = event.text) }
            is FormEvent.Email -> patch { copy(email = event.text) }
            is FormEvent.Phone -> patch { copy(phone = event.text) }
            is FormEvent.DateOfBirth -> patch { copy(dateOfBirth = event.text) }
            is FormEvent.Address -> patch { copy(address = event.text) }
            is FormEvent.Category -> patch { copy(category = event.category) }
            is FormEvent.Engagement -> patch { copy(engagement = event.engagement) }
            is FormEvent.Union -> patch { copy(union = event.name) }
            is FormEvent.PickAgency -> patch { copy(agencyId = event.agencyId, agencyName = event.name) }
            is FormEvent.Minor -> patch { copy(isMinor = event.on) }
            is FormEvent.RefNumber -> patch { copy(refNumber = event.text) }
            is FormEvent.Bank -> patch { copy(bank = bank.with(event.field, event.text)) }
            is FormEvent.DocumentLabel -> patchDocument(event.index) { copy(label = event.text) }
            is FormEvent.DocumentDescription -> patchDocument(event.index) { copy(description = event.text) }
            FormEvent.AddDocument -> patch { copy(documents = documents + ArtisteDocument()) }
            is FormEvent.RemoveDocument -> patch {
                copy(documents = documents.filterIndexed { index, _ -> index != event.index })
            }

            is FormEvent.Save -> save(event.asDraft)
            FormEvent.Promote -> promote()
        }
    }

    /**
     * Opens the form, and loads the three catalogues it offers.
     *
     * The crew list is filtered to the three artiste groups: the Stand-Ins and
     * Stunts departments, plus the Supporting Cast designation. Anyone else is
     * onboarded with the external toggle.
     */
    private fun open(artisteId: String?) = vm.posting {
        val existing = artisteId?.let { id -> vm.current.artistes.firstOrNull { it.id == id } }
        vm.update {
            copy(
                artisteForm = ArtisteFormState(
                    artisteId = artisteId,
                    draft = existing?.asDraft() ?: ArtisteDraft(),
                    eligible = vm.machine.crew().filter { it.artisteEligible },
                ),
            )
        }
        vm.run {
            (vm.repo.agencyVendors() as? ZillitResult.Success)?.let { rows ->
                vm.update { copy(artisteForm = artisteForm?.copy(agencies = rows.data)) }
            }
            (vm.repo.unionList() as? ZillitResult.Success)?.let { rows ->
                vm.update { copy(artisteForm = artisteForm?.copy(unions = rows.data)) }
            }
            // An edit loads the full record: the register's row carries no
            // bank details and no document list.
            if (artisteId != null) {
                (vm.repo.artiste(artisteId) as? ZillitResult.Success)?.let { fresh ->
                    vm.update { copy(artisteForm = artisteForm?.copy(draft = fresh.data.asDraft())) }
                }
            }
        }
    }

    private fun save(asDraft: Boolean) {
        val open = vm.current.artisteForm ?: return
        if (!open.draft.identityComplete) {
            vm.update { copy(artisteForm = artisteForm?.copy(error = str(S.desktop_ad_identity_required))) }
            return
        }
        if (!open.draft.emailValid) {
            vm.update { copy(artisteForm = artisteForm?.copy(error = str(S.desktop_ad_email_invalid))) }
            return
        }
        val draft = open.draft.copy(
            status = if (asDraft) ArtisteStatus.Draft else ArtisteStatus.Pending,
        )
        vm.update { copy(artisteForm = artisteForm?.copy(saving = true, error = null)) }
        vm.run {
            val result = if (open.artisteId == null) {
                when (val created = vm.repo.createArtiste(draft)) {
                    is ZillitResult.Success -> ZillitResult.Success(Unit)
                    is ZillitResult.Failure -> ZillitResult.Failure(created.error)
                }
            } else {
                vm.repo.updateArtiste(open.artisteId, draft)
            }
            when (result) {
                is ZillitResult.Success -> {
                    vm.update {
                        copy(
                            artisteForm = null,
                            // The web's own toast says "added to today's list",
                            // but the call is register-only — so this one says
                            // what actually happened.
                            notice = if (asDraft) {
                                str(S.ah_draft_saved_msg)
                            } else {
                                str(S.desktop_ad_artiste_added_register)
                            },
                        )
                    }
                    vm.loadRegister()
                }

                is ZillitResult.Failure -> {
                    vm.update { copy(artisteForm = artisteForm?.copy(saving = false)) }
                    vm.report(result.error)
                }
            }
        }
    }

    /** A draft artiste's **Add artiste**: the same record, PATCHed to pending. */
    private fun promote() {
        val open = vm.current.artisteForm ?: return
        val id = open.artisteId ?: return
        vm.update { copy(artisteForm = artisteForm?.copy(saving = true)) }
        vm.run {
            when (val result = vm.repo.updateArtiste(id, open.draft.copy(status = ArtisteStatus.Pending))) {
                is ZillitResult.Success -> {
                    vm.update { copy(artisteForm = null, notice = str(S.desktop_ad_artiste_added_register)) }
                    vm.loadRegister()
                }

                is ZillitResult.Failure -> {
                    vm.update { copy(artisteForm = artisteForm?.copy(saving = false)) }
                    vm.report(result.error)
                }
            }
        }
    }

    private fun patch(change: ArtisteDraft.() -> ArtisteDraft) = vm.update {
        copy(artisteForm = artisteForm?.let { form -> form.copy(draft = form.draft.change(), error = null) })
    }

    private fun patchDocument(index: Int, change: ArtisteDocument.() -> ArtisteDocument) = patch {
        copy(
            documents = documents.mapIndexed { position, document ->
                if (position == index) document.change() else document
            },
        )
    }
}

/** An uploaded agency list, from the file dialog to the commit. */
internal class ImportActions(private val vm: AdViewModel) {

    fun on(event: ImportEvent) {
        when (event) {
            ImportEvent.PickFile -> pickFile()
            ImportEvent.Clear -> vm.update { copy(import = null) }
            is ImportEvent.ToggleRow -> toggleRow(event.index)
            ImportEvent.ToggleAll -> toggleAll()
            is ImportEvent.EditRow -> editRow(event)
            ImportEvent.Commit -> commit(toToday = false)
            ImportEvent.CommitToday -> commit(toToday = true)
        }
    }

    /**
     * The file goes to storage, and the server reads it.
     *
     * The client never parses a spreadsheet: the extraction and the match from
     * each row's agency name to a registered vendor are the server's.
     */
    private fun pickFile() = vm.posting {
        vm.run {
            val picked = vm.machine.pickFile(AGENCY_LIST_EXTENSIONS) ?: return@run
            vm.update { copy(import = ImportState(fileName = picked.name, uploading = true)) }
            when (val preview = vm.repo.importPreview(picked.name, picked.bytes)) {
                is ZillitResult.Success -> vm.update {
                    copy(import = import?.copy(uploading = false, preview = preview.data))
                }

                is ZillitResult.Failure -> {
                    vm.update { copy(import = import?.copy(uploading = false)) }
                    vm.report(preview.error)
                }
            }
        }
    }

    private fun toggleRow(index: Int) = patchRows { row ->
        if (row.index == index) row.copy(selected = !row.selected) else row
    }

    private fun toggleAll() {
        val preview = vm.current.import?.preview ?: return
        val allOn = preview.rows.all { it.selected }
        patchRows { it.copy(selected = !allOn) }
    }

    private fun editRow(event: ImportEvent.EditRow) = patchRows { row ->
        if (row.index != event.index) {
            row
        } else {
            when (event.field) {
                ImportField.FirstName -> row.copy(firstName = event.text)
                ImportField.LastName -> row.copy(lastName = event.text)
                ImportField.Email -> row.copy(email = event.text)
            }
        }
    }

    /**
     * Commits the ticked, valid rows.
     *
     * The to-today variant is hidden once the day is locked, which is why it
     * checks the day's own gate rather than only the posting right.
     */
    private fun commit(toToday: Boolean) {
        val rows = vm.current.import?.preview?.ready.orEmpty()
        if (rows.isEmpty()) return
        if (toToday && !vm.current.canEditDay) {
            vm.refuse()
            return
        }
        if (!toToday && !vm.current.viewer.canPost) {
            vm.askForPostingRights()
            return
        }
        vm.update { copy(import = import?.copy(committing = true)) }
        vm.run {
            val result = if (toToday) {
                vm.repo.importCommitToday(rows, vm.current.shootDate)
            } else {
                vm.repo.importCommit(rows)
            }
            when (result) {
                is ZillitResult.Success -> {
                    vm.update {
                        copy(
                            import = null,
                            notice = if (toToday) {
                                str(S.desktop_ad_imported_to_day, result.data)
                            } else {
                                str(S.desktop_ad_imported_to_register, result.data)
                            },
                        )
                    }
                    vm.loadRegister()
                    if (toToday) vm.loadDay()
                }

                is ZillitResult.Failure -> {
                    vm.update { copy(import = import?.copy(committing = false)) }
                    vm.report(result.error)
                }
            }
        }
    }

    private fun patchRows(change: (com.zillit.desktop.feature.addashboard.domain.ImportRow) ->
    com.zillit.desktop.feature.addashboard.domain.ImportRow) = vm.update {
        val preview = import?.preview ?: return@update this
        copy(import = import.copy(preview = preview.copy(rows = preview.rows.map(change))))
    }
}

/** One bank field replaced, leaving the rest alone. */
internal fun BankDetails.with(field: BankField, text: String): BankDetails = when (field) {
    BankField.AccountName -> copy(accountName = text)
    BankField.AccountNumber -> copy(accountNumber = text)
    BankField.SortCode -> copy(sortCode = text)
    BankField.Iban -> copy(ibanNumber = text)
    BankField.Swift -> copy(swiftCode = text)
    BankField.BankName -> copy(bankName = text)
}

/** An artiste back into the form's draft, for an edit. */
internal fun com.zillit.desktop.feature.addashboard.domain.Artiste.asDraft(): ArtisteDraft = ArtisteDraft(
    isExternal = isExternal,
    userId = userId.orEmpty(),
    firstName = firstName,
    lastName = lastName,
    email = email,
    phone = phone,
    dateOfBirth = dateOfBirth,
    address = address,
    category = category,
    engagement = engagement,
    union = union,
    agencyName = agencyName,
    agencyId = agencyId,
    isMinor = isMinor,
    refNumber = refNumber,
    bank = bank,
    documents = documents,
    status = status,
)
