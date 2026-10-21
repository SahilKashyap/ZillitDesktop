package com.zillit.desktop.feature.addashboard.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.addashboard.domain.Agency
import com.zillit.desktop.feature.addashboard.domain.AgencyDraft
import com.zillit.desktop.feature.addashboard.domain.DayBreakdownLine
import com.zillit.desktop.feature.addashboard.domain.QueryDayContext

/**
 * The Agency tab.
 *
 * Deliberately **not** gated on the AD posting right: it is a copy of the
 * Vendors module, and edit, delete and verify follow the Vendors rules — an
 * accountant, or whoever created the row, and verify for an accountant only.
 * Only **Add Agency** is blocked for a non-posting viewer, because that lives
 * under Rates & Config.
 */
@Suppress("TooManyFunctions") // One handler per action on an agency.
internal class AgencyActions(private val vm: AdViewModel) {

    fun load() {
        vm.update { copy(agency = agency.copy(loading = true)) }
        vm.run {
            when (val rows = vm.repo.agencies()) {
                is ZillitResult.Success -> vm.update {
                    copy(agency = agency.copy(rows = rows.data, loading = false))
                }

                is ZillitResult.Failure -> {
                    vm.update { copy(agency = agency.copy(loading = false)) }
                    vm.failWith(rows.error)
                }
            }
        }
    }

    @Suppress("CyclomaticComplexMethod") // A dispatch table; one line per event.
    fun on(event: AgencyEvent) {
        when (event) {
            is AgencyEvent.Search -> vm.update { copy(agency = agency.copy(search = event.text)) }
            is AgencyEvent.OpenAgency -> vm.update { copy(agency = agency.copy(open = event.agency)) }
            AgencyEvent.CloseAgency -> vm.update { copy(agency = agency.copy(open = null)) }
            is AgencyEvent.StartForm -> startForm(event.agency)
            AgencyEvent.CancelForm -> vm.update { copy(agency = agency.copy(form = null)) }
            is AgencyEvent.Name -> patch { copy(name = event.text) }
            is AgencyEvent.Email -> patch { copy(email = event.text) }
            is AgencyEvent.Contact -> patch { copy(contactPerson = event.text) }
            is AgencyEvent.Phone -> patch { copy(phone = event.text) }
            is AgencyEvent.Address -> patch { copy(address = event.text) }
            is AgencyEvent.Bank -> patch { copy(bank = bank.with(event.field, event.text)) }
            is AgencyEvent.Save -> save(event.andVerify)
            is AgencyEvent.Verify -> verify(event.id)
            is AgencyEvent.AskDelete -> askDelete(event.agency)
            AgencyEvent.ConfirmDelete -> confirmDelete()
            AgencyEvent.CancelDelete -> vm.update { copy(agency = agency.copy(confirmDelete = null)) }
            is AgencyEvent.OpenHistory -> openHistory(event.agency)
            AgencyEvent.CloseHistory -> vm.update {
                copy(agency = agency.copy(historyFor = null, history = emptyList()))
            }
        }
    }

    /** Whether this viewer may change that row — the Vendors rules, not the AD's. */
    private fun mayEdit(agency: Agency?): Boolean {
        val row = agency ?: return vm.current.viewer.canPost
        return row.editableBy(vm.current.viewer.userId, vm.machine.isAccountant())
    }

    private fun startForm(agency: Agency?) {
        if (!mayEdit(agency)) {
            vm.fail(str(S.desktop_ad_agency_not_yours))
            return
        }
        vm.update {
            copy(
                agency = this.agency.copy(
                    form = AgencyFormState(
                        agencyId = agency?.id,
                        draft = agency?.asDraft() ?: AgencyDraft(),
                    ),
                ),
            )
        }
    }

