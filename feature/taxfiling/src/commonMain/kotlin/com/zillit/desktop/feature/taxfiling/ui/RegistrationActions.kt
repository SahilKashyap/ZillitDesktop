package com.zillit.desktop.feature.taxfiling.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.taxfiling.domain.RegistrationRequest
import com.zillit.desktop.feature.taxfiling.domain.SupportedFiling
import com.zillit.desktop.feature.taxfiling.domain.TaxFilingRoute
import com.zillit.desktop.feature.taxfiling.domain.TaxRegistration
import com.zillit.desktop.feature.taxfiling.domain.exportFileName
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay

/**
 * The companies list: registering a VAT number, removing one, exporting what
 * the service holds, and connecting to HMRC — the web's `TaxFilingModule`
 * handlers and its `RegisterModal` / `RemoveConfirm`.
 */
internal class RegistrationActions(private val vm: TaxFilingViewModel) {

    /** The check for a consent the accountant is giving in the browser. */
    private var watch: Job? = null

    fun onEvent(event: TaxFilingEvent): Boolean {
        when (event) {
            TaxFilingEvent.ComposeRegistration -> vm.update { copy(draft = RegistrationDraft()) }
            is TaxFilingEvent.EditDraft -> vm.update {
                // The number field keeps digits only, nine at most, however it was typed.
                val number = RegistrationDraft.cleanNumber(event.draft.registrationNumber)
                copy(draft = event.draft.copy(registrationNumber = number))
            }
            TaxFilingEvent.DismissDraft -> vm.update { if (draft?.saving == true) this else copy(draft = null) }
            TaxFilingEvent.SaveRegistration -> save()
            is TaxFilingEvent.AskRemove -> vm.update { copy(removing = event.registration) }
            TaxFilingEvent.DismissRemove -> vm.update { if (removeInFlight) this else copy(removing = null) }
            TaxFilingEvent.ConfirmRemove -> remove()
            is TaxFilingEvent.ExportData -> export(event.registration)
            is TaxFilingEvent.Connect -> connect(event.registration)
            TaxFilingEvent.CancelConnect -> stopWatching()
            else -> return false
        }
        return true
    }

    private fun save() {
        val state = vm.current
        val draft = state.draft ?: return
        if (!draft.canSubmit) return
        // One registration per company — the dialog only offers the free ones,
        // and a stale list must not register a company twice.
        if (state.registrations.any { it.companyId == draft.companyId }) {
            return vm.info(str(S.desktop_tax_already_registered))
        }
        val company = state.companies.firstOrNull { it.id == draft.companyId }
        val filing = (state.route as? TaxFilingRoute.Filing)?.supported ?: SupportedFiling.MtdVat
        vm.update { copy(draft = draft.copy(saving = true)) }
        vm.request(
            {
                vm.repository.createRegistration(
                    RegistrationRequest(
                        companyId = draft.companyId,
                        registrationNumber = draft.registrationNumber,
                        filingFrequency = draft.frequency,
                        registrationDate = draft.registrationDate.trim().ifBlank { null },
                        countryCode = filing.country,
                        regime = filing.regime,
                    ),
                )
            },
            {
                vm.update { copy(draft = null) }
                vm.toast(
                    str(
                        S.desktop_tax_registered_named,
                        company?.name?.takeIf { it.isNotBlank() } ?: str(S.desktop_company_lower),
                    ),
                )
                vm.loadFiling()
            },
            { error ->
                vm.update { copy(draft = this.draft?.copy(saving = false)) }
                vm.fail(error)
            },
        )
    }

    /**
     * Removes a registration.
     *
     * The service disconnects it from HMRC and clears its obligation and return
     * history, so a return open for it closes. On a failure the dialog stays,
     * as on the web, so the accountant can try again or back out.
     */
    private fun remove() {
        val state = vm.current
        val target = state.removing ?: return
        if (state.removeInFlight) return
        val name = target.named(state.companies).companyName
        vm.update { copy(removeInFlight = true) }
        vm.request({ vm.repository.deleteRegistration(target.id) }, {
            if (vm.current.connectingId == target.id) stopWatching()
            vm.update {
                val closing = returnState.registration?.id == target.id
                copy(
                    removeInFlight = false,
                    removing = null,
                    view = if (closing) TaxFilingView.Registrations else view,
                    returnState = if (closing) ReturnState() else returnState,
                )
            }
            vm.toast(str(S.desktop_tax_removed_named, name))
            vm.loadFiling()
        }, { error ->
            vm.update { copy(removeInFlight = false) }
            vm.fail(error)
        })
    }

    /** UK GDPR data portability: everything held for the registration, as JSON. */
    private fun export(registration: TaxRegistration) {
        val sink = vm.fileSink ?: return vm.fail(str(S.desktop_cannot_save_files))
        val named = registration.named(vm.current.companies)
        vm.request({ vm.repository.exportRegistration(registration.id) }, { json ->
            vm.work {
                when (val saved = sink.save(exportFileName(named), json.encodeToByteArray(), open = false)) {
                    is ZillitResult.Success ->
                        vm.toast(
                            str(
                                S.desktop_tax_exported_company_data,
                                named.companyName.ifBlank { str(S.desktop_company_lower) },
                            ),
                        )
                    is ZillitResult.Failure -> vm.fail(saved.error)
                }
            }
        })
    }

    /**
     * Opens HMRC's consent page, then waits for the grant to land.
     *
     * The web leaves for HMRC and comes back to `?connected=1`. A desktop
     * application has no page for HMRC to return to, so it asks the service
     * every few seconds instead, and stops — saying so — when nothing arrives.
     */
    private fun connect(registration: TaxRegistration) {
        if (vm.current.connectingId == registration.id) return
        stopWatching()
        vm.update { copy(connectingId = registration.id) }
        vm.request({ vm.repository.connectUrl(registration.id) }, { url ->
            if (url.isBlank()) {
                vm.update { copy(connectingId = null) }
                vm.fail(str(S.desktop_tax_no_connect_url))
            } else {
                vm.emit(TaxFilingEffect.OpenInBrowser(url))
                watchFor(registration.id)
            }
        }, { error ->
            vm.update { copy(connectingId = null) }
            vm.fail(error)
        })
    }

    private fun watchFor(registrationId: String) {
        watch = vm.work {
            repeat(vm.connectPolls) {
                delay(vm.connectPollMillis)
                val rows = (vm.repository.registrations() as? ZillitResult.Success)?.data ?: return@repeat
                vm.applyRegistrations(rows)
                val row = rows.firstOrNull { it.id == registrationId }
                if (row == null || row.connected) {
                    vm.update { copy(connectingId = null) }
                    if (row != null) vm.toast(str(S.desktop_tax_connected_to_hmrc))
                    return@work
                }
            }
            vm.update { copy(connectingId = null) }
            vm.info(
                str(S.desktop_tax_connect_not_confirmed),
            )
        }
    }

    fun stopWatching() {
        watch?.cancel()
        watch = null
        vm.update { if (connectingId == null) this else copy(connectingId = null) }
    }
}
