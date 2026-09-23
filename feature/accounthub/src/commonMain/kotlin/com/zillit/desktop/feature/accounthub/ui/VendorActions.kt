package com.zillit.desktop.feature.accounthub.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.accounthub.domain.IsdCountries
import com.zillit.desktop.feature.accounthub.domain.NewVendor
import com.zillit.desktop.feature.accounthub.domain.Vendor
import com.zillit.desktop.feature.accounthub.domain.VendorBank
import com.zillit.desktop.feature.accounthub.domain.withBank
import com.zillit.desktop.feature.accounthub.domain.validationError
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay

/**
 * The vendor register's actions.
 *
 * Its own collaborator for the reason [SetupSections] is: the view model had
 * grown past what one class should hold, and the register is the most
 * self-contained slice of it — a list, a detail, a full-page form, a history
 * panel, and four writes that all end in the same reload.
 */
@Suppress("TooManyFunctions") // One handler per action.
internal class VendorActions(private val vm: AccountHubViewModel) {

    private var revealJob: Job? = null

    /** The postcode lookup waiting out its pause, or on the wire. One at a time. */
    private var postcodeJob: Job? = null

    fun load() {
        vm.update { copy(vendors = vendors.copy(loading = true)) }
        // The whole register, once. Search is applied locally, as the web does:
        // it matches the department's *name*, which the server does not hold,
        // and it keeps the tab counts about the register rather than about
        // whatever was typed.
        vm.runResult({ vm.repo.vendors() }, { rows ->
            vm.update { copy(vendors = vendors.copy(rows = rows, loading = false)) }
            openPendingEdit()
        }, { error ->
            vm.update { copy(vendors = vendors.copy(loading = false)) }
            vm.report(error)
        })
        // The department chip and the form's picker read the chart and the
        // departments; both are already on the roster, the chart is made sure of.
        vm.chart.ensureLoaded()
        loadCountries()
    }