    private fun save(andVerify: Boolean) {
        val open = vm.current.agency.form ?: return
        if (!open.draft.ready) {
            vm.update {
                val said = str(S.desktop_ad_agency_name_required)
                copy(agency = agency.copy(form = agency.form?.copy(error = said)))
            }
            return
        }
        vm.update { copy(agency = agency.copy(form = agency.form?.copy(saving = true, error = null))) }
        vm.run {
            val result = if (open.agencyId == null) {
                vm.repo.createAgency(open.draft)
            } else {
                vm.repo.updateAgency(open.agencyId, open.draft)
            }
            when (result) {
                is ZillitResult.Success -> {
                    // Verify is a second call, as it is on the web: the save
                    // route does not take a verified flag.
                    if (andVerify && open.agencyId != null) vm.repo.verifyAgency(open.agencyId)
                    vm.update { copy(agency = agency.copy(form = null), notice = str(S.desktop_ad_agency_saved)) }
                    load()
                }

                is ZillitResult.Failure -> {
                    vm.update { copy(agency = agency.copy(form = agency.form?.copy(saving = false))) }
                    vm.report(result.error)
                }
            }
        }
    }

    /** Verify is accountant-only, which is the Vendors rule rather than this tool's. */
    private fun verify(id: String) {
        if (!vm.machine.isAccountant()) {
            vm.fail(str(S.desktop_ad_verify_accountant_only))
            return
        }
        act(str(S.desktop_ad_agency_verified)) { vm.repo.verifyAgency(id) }
    }

    private fun askDelete(agency: Agency) {
        if (!mayEdit(agency)) {
            vm.fail(str(S.desktop_ad_agency_not_yours))
            return
        }
        vm.update { copy(agency = this.agency.copy(confirmDelete = agency)) }
    }

    private fun confirmDelete() {
        val target = vm.current.agency.confirmDelete ?: return
        act(str(S.desktop_ad_agency_deleted)) { vm.repo.deleteAgency(target.id) }
        vm.update { copy(agency = agency.copy(confirmDelete = null, open = null)) }
    }

    private fun openHistory(agency: Agency) {
        vm.update { copy(agency = this.agency.copy(historyFor = agency, historyLoading = true)) }
        vm.run {
            when (val rows = vm.repo.agencyHistory(agency.id)) {
                is ZillitResult.Success -> vm.update {
                    copy(agency = this.agency.copy(history = rows.data, historyLoading = false))
                }

                is ZillitResult.Failure -> {
                    vm.update { copy(agency = this.agency.copy(historyLoading = false)) }
                    vm.report(rows.error)
                }
            }
        }
    }

    private fun act(success: String, call: suspend () -> ZillitResult<Unit>) {
        vm.update { copy(agency = agency.copy(busy = true)) }
        vm.run {
            val result = call()
            vm.update { copy(agency = agency.copy(busy = false)) }
            when (result) {
                is ZillitResult.Success -> {
                    vm.notice(success)
                    load()
                }

                is ZillitResult.Failure -> vm.report(result.error)
            }
        }
    }

    private fun patch(change: AgencyDraft.() -> AgencyDraft) = vm.update {
        copy(agency = agency.copy(form = agency.form?.let { it.copy(draft = it.draft.change(), error = null) }))
    }
}

/**
 * Artiste Queries.
 *
 * The one area that **ignores** read-only by decision: anyone who can open the
 * tool may reply and resolve, because a query is communication rather than an
 * operational action. Don't add a posting gate here.
 */
internal class QueryActions(private val vm: AdViewModel) {

    fun load() {
        vm.update { copy(queries = queries.copy(loading = true)) }
        vm.run {
            when (val rows = vm.repo.queries()) {
                is ZillitResult.Success -> vm.update {
                    copy(queries = queries.copy(rows = rows.data, loading = false))
                }

                is ZillitResult.Failure -> {
                    vm.update { copy(queries = queries.copy(loading = false)) }
                    vm.failWith(rows.error)
                }
            }
        }
    }

