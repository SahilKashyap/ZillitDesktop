// A template and the form that edits it or raises an order from it.
package com.zillit.desktop.feature.purchaseorder.ui

import com.zillit.desktop.core.forms.FormLayout
import com.zillit.desktop.core.forms.customValues
import com.zillit.desktop.feature.purchaseorder.domain.PoAddress
import com.zillit.desktop.feature.purchaseorder.domain.PoFormFields
import com.zillit.desktop.feature.purchaseorder.domain.PoTemplate

/**
 * The form as the template the web saves — every field it keeps, not just the
 * vendor and the lines. [asNew] drops the id so the save creates a template
 * instead of replacing the one being edited. Codes the [chart] lacks go out
 * wrapped, as on every other save.
 */
internal fun PoFormState.toTemplate(
    name: String,
    asNew: Boolean,
    layout: FormLayout,
    chart: Set<String> = emptySet(),
) = PoTemplate(
    id = if (asNew) "" else templateId.orEmpty(),
    name = name,
    vendorId = vendorId,
    vendorName = vendorName,
    departmentId = departmentId,
    nominalCode = wrappedNominal(chart),
    description = description.trim(),
    currency = currency,
    notes = notes.takeIf { it.isNotBlank() },
    lines = wrappedLines(chart),
    companyId = companyId,
    episode = episode.takeIf { it.isNotBlank() },
    deliveryAddressId = deliveryAddressId,
    deliveryAddress = deliveryAddress.takeIf { !it.isEmpty },
    deliveryDate = deliveryDate,
    effectiveDate = effectiveDate,
    vatTreatment = vatTreatment.takeIf { it.isNotBlank() },
    customFields = listOfNotNull(layout.customValues(PoFormFields.DETAILS, customFields)),
    attachments = attachments,
)

/**
 * A template, as the form that edits it or raises an order from it.
 *
 * The editor ([asTemplate]) gets the whole header back, and the lines with the
 * ids they were saved with — a split child names its parent by id, and a
 * template saved without them would come back as loose lines. "Use" is the
 * web's narrower restore (see [PoFormActions.openTemplate]) and its lines carry
 * no ids: they are a shape, and reusing the stored ids would make the server
 * think it already has these lines.
 *
 * A saved-address link that is no longer in the book is dropped and the typed
 * snapshot kept, as the web's re-select does when the entry was deleted; with
 * no book read yet ([savedAddressIds] empty) the link is kept, not guessed at.
 */
internal fun PoTemplate.toForm(
    asTemplate: Boolean,
    fallbackDepartmentId: String?,
    fallbackCurrency: String,
    savedAddressIds: Set<String> = emptySet(),
): PoFormState {
    val shape = PoFormState(
        mode = if (asTemplate) PoFormMode.EditTemplate else PoFormMode.NewOrder,
        templateId = id.takeIf { asTemplate },
        templateName = name,
        vendorId = vendorId,
        vendorName = vendorName,
        description = description,
        departmentId = departmentId ?: fallbackDepartmentId,
        nominalCode = nominalCode.orEmpty(),
        currency = currency ?: fallbackCurrency,
        notes = notes.orEmpty(),
        lines = lines.map { if (asTemplate) it else it.copy(id = null) }.ifEmpty { listOf(blankLine()) },
    )
    if (!asTemplate) return shape
    return shape.copy(
        companyId = companyId,
        episode = episode.orEmpty(),
        effectiveDate = effectiveDate,
        deliveryDate = deliveryDate,
        vatTreatment = vatTreatment ?: "pending",
        deliveryAddressId = deliveryAddressId?.takeIf { savedAddressIds.isEmpty() || it in savedAddressIds },
        deliveryAddress = deliveryAddress ?: PoAddress(),
        attachments = attachments,
        customFields = customFields.flatMap { group -> group.fields }.associate { it.label to it.value },
    )
}
