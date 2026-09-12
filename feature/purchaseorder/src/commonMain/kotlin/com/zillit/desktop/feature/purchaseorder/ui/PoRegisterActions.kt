package com.zillit.desktop.feature.purchaseorder.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.feature.purchaseorder.domain.PoDeliveryAddress
import com.zillit.desktop.feature.purchaseorder.domain.PoTemplate
import com.zillit.desktop.feature.purchaseorder.domain.PurchaseOrderRepository
import kotlinx.serialization.builtins.ListSerializer

/**
 * The two registers: the Templates tab and the Delivery Addresses tab.
 *
 * Both are read on entering their tab rather than at startup — the web does the
 * same, and neither list is needed to raise an order. The address book is the
 * exception: the form's "Pick a saved address" needs it, so opening the form
 * loads it too.
 */
internal class PoRegisterActions(
    private val vm: PurchaseOrderViewModel,
    private val repository: PurchaseOrderRepository,
) {
    fun onEvent(event: PoEvent) {
        when (event) {
            PoEvent.AddAddress -> vm.update { copy(addressForm = PoAddressForm()) }
            is PoEvent.EditAddressRow -> openAddress(event.id)
            is PoEvent.EditAddress -> vm.update {
                copy(addressForm = addressForm?.copy(address = event.address, problem = null))
            }

            PoEvent.SaveAddress -> saveAddress()
            PoEvent.DismissAddress -> vm.update { copy(addressForm = null) }
            is PoEvent.DeleteTemplate -> askDeleteTemplate(event.id)
            else -> Unit
        }
    }

    // -- templates -------------------------------------------------------------

    fun loadTemplates() {
        vm.update { copy(templatesLoading = true) }
        vm.launchWork {
            when (val answer = repository.templates()) {
                is ZillitResult.Success -> {
                    vm.update { copy(templatesLoading = false, templates = answer.data) }
                    vm.remember(TEMPLATES_CACHE, ListSerializer(PoTemplate.serializer()), answer.data)
                }

                is ZillitResult.Failure -> {
                    val saved = vm.recallIfUnreachable(
                        TEMPLATES_CACHE,
                        answer.error,
                        ListSerializer(PoTemplate.serializer()),
                    )
                    if (saved != null) {
                        vm.update { copy(templatesLoading = false, templates = saved.first, staleSince = saved.second) }
                    } else {
                        vm.update { copy(templatesLoading = false) }
                        vm.fail(answer.error.localised())
                    }
                }
            }
        }
    }

    /** Asks before removing one: a template is somebody's saved work. */
    private fun askDeleteTemplate(id: String) {
        val template = vm.ui.templates.firstOrNull { it.id == id } ?: return
        vm.ask(
            PoPrompt.Confirm(
                action = PoConfirmAction.DeleteTemplate,
                targetId = id,
                title = "Delete Template",
                message = "\"${template.name}\" will be removed. This action cannot be undone.",
                destructive = true,
            ),
        )
    }

    fun deleteTemplate(id: String) {
        vm.update { copy(busy = true) }
        vm.launchWork {
            when (val answer = repository.deleteTemplate(id)) {
                is ZillitResult.Success -> {
                    vm.update {
                        copy(busy = false, notice = "Template deleted", templates = templates.filterNot { it.id == id })
                    }
                }

                is ZillitResult.Failure -> {
                    vm.update { copy(busy = false) }
                    vm.fail(answer.error.localised())
                }
            }
        }
    }

    // -- delivery addresses ----------------------------------------------------

    fun loadAddresses() {
        vm.update { copy(addressesLoading = true) }
        vm.launchWork {
            when (val answer = repository.deliveryAddresses()) {
                is ZillitResult.Success -> {
                    vm.update { copy(addressesLoading = false, addresses = answer.data) }
                    vm.remember(ADDRESSES_CACHE, ListSerializer(PoDeliveryAddress.serializer()), answer.data)
                }

                is ZillitResult.Failure -> {
                    val saved = vm.recallIfUnreachable(
                        ADDRESSES_CACHE,
                        answer.error,
                        ListSerializer(PoDeliveryAddress.serializer()),
                    )
                    if (saved != null) {
                        vm.update { copy(addressesLoading = false, addresses = saved.first) }
                    } else {
                        vm.update { copy(addressesLoading = false) }
                        vm.fail(answer.error.localised())
                    }
                }
            }
        }
    }

    /**
     * Opens one for editing, if this viewer may.
     *
     * The server enforces the same rule and answers 403; refusing here means
     * the reason is a sentence rather than a status code.
     */
    private fun openAddress(id: String) {
        val row = vm.ui.addresses.firstOrNull { it.id == id } ?: return
        if (!row.editableBy(vm.ui.viewer)) {
            vm.fail("You can only edit delivery addresses you created.")
            return
        }
        vm.update { copy(addressForm = PoAddressForm(id = row.id, address = row.address)) }
    }

    private fun saveAddress() {
        val form = vm.ui.addressForm ?: return
        val problem = form.address.validationError()
        if (problem != null) {
            vm.update { copy(addressForm = form.copy(problem = problem)) }
            return
        }
        vm.update { copy(addressForm = form.copy(saving = true, problem = null)) }
        vm.launchWork {
            when (val answer = repository.saveDeliveryAddress(form.id, form.address)) {
                is ZillitResult.Success -> {
                    val saved = answer.data
                    vm.update {
                        copy(
                            addressForm = null,
                            notice = if (form.id == null) "Delivery address saved" else "Delivery address updated",
                            // Replace in place where it was already listed, so
                            // the row does not jump to the bottom on an edit.
                            addresses = if (addresses.any { it.id == saved.id }) {
                                addresses.map { if (it.id == saved.id) saved else it }
                            } else {
                                addresses + saved
                            },
                        )
                    }
                }

                is ZillitResult.Failure -> vm.update {
                    copy(
                        addressForm = form.copy(
                            saving = false,
                            problem = answer.error.localised().ifBlank { "Couldn't update the delivery address." },
                        ),
                    )
                }
            }
        }
    }

    /** Both registers again, after a socket frame said one of them changed. */
    fun reload() {
        if (vm.ui.destination == PoDestination.Templates || vm.ui.templates.isNotEmpty()) loadTemplates()
        if (vm.ui.destination == PoDestination.DeliveryAddresses || vm.ui.addresses.isNotEmpty()) loadAddresses()
    }

    /** Loads the address book for the form's picker, once. */
    fun ensureAddresses() {
        if (vm.ui.addresses.isEmpty() && !vm.ui.addressesLoading) loadAddresses()
    }

    private companion object {
        const val TEMPLATES_CACHE = "po.templates"
        const val ADDRESSES_CACHE = "po.addresses"
    }
}