    fun on(event: QueryEvent) {
        when (event) {
            is QueryEvent.Search -> vm.update { copy(queries = queries.copy(search = event.text)) }
            is QueryEvent.ShowResolved -> vm.update { copy(queries = queries.copy(showResolved = event.on)) }
            is QueryEvent.OpenThread -> openThread(event.id)
            QueryEvent.CloseThread -> vm.update {
                copy(queries = queries.copy(openId = null, thread = null, dayContext = null))
            }

            is QueryEvent.Reply -> vm.update { copy(queries = queries.copy(reply = event.text)) }
            QueryEvent.SendReply -> sendReply()
            is QueryEvent.Resolve -> resolve(event.id)
        }
    }

    /** Opening a thread reads that one query's badge, never the whole unit. */
    private fun openThread(id: String) {
        vm.readEntity(AdBadges.QUERY, id)
        vm.update { copy(queries = queries.copy(openId = id, threadLoading = true, dayContext = null)) }
        vm.run {
            when (val thread = vm.repo.query(id)) {
                is ZillitResult.Success -> {
                    vm.update { copy(queries = queries.copy(thread = thread.data, threadLoading = false)) }
                    thread.data.day?.id?.takeIf { it.isNotBlank() }?.let { loadDayContext(it, thread.data) }
                }

                is ZillitResult.Failure -> {
                    vm.update { copy(queries = queries.copy(threadLoading = false)) }
                    vm.report(thread.error)
                }
            }
        }
    }

    /**
     * The day behind the query.
     *
     * Deliberately not the voucher total: the engine's rate and allowance
     * lines only, which is what the web's own breakdown shows — additional
     * fees are absent from it.
     */
    private fun loadDayContext(dayId: String, query: com.zillit.desktop.feature.addashboard.domain.ArtisteQuery) {
        vm.run {
            (vm.repo.dayEntry(dayId) as? ZillitResult.Success)?.let { row ->
                val day = row.data
                vm.update {
                    copy(
                        queries = queries.copy(
                            dayContext = QueryDayContext(
                                shootDate = day.shootDate,
                                code = query.voucherCode,
                                statusLabel = day.status,
                                callTime = day.callTime,
                                wrapTime = day.wrapTime,
                                lines = day.allowances.map { line ->
                                    DayBreakdownLine(
                                        label = line.label,
                                        detail = line.rateType,
                                        amount = line.rateAmount,
                                    )
                                },
                                total = day.gross,
                                currency = day.currency,
                            ),
                        ),
                    )
                }
            }
        }
    }

    private fun sendReply() {
        val open = vm.current.queries
        val id = open.openId ?: return
        val text = open.reply.trim().takeIf { it.isNotEmpty() } ?: return
        vm.update { copy(queries = queries.copy(busy = true)) }
        vm.run {
            val result = vm.repo.replyToQuery(id, text)
            vm.update { copy(queries = queries.copy(busy = false)) }
            when (result) {
                is ZillitResult.Success -> {
                    vm.update { copy(queries = queries.copy(reply = "")) }
                    openThread(id)
                    load()
                }

                is ZillitResult.Failure -> vm.report(result.error)
            }
        }
    }

    private fun resolve(id: String) {
        vm.update { copy(queries = queries.copy(busy = true)) }
        vm.run {
            val result = vm.repo.resolveQuery(id)
            vm.update { copy(queries = queries.copy(busy = false)) }
            when (result) {
                is ZillitResult.Success -> {
                    vm.notice(str(S.desktop_ad_query_resolved))
                    load()
                    if (vm.current.queries.openId == id) openThread(id)
                }

                // The web swallows this one with no toast; a refusal the
                // person cannot see is worse than one they can.
                is ZillitResult.Failure -> vm.report(result.error)
            }
        }
    }
}

/** An agency back into the form's draft, for an edit. */
internal fun Agency.asDraft(): AgencyDraft = AgencyDraft(
    name = name,
    email = email,
    contactPerson = contactPerson,
    phone = phone,
    address = address,
    bank = bank,
)