    /**
     * The live country catalogue, once.
     *
     * Silent when it fails, as on the web: the bundled copy is already in the
     * state, and it is left there rather than traded for an empty answer — a
     * picker with nothing in it would make the required country unanswerable.
     */
    internal fun loadCountries() {
        if (vm.setupState.vendors.countriesLoaded) return
        vm.runResult({ vm.repo.isdCodes() }, { rows ->
            vm.update {
                copy(vendors = vendors.copy(countries = rows.ifEmpty { vendors.countries }, countriesLoaded = true))
            }
        })
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod") // One branch per action.
    fun onEvent(event: AccountHubEvent): Boolean {
        when (event) {
            // Local, no round trip: the rows are already here. See `load`.
            is AccountHubEvent.SearchVendors -> vm.update { copy(vendors = vendors.copy(search = event.term)) }
            is AccountHubEvent.FilterVendors -> vm.update {
                // No refetch: the rows are already here and the flag is on them.
                copy(vendors = vendors.copy(filter = event.filter))
            }
            is AccountHubEvent.SelectVendor -> selectVendor(event.id)
            is AccountHubEvent.OpenVendorDetail -> openDetail(event.id)
            is AccountHubEvent.RevealVendorBank -> reveal(event.revealed)
            is AccountHubEvent.ComposeVendor -> composeVendor(event.editing)
            is AccountHubEvent.OpenVendorForm -> openForm(event.editId)
            is AccountHubEvent.UpdateVendorDraft -> updateDraft(event.draft)
            is AccountHubEvent.TouchVendorField -> vm.update {
                copy(vendors = vendors.copy(page = vendors.page?.let { it.copy(touched = it.touched + event.field) }))
            }
            AccountHubEvent.DismissVendorForm -> {
                postcodeJob?.cancel()
                vm.update { copy(vendors = vendors.copy(page = null)) }
            }
            AccountHubEvent.SaveVendor -> saveVendor()
            AccountHubEvent.SaveAndVerifyVendor -> saveVendor(thenVerify = true)
            is AccountHubEvent.AskDeleteVendorBank -> vm.update {
                copy(vendors = vendors.copy(page = vendors.page?.copy(confirmDeleteBank = event.open)))
            }
            AccountHubEvent.ConfirmDeleteVendorBank -> clearBank()
            is AccountHubEvent.VerifyVendor -> verifyVendor(event.id)
            is AccountHubEvent.AskDeleteVendor -> vm.update {
                copy(vendors = vendors.copy(confirmDelete = event.vendor))
            }
            is AccountHubEvent.DeleteVendor -> deleteVendor(event.id)
            is AccountHubEvent.OpenVendorHistory -> openHistory(event.id)
            else -> return false
        }
        return true
    }

    /**
     * Opens a vendor's detail, fetching its linked bank record alongside.
     *
     * Without the record a vendor saved through the current flow showed no bank
     * details at all — its row's bank columns are the legacy copy and are empty.
     */
    private fun openDetail(id: String?) {
        vm.update {
            copy(vendors = vendors.copy(detailId = id, bankRevealed = false, bankRecord = null))
        }
        val bankId = vm.setupState.vendors.detail?.bankId ?: return
        vm.update { copy(vendors = vendors.copy(bankRecordLoading = true)) }
        vm.runResult({ vm.repo.bankAccount(bankId) }, { record ->
            // Guarded: the reader may have opened another vendor while this was
            // in flight, and one vendor's bank details on another's card is the
            // worst thing this dialog could show.
            vm.update {
                val stillOpen = vendors.detail?.bankId == bankId
                copy(
                    vendors = vendors.copy(
                        bankRecord = if (stillOpen) record else vendors.bankRecord,
                        bankRecordLoading = false,
                    ),
                )
            }
        }, {
            // A missing record falls back to the row's own copy, as the web does;
            // it is not an error worth interrupting the dialog for.
            vm.update { copy(vendors = vendors.copy(bankRecordLoading = false)) }
        })
    }

    /**
     * The route's form: a new vendor now, an edit once its row is here.
     *
     * An id the register does not hold is dropped rather than kept waiting —
     * the web clears its query either way, so a stale link does not reopen the
     * form every time the list refreshes.
     */
    private fun openForm(editId: String?) {
        if (editId == null) {
            composeVendor(null)
            return
        }
        vm.update { copy(vendors = vendors.copy(pendingEditId = editId)) }
        openPendingEdit()
    }

    private fun openPendingEdit() {
        val vendors = vm.setupState.vendors
        val id = vendors.pendingEditId ?: return
        if (vendors.loading) return
        val row = vendors.rows.firstOrNull { it.id == id }
        vm.update { copy(vendors = this.vendors.copy(pendingEditId = null)) }
        if (row != null) composeVendor(row)
    }

    private fun selectVendor(id: String?) {
        vm.update { copy(vendors = vendors.copy(selectedId = id, history = emptyList())) }
        val vendorId = id ?: return
        loadHistory(vendorId)
    }

    private fun openHistory(id: String?) {
        vm.update { copy(vendors = vendors.copy(historyFor = id, history = emptyList(), historyError = null)) }
        id?.let(::loadHistory)
    }

    private fun loadHistory(vendorId: String) {
        vm.update { copy(vendors = vendors.copy(historyLoading = true, historyError = null)) }
        vm.runResult({ vm.repo.vendorHistory(vendorId) }, { rows ->
            vm.update { copy(vendors = vendors.copy(history = rows, historyLoading = false)) }
        }, { error ->
            // In the panel, not only a toast: "No recorded changes" under a
            // failed read tells the user the vendor has no history.
            vm.update { copy(vendors = vendors.copy(historyLoading = false, historyError = error.localised())) }
        })
    }

    private fun updateDraft(draft: NewVendor) {
        val before = vm.setupState.vendors.page?.draft ?: return
        vm.update { copy(vendors = vendors.copy(page = vendors.page?.copy(draft = draft))) }
        val moved = before.address.postalCode != draft.address.postalCode ||
            before.address.country != draft.address.country
        if (moved) lookUpPostcode()
    }

    /**
     * Fills the city and county from the postcode, a second after the last
     * change to the postcode or the country: the web's `usePostcodeAutofill`.
     *
     * Only an edit starts it, never opening the form, so a saved vendor's
     * address is not rewritten by being looked at. A confirmed answer is
     * written over whatever the fields held, blanks included (ZL-20356: the old
     * fill-if-empty guard left the previous postcode's city in place), and a
     * failed lookup changes nothing. A newer change cancels an older lookup, so
     * a slow answer can never land over a fresher one.
     */
    private fun lookUpPostcode() {
        postcodeJob?.cancel()
        val page = vm.setupState.vendors.page ?: return
        if (page.postcodeLooking) setPostcodeLooking(false)
        val countryCode = IsdCountries.isoFor(vm.setupState.vendors.countries, page.draft.address.country)
        val postcode = page.draft.address.postalCode.trim()
        if (countryCode.isBlank() || postcode.length < MIN_POSTCODE) return
        val formId = page.editingId
        postcodeJob = vm.launchWork {
            delay(POSTCODE_PAUSE_MS)
            setPostcodeLooking(true)
            val place = (vm.repo.postcodePlace(countryCode, postcode) as? ZillitResult.Success)?.data
            vm.update {
                val open = vendors.page?.takeIf { it.editingId == formId } ?: return@update this
                val address = open.draft.address
                val draft = place?.let { open.draft.copy(address = address.copy(city = it.city, state = it.state)) }
                copy(vendors = vendors.copy(page = open.copy(draft = draft ?: open.draft, postcodeLooking = false)))
            }
        }
    }

    private fun setPostcodeLooking(looking: Boolean) {
        vm.update { copy(vendors = vendors.copy(page = vendors.page?.copy(postcodeLooking = looking))) }
    }

    /** Unmasks the detail's bank numbers; re-masks after five seconds, as the web does. */
    private fun reveal(revealed: Boolean) {
        revealJob?.cancel()
        vm.update { copy(vendors = vendors.copy(bankRevealed = revealed)) }
        if (!revealed) return
        revealJob = vm.launchWork {
            delay(REVEAL_MS)
            vm.update { copy(vendors = vendors.copy(bankRevealed = false)) }
        }
    }

    /**
     * Opens the vendor form, new or editing.
     *
     * Editing seeds the bank block from the linked record rather than the row:
     * the row's bank columns are the legacy copy, and seeding from them put an
     * empty bank block in front of a vendor whose details were on file — so an
     * unrelated edit sent every bank field back as null.
     */
    private fun composeVendor(editing: Vendor?) {
        val viewer = vm.setupState.viewer
        val allowed = if (editing == null) viewer.mayAddVendor else viewer.mayModifyVendor(editing)
        if (!allowed) {
            vm.sendSideEffect(
                AccountHubEffect.Failed(
                    if (editing == null) {
                        str(S.desktop_hub_you_cannot_add_vendors_on_this_project)
                    } else {
                        str(S.desktop_hub_only_the_accounts_team_or_the_person_who_added_this)
                    },
                ),
            )
            return
        }
        val bankId = editing?.bankId
        postcodeJob?.cancel()
        vm.update {
            copy(
                vendors = vendors.copy(
                    detailId = null,
                    page = VendorFormPage(
                        editingId = editing?.id,
                        draft = editing?.let(NewVendor::from) ?: NewVendor(departmentId = ownDepartmentId()),
                        bankId = bankId,
                        bankLoading = bankId != null,
                    ),
                ),
            )
        }
        if (editing == null || bankId == null) return
        vm.runResult({ vm.repo.bankAccount(bankId) }, { record ->
            vm.update {
                val page = vendors.page?.takeIf { it.editingId == editing.id } ?: return@update this
                copy(
                    vendors = vendors.copy(
                        page = page.copy(
                            draft = page.draft.withBank(VendorBank.resolve(editing, record)),
                            bankLoading = false,
                        ),
                    ),
                )
            }
        }, {
            // No record: the row's legacy copy is already in the draft.
            vm.update { copy(vendors = vendors.copy(page = vendors.page?.copy(bankLoading = false))) }
        })
    }

    /**
     * The viewer's own department, which a new vendor starts in: the web's
     * `currentUser.department_id` default.
     *
     * New vendors only. The web applies it to an edit too, which files a vendor
     * saved without a department under whoever next opens it — the same silent
     * default ZL-20520 took out of the dial code and the country.
     *
     * The roster names a person's department rather than giving its id, so the
     * name is matched against the department list; no match leaves it unset.
     */
    private fun ownDepartmentId(): String? {
        val state = vm.setupState
        val me = state.users.firstOrNull { it.id == state.viewer.userId } ?: return null
        if (me.departmentId.isNotBlank()) return me.departmentId
        val department = me.department.trim().takeIf { it.isNotEmpty() } ?: return null
        return state.departmentList.firstOrNull {
            it.name.equals(department, ignoreCase = true) || it.identifier.equals(department, ignoreCase = true)
        }?.id
    }

    /**
     * Saves the open form, and on [thenVerify] verifies the same vendor.
     *
     * The verify is a second call the web makes too — there is no combined
     * endpoint — and it runs only after the save succeeds, so a rejected edit
     * cannot leave a vendor verified against details that were never stored.
     */
    private fun saveVendor(thenVerify: Boolean = false) {
        val page = vm.setupState.vendors.page ?: return
        val problem = page.draft.validationError()
        if (problem != null) {
            vm.update { copy(vendors = vendors.copy(page = page.copy(showErrors = true))) }
            vm.sendSideEffect(AccountHubEffect.Failed(problem))
            return
        }
        if (thenVerify && !vm.mayActAsAccountant()) return
        // The handler holds the rule as well as the screen: an event can reach
        // here without the button that would have hidden it.
        val viewer = vm.setupState.viewer
        val existing = page.editingId?.let { id -> vm.setupState.vendors.rows.firstOrNull { it.id == id } }
        val allowed = if (existing == null) viewer.mayAddVendor else viewer.mayModifyVendor(existing)
        if (!allowed) {
            vm.sendSideEffect(
                AccountHubEffect.Failed(str(S.desktop_hub_only_the_accounts_team_or_the_person_who_added_this)),
            )
            return
        }
        vm.update { copy(vendors = vendors.copy(page = page.copy(saving = !thenVerify, verifying = thenVerify))) }
        vm.runResult(
            { page.editingId?.let { vm.repo.updateVendor(it, page.draft) } ?: vm.repo.createVendor(page.draft) },
            { saved ->
                val id = page.editingId ?: saved.id.takeIf { it.isNotBlank() }
                if (thenVerify && id != null) {
                    verifyAfterSave(id)
                } else {
                    postcodeJob?.cancel()
                    vm.update { copy(vendors = vendors.copy(page = null), notice = str(S.desktop_vendor_saved)) }
                    load()
                }
            },
            { error ->
                vm.update { copy(vendors = vendors.copy(page = page.copy(saving = false, verifying = false))) }
                vm.report(error)
            },
        )
    }

    /**
     * The verify half of Save & Verify.
     *
     * A failure here closes the form and reports: the edit *did* save, so
     * leaving the form open would invite the user to save it twice.
     */
    private fun verifyAfterSave(id: String) {
        vm.runResult({ vm.repo.verifyVendor(id) }, {
            vm.update {
                copy(vendors = vendors.copy(page = null), notice = str(S.desktop_hub_vendor_saved_and_verified))
            }
            load()
        }, { error ->
            vm.update { copy(vendors = vendors.copy(page = null), notice = str(S.desktop_vendor_saved)) }
            load()
            vm.report(error)
        })
    }

    /**
     * "Delete Bank Details": removes the linked record, now.
     *
     * Immediate rather than deferred to Save, as the web does: the record is its
     * own thing, and clearing the form's fields and waiting for a save left the
     * record — and the vendor's `bank_id` — exactly where they were. The server
     * nulls `bank_id` itself, and refuses a bank still named by an invoice, a
     * card, a cash claim or a timecard; that refusal is shown, not swallowed.
     */
    private fun clearBank() {
        val page = vm.setupState.vendors.page ?: return
        val bankId = page.bankId
        if (bankId == null) {
            vm.update { copy(vendors = vendors.copy(page = page.copy(confirmDeleteBank = false))) }
            return
        }
        val existing = page.editingId?.let { id -> vm.setupState.vendors.rows.firstOrNull { it.id == id } }
        if (existing == null || !vm.setupState.viewer.mayModifyVendor(existing)) return
        vm.update {
            copy(vendors = vendors.copy(page = page.copy(confirmDeleteBank = false, deletingBank = true)))
        }
        vm.runResult({ vm.repo.deleteBankAccount(bankId) }, {
            vm.update {
                val open = vendors.page ?: return@update this
                copy(
                    vendors = vendors.copy(
                        page = open.copy(
                            draft = open.draft.withBank(VendorBank()),
                            bankId = null,
                            deletingBank = false,
                        ),
                    ),
                    notice = str(S.ah_bank_deleted_msg),
                )
            }
            load()
        }, { error ->
            vm.update { copy(vendors = vendors.copy(page = vendors.page?.copy(deletingBank = false))) }
            vm.report(error)
        })
    }

    private fun verifyVendor(id: String) {
        // Not `requireEdit`: verification is one of the two operations the
        // service reserves for the accounts department.
        if (!vm.mayActAsAccountant()) return
        vm.update { copy(vendors = vendors.copy(verifyingId = id)) }
        vm.runResult({ vm.repo.verifyVendor(id) }, {
            vm.update { copy(vendors = vendors.copy(verifyingId = null), notice = str(S.desktop_vendor_verified)) }
            load()
        }, { error ->
            vm.update { copy(vendors = vendors.copy(verifyingId = null)) }
            vm.report(error)
        })
    }

    private fun deleteVendor(id: String) {
        val vendor = vm.setupState.vendors.rows.firstOrNull { it.id == id } ?: return
        if (!vm.setupState.viewer.mayModifyVendor(vendor)) {
            vm.sendSideEffect(
                AccountHubEffect.Failed(str(S.desktop_hub_only_the_accounts_team_or_the_person_who_added_this_2)),
            )
            return
        }
        if (id in vm.setupState.vendors.deletingIds) return
        vm.update { copy(vendors = vendors.copy(confirmDelete = null, deletingIds = vendors.deletingIds + id)) }
        vm.runResult({ vm.repo.deleteVendor(id) }, {
            vm.update {
                copy(
                    vendors = vendors.copy(
                        selectedId = null,
                        detailId = null,
                        historyFor = null,
                        deletingIds = vendors.deletingIds - id,
                    ),
                    notice = str(S.desktop_vendor_removed),
                )
            }
            load()
        }, { error ->
            vm.update { copy(vendors = vendors.copy(deletingIds = vendors.deletingIds - id)) }
            vm.report(error)
        })
    }

    private companion object {
        const val REVEAL_MS = 5_000L

        /** The web's debounce: the lookup waits for a pause in the typing. */
        const val POSTCODE_PAUSE_MS = 1_000L

        /** Shorter than this, the web does not ask. */
        const val MIN_POSTCODE = 3
    }
}
