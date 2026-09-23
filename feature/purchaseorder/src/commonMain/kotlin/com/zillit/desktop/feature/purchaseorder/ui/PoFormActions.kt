package com.zillit.desktop.feature.purchaseorder.ui

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.forms.FormLayout
import com.zillit.desktop.core.forms.customValues
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.core.sync.LocalDraft
import com.zillit.desktop.feature.purchaseorder.domain.NewPurchaseOrder
import com.zillit.desktop.feature.purchaseorder.domain.PoAccess
import com.zillit.desktop.feature.purchaseorder.domain.PoAttachment
import com.zillit.desktop.feature.purchaseorder.domain.PoFormFields
import com.zillit.desktop.feature.purchaseorder.domain.PoLine
import com.zillit.desktop.feature.purchaseorder.domain.isoDayNumber
import com.zillit.desktop.feature.purchaseorder.domain.toIsoDay
import com.zillit.desktop.feature.purchaseorder.domain.splitCadence
import com.zillit.desktop.feature.purchaseorder.domain.PoTemplate
import com.zillit.desktop.feature.purchaseorder.domain.PurchaseOrder
import com.zillit.desktop.feature.purchaseorder.domain.PurchaseOrderRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay

/**
 * The Create / Edit PO form — the web's `POForm`, which takes over the page.
 *
 * One collaborator for five of the web's modes (new order, edit order, resume
 * draft, new template, edit template) because they differ only in where the
 * save goes; and one place for the two split actions, which are the part of
 * this form most easily got wrong.
 */
@Suppress("TooManyFunctions") // One private handler per control on the form; see the class note.
internal class PoFormActions(
    private val vm: PurchaseOrderViewModel,
    private val repository: PurchaseOrderRepository,
) {
    private var draftSaveJob: Job? = null

    @Suppress("CyclomaticComplexMethod") // One branch per control on the form.
    fun onEvent(event: PoEvent) {
        when (event) {
            PoEvent.CreateOrder -> openBlank()
            is PoEvent.EditOrder -> requestEdit(event.id)
            is PoEvent.ResumeDraft -> openOrder(event.id, PoFormMode.EditDraft)
            is PoEvent.UseTemplate -> openTemplate(event.id, asTemplate = false)
            is PoEvent.EditTemplate -> openTemplate(event.id, asTemplate = true)
            PoEvent.CreateTemplate -> vm.update {
                copy(form = PoFormState(mode = PoFormMode.NewTemplate, currency = defaultCurrency()))
            }

            is PoEvent.EditForm -> edit(event.form)
            PoEvent.AddLine -> withForm { form -> form.copy(lines = form.lines + blankLine()) }
            is PoEvent.RemoveLine -> withForm { form -> form.copy(lines = form.lines.without(event.index)) }
            is PoEvent.SplitLine -> withForm { form -> form.copy(lines = form.lines.splitEvenly(event.index)) }
            is PoEvent.SplitLineByPeriod -> splitByPeriod(event.index)
            PoEvent.AttachFile -> attach()
            is PoEvent.RemoveAttachment -> withForm { form ->
                form.copy(attachments = form.attachments.filterNot { it.media == event.attachment.media })
            }

            PoEvent.CloseForm -> vm.update { copy(form = null) }
            PoEvent.SubmitForm -> submit()
            PoEvent.SaveDraft -> saveDraft()
            is PoEvent.SaveAsTemplate -> saveTemplate(event.name)
            PoEvent.NameTemplate -> askTemplateName()
            is PoEvent.PickSavedAddress -> pickSavedAddress(event.id)
            else -> Unit
        }
    }

    // -- opening ---------------------------------------------------------------

    private fun openBlank() {
        val state = vm.ui
        vm.update {
            copy(
                form = PoFormState(
                    mode = PoFormMode.NewOrder,
                    // The person's own department is the default, as it is on
                    // both phones: most orders are raised for the department
                    // raising them, and a wrong default here routes the
                    // approval chain to the wrong people.
                    departmentId = state.viewer.departmentId,
                    currency = state.defaultCurrency(),
                ),
            )
        }
    }

    /**
     * Edit, asking first when it is an amendment.
     *
     * Editing a fully-approved order discards its approval chain and sends it
     * back through, so the web confirms before opening the form rather than
     * after the save — by which point the approvals are already gone.
     */
    private fun requestEdit(id: String) {
        val order = vm.ui.orderById(id) ?: return
        if (!PoAccess.isAmendment(order.status)) {
            openOrder(id, PoFormMode.EditOrder)
            return
        }
        vm.ask(
            PoPrompt.Confirm(
                action = PoConfirmAction.AmendOrder,
                targetId = id,
                title = str(S.desktop_po_amend_title),
                message = str(S.desktop_po_amend_message),
                destructive = true,
            ),
        )
    }

    /** Loads the order fresh, then fills the form from it. */
    fun openOrder(id: String, mode: PoFormMode) {
        vm.update { copy(busy = true) }
        vm.launchWork {
            when (val answer = repository.order(id)) {
                is ZillitResult.Success -> vm.update { copy(busy = false, form = answer.data.toForm(mode)) }
                is ZillitResult.Failure -> {
                    vm.update { copy(busy = false) }
                    vm.fail(answer.error.localised())
                }
            }
        }
    }

    /**
     * Opens the form from a saved shape.
     *
     * [asTemplate] decides which document the save writes: the Templates tab's
     * pencil edits the template itself, its "Use" raises an order from it. The
     * two are the same screen and the same values, which is why the web passes
     * a mode rather than building two.
     */
    private fun openTemplate(id: String, asTemplate: Boolean) {
        val template = vm.ui.templates.firstOrNull { it.id == id } ?: return
        vm.update {
            copy(
                form = PoFormState(
                    mode = if (asTemplate) PoFormMode.EditTemplate else PoFormMode.NewOrder,
                    templateId = template.id.takeIf { asTemplate },
                    templateName = template.name,
                    vendorId = template.vendorId,
                    vendorName = template.vendorName,
                    description = template.description,
                    departmentId = template.departmentId ?: viewer.departmentId,
                    nominalCode = template.nominalCode.orEmpty(),
                    currency = template.currency ?: defaultCurrency(),
                    notes = template.notes.orEmpty(),
                    // A template's lines carry no ids: they are a shape, and
                    // reusing the stored ids would make the server think it
                    // already has these lines.
                    lines = template.lines.map { it.copy(id = null) }.ifEmpty { listOf(blankLine()) },
                ),
            )
        }
    }

    // -- editing ---------------------------------------------------------------

    private fun edit(form: PoFormState) {
        vm.update { copy(form = form) }
        keepDraft(form)
    }

    private inline fun withForm(crossinline reducer: (PoFormState) -> PoFormState) {
        val current = vm.ui.form ?: return
        edit(reducer(current))
    }

    /**
     * Halves a line into two children — the web's "Split Line".
     *
     * The parent stays and keeps the description; the children carry half the
     * money each and a [PoLine.splitParentId] pointing at it, which is what
     * keeps every total on this tool from counting the money twice. An odd
     * penny goes to the first child, so the two children still add to the
     * parent exactly.
     */
    private fun List<PoLine>.splitEvenly(index: Int): List<PoLine> {
        val parent = getOrNull(index) ?: return this
        if (parent.isSplitChild) return this
        val parentKey = parent.id ?: "line-$index"
        val pennies = kotlin.math.round(parent.total * PENNIES_PER_UNIT).toLong()
        val first = (pennies / 2) + (pennies % 2)
        val children = listOf(first, pennies - first).map { part ->
            parent.copy(
                id = null,
                quantity = 1.0,
                unitPrice = part / 100.0,
                amount = part / 100.0,
                splitParentId = parentKey,
            )
        }
        val withKey = if (parent.id == null) parent.copy(id = parentKey) else parent
        return take(index) + withKey + children + drop(index + 1)
    }

    /**
     * Divides a rental line across the periods of its own window — the web's
     * "Split by Period".
     *
     * The cadence is the project's (`auto_split_rentals` on, then
     * `default_split_type`); with auto-split off it is monthly, which is the
     * legacy behaviour the web keeps. Refused rather than approximated when the
     * window is not divisible: the gate is [PoLine.isDivisibleRental], so the
     * button is never live on a window this cannot cut.
     */
    private fun splitByPeriod(index: Int) {
        val form = vm.ui.form ?: return
        val parent = form.lines.getOrNull(index) ?: return
        if (!parent.isDivisibleRental) {
            vm.fail(str(S.desktop_po_period_split_needs_rent))
            return
        }
        val periods = periodsIn(parent, vm.ui.projectSettings.splitCadence.days)
        if (periods.size < 2) {
            vm.fail(
                str(S.desktop_po_rental_window_too_short, vm.ui.projectSettings.splitCadence.label.lowercase()),
            )
            return
        }
        val parentKey = parent.id ?: "line-$index"
        val pennies = kotlin.math.round(parent.total * PENNIES_PER_UNIT).toLong()
        val each = pennies / periods.size
        // The remainder rides the first child so the children still add to the
        // parent to the penny — a rental split that loses 2p reconciles wrong.
        val remainder = pennies - each * periods.size
        val children = periods.mapIndexed { position, window ->
            val part = each + if (position == 0) remainder else 0L
            parent.copy(
                id = null,
                quantity = 1.0,
                unitPrice = part / 100.0,
                amount = part / 100.0,
                rentalStart = window.first,
                rentalEnd = window.second,
                splitParentId = parentKey,
            )
        }
        val withKey = if (parent.id == null) parent.copy(id = parentKey) else parent
        edit(form.copy(lines = form.lines.take(index) + withKey + children + form.lines.drop(index + 1)))
    }

    // -- attachments -----------------------------------------------------------

    /**
     * Picks a file and uploads it, adding it to the form once stored.
     *
     * Uploaded now rather than on save: the form can be open for a long time,
     * and a quote that fails to upload should say so while the person can still
     * choose another file.
     */
    private fun attach() {
        val files = vm.attachmentFiles
        if (files == null) {
            vm.fail(str(S.desktop_po_attachments_unavailable))
            return
        }
        vm.launchWork {
            val picked = files.pick { message -> vm.fail(message) } ?: return@launchWork
            vm.update { copy(form = form?.copy(uploading = true)) }
            when (val stored = files.upload(picked)) {
                is ZillitResult.Success -> vm.update {
                    copy(form = form?.copy(uploading = false, attachments = form.attachments + stored.data))
                }

                is ZillitResult.Failure -> {
                    vm.update { copy(form = form?.copy(uploading = false)) }
                    vm.fail(stored.error.localised())
                }
            }
        }
    }

    /** Fills the delivery block from the address book, or clears the link. */
    private fun pickSavedAddress(id: String?) {
        val saved = id?.let { key -> vm.ui.addresses.firstOrNull { it.id == key } }
        withForm { form ->
            form.copy(
                deliveryAddressId = saved?.id,
                deliveryAddress = saved?.address ?: form.deliveryAddress,
            )
        }
    }

    // -- saving ----------------------------------------------------------------

    private fun submit() {
        val form = vm.ui.form ?: return
        if (form.isTemplate) {
            saveTemplate(form.templateName)
            return
        }
        val layout = vm.ui.formLayout
        // Accounts raise straight into the ledger's queue; everyone else into
        // the approval chain — the same two statuses the phones send. An edit
        // sends no status at all, which leaves the order where it is.
        val status = when {
            form.mode == PoFormMode.EditOrder -> null
            vm.ui.viewer.isAccountant -> PurchaseOrderViewModel.STATUS_ACCOUNTS_ENTERED
            else -> PurchaseOrderViewModel.STATUS_PENDING
        }
        val request = form.toRequest(status, layout)
        val problems = validate(request, form, layout)
        if (problems.isNotEmpty()) {
            vm.update { copy(form = form.copy(problems = problems)) }
            return
        }
        send(form, request, str(S.desktop_order_raised))
    }

    /** Saves without submitting — the order waits as a draft. */
    private fun saveDraft() {
        val form = vm.ui.form ?: return
        val request = form.toRequest(PurchaseOrderViewModel.STATUS_DRAFT, vm.ui.formLayout)
        // A draft is deliberately not validated the way a submission is: the
        // point of it is to stop half way. Only a vendor-less, line-less,
        // description-less shell is refused, because there would be nothing to
        // come back to.
        if (request.vendorName.isBlank() && request.description.isBlank() && request.lines.isEmpty()) {
            vm.update { copy(form = form.copy(problems = listOf(str(S.desktop_po_needs_vendor_or_description)))) }
            return
        }
        send(form, request, str(S.ah_draft_saved_msg))
    }

    private fun send(form: PoFormState, request: NewPurchaseOrder, success: String) {
        val support = vm.offline
        if (support != null && support.isOffline && form.orderId == null) {
            vm.launchWork { vm.queueOrder(support, request) }
            return
        }
        vm.update { copy(form = form.copy(saving = true, problems = emptyList())) }
        vm.launchWork {
            val result = form.orderId
                ?.let { repository.update(it, request) }
                ?: repository.create(request)
            when (result) {
                is ZillitResult.Success -> {
                    forgetDraft()
                    vm.update { copy(form = null, notice = success, detail = null) }
                    vm.load(vm.ui.destination)
                }

                is ZillitResult.Failure -> {
                    // The request never left this machine: queue it rather than
                    // make the user retype it. Anything else — a timeout, a
                    // refusal — is reported, and the form keeps their words.
                    if (support != null && result.error is ZillitError.NoConnection && form.orderId == null) {
                        vm.queueOrder(support, request)
                    } else {
                        vm.update { copy(form = form.copy(saving = false)) }
                        vm.fail(result.error.localised())
                    }
                }
            }
        }
    }

    /** Asks what to call it, seeded with the order's description. */
    private fun askTemplateName() {
        val form = vm.ui.form ?: return
        vm.ask(
            PoPrompt.WithReason(
                action = PoReasonAction.NameTemplate,
                targetId = "",
                title = str(S.save_as_template),
                label = str(S.nda_template_name),
                reason = form.templateName.ifBlank { form.description.trim() },
            ),
        )
    }

    fun saveTemplate(name: String) {
        val form = vm.ui.form ?: return
        if (name.isBlank()) {
            vm.update { copy(form = form.copy(problems = listOf(str(S.ah_template_name_required)))) }
            return
        }
        vm.update { copy(form = form.copy(saving = true, problems = emptyList())) }
        vm.launchWork {
            val template = PoTemplate(
                id = form.templateId.orEmpty(),
                name = name.trim(),
                vendorId = form.vendorId,
                vendorName = form.vendorName,
                departmentId = form.departmentId,
                nominalCode = form.nominalCode.takeIf { it.isNotBlank() },
                description = form.description.trim(),
                currency = form.currency,
                notes = form.notes.takeIf { it.isNotBlank() },
                lines = form.lines.filter { it.description.isNotBlank() },
            )
            when (val answer = repository.saveTemplate(template)) {
                is ZillitResult.Success -> {
                    vm.update { copy(form = null, notice = str(S.ah_template_saved_msg)) }
                    vm.registerActions.loadTemplates()
                }

                is ZillitResult.Failure -> {
                    vm.update { copy(form = form.copy(saving = false)) }
                    vm.fail(answer.error.localised())
                }
            }
        }
    }

    /**
     * Every reason the form is refusing, together.
     *
     * Shown as a list rather than one at a time: the web's "Please fix the
     * following errors" panel, and the reason is that fixing four problems one
     * refusal at a time is four round trips through a long form.
     *
     * The production's own required-field rules are checked only for fields
     * this screen renders — a template can mark one required that this form
     * does not offer, and refusing over a control that is not on screen leaves
     * the person with nothing to put right. Those stay the server's to judge.
     */
    private fun validate(request: NewPurchaseOrder, form: PoFormState, layout: FormLayout): List<String> = buildList {
        request.validationError()?.let { add(it) }
        if (form.lines.any { it.isDivisibleRental && it.rentalEnd!! <= it.rentalStart!! }) {
            add(str(S.desktop_po_rental_end_after_start))
        }
        if (!layout.isLoaded) return@buildList
        val required = { label: String -> layout.isRequired(PoFormFields.DETAILS, label) }
        if (required(PoFormFields.VENDOR) && form.vendorId.isNullOrBlank()) {
            add(str(S.desktop_po_requires_vendor))
        }
        if (required(PoFormFields.ACCOUNT_CODE) && form.nominalCode.isBlank()) {
            add(str(S.desktop_po_requires_nominal_code))
        }
        if (required(PoFormFields.DESCRIPTION) && form.description.isBlank()) {
            add(str(S.desktop_po_requires_description))
        }
        if (required(PoFormFields.NOTES) && form.notes.isBlank()) {
            add(str(S.desktop_po_requires_note))
        }
        layout.missingCustom(PoFormFields.DETAILS, form.customFields).forEach { field ->
            add(str(S.desktop_po_field_required_on_orders, field.name))
        }
    }

    // -- the on-disk draft -----------------------------------------------------

    /**
     * Keeps the open form on disk as it is typed.
     *
     * Debounced: every keystroke changes it and the disk does not need to hear
     * each one. Only a *new* order is kept — an edit of a server-side order
     * would be restored over a row that may have moved on since.
     */
    private fun keepDraft(form: PoFormState) {
        val support = vm.offline ?: return
        if (form.mode != PoFormMode.NewOrder) return
        draftSaveJob?.cancel()
        draftSaveJob = vm.launchWork {
            delay(PurchaseOrderViewModel.DRAFT_SAVE_DEBOUNCE_MILLIS)
            val scope = support.currentScope() ?: return@launchWork
            val id = draftId(scope.userId, scope.projectId)
            if (form.isEmptyDraft) {
                support.drafts.delete(id)
            } else {
                support.drafts.save(
                    LocalDraft(
                        id = id,
                        scope = scope,
                        kind = PurchaseOrderViewModel.DRAFT_KIND,
                        payload = vm.json.encodeToString(PoStoredDraft.serializer(), form.toStored()),
                        updatedAt = vm.nowMillis(),
                    ),
                )
            }
        }
    }

    suspend fun restoreDraft() {
        val support = vm.offline ?: return
        val scope = support.currentScope() ?: return
        val saved = support.drafts.get(draftId(scope.userId, scope.projectId)) ?: return
        val stored = runCatching { vm.json.decodeFromString(PoStoredDraft.serializer(), saved.payload) }
            .getOrNull() ?: return
        // Only when nothing is open — a restore must never overwrite.
        if (vm.ui.form == null) vm.update { copy(form = stored.toForm()) }
    }

    suspend fun forgetDraft() {
        val support = vm.offline ?: return
        val scope = support.currentScope() ?: return
        draftSaveJob?.cancel()
        support.drafts.delete(draftId(scope.userId, scope.projectId))
    }

    private fun draftId(userId: String, projectId: String) = "po.draft:$userId:$projectId"

}

/** A list with one entry taken out, never emptied — a form with no lines has no add button. */
private fun List<PoLine>.without(index: Int): List<PoLine> {
    val remaining = filterIndexed { position, _ -> position != index }
    return remaining.ifEmpty { listOf(blankLine()) }
}

/** The production's default currency, or sterling where it has not said. */
internal fun PoUiState.defaultCurrency(): String = currencies.firstOrNull() ?: "GBP"

internal fun PoUiState.orderById(id: String): PurchaseOrder? =
    detail?.takeIf { it.id == id } ?: (localOrders + orders).firstOrNull { it.id == id }

/** The form's values as the create/update body wants them. */
internal fun PoFormState.toRequest(status: String?, layout: FormLayout) = NewPurchaseOrder(
    vendorId = vendorId,
    vendorName = vendorName.trim(),
    description = description.trim(),
    departmentId = departmentId,
    companyId = companyId,
    currency = currency,
    nominalCode = nominalCode.takeIf { it.isNotBlank() },
    episode = episode.takeIf { it.isNotBlank() },
    notes = notes.takeIf { it.isNotBlank() },
    effectiveDate = effectiveDate,
    lines = lines.filter { it.description.isNotBlank() },
    vatTreatment = vatTreatment.takeIf { it.isNotBlank() },
    deliveryDate = deliveryDate,
    deliveryAddressId = deliveryAddressId,
    deliveryAddress = deliveryAddress.takeIf { !it.isEmpty },
    attachments = attachments,
    status = status,
    customFields = listOfNotNull(layout.customValues(PoFormFields.DETAILS, customFields)),
)

/** An order, as the form that edits it. */
internal fun PurchaseOrder.toForm(mode: PoFormMode) = PoFormState(
    mode = mode,
    orderId = id,
    vendorId = vendorId,
    vendorName = vendorName,
    description = description,
    departmentId = departmentId,
    companyId = companyId,
    nominalCode = nominalCode.orEmpty(),
    currency = currency,
    episode = episode.orEmpty(),
    notes = notes.orEmpty(),
    effectiveDate = effectiveDate,
    deliveryDate = deliveryDate,
    vatTreatment = vatTreatment ?: "pending",
    deliveryAddressId = deliveryAddressId,
    lines = lines.ifEmpty { listOf(blankLine()) },
    attachments = attachments,
    customFields = customFields.flatMap { group -> group.fields }.associate { it.label to it.value },
)

/** Whether there is anything worth keeping on disk. */
internal val PoFormState.isEmptyDraft: Boolean
    get() = vendorId == null &&
        vendorName.isBlank() &&
        description.isBlank() &&
        notes.isBlank() &&
        nominalCode.isBlank() &&
        attachments.isEmpty() &&
        lines.all { it.description.isBlank() && it.total == 0.0 }

/**
 * The form as it waits on disk.
 *
 * Its own type rather than making [PoFormState] serialisable: the form also
 * carries what it is *doing* (saving, uploading, the problems it is showing),
 * and none of that should survive a restart.
 */
@kotlinx.serialization.Serializable
internal data class PoStoredDraft(
    val vendorId: String? = null,
    val vendorName: String = "",
    val description: String = "",
    val departmentId: String? = null,
    val companyId: String? = null,
    val nominalCode: String = "",
    val currency: String? = null,
    val episode: String = "",
    val notes: String = "",
    val effectiveDate: Long? = null,
    val lines: List<PoLine> = emptyList(),
    val attachments: List<PoAttachment> = emptyList(),
    val customFields: Map<String, String> = emptyMap(),
) {
    fun toForm() = PoFormState(
        mode = PoFormMode.NewOrder,
        vendorId = vendorId,
        vendorName = vendorName,
        description = description,
        departmentId = departmentId,
        companyId = companyId,
        nominalCode = nominalCode,
        currency = currency,
        episode = episode,
        notes = notes,
        effectiveDate = effectiveDate,
        lines = lines.ifEmpty { listOf(blankLine()) },
        attachments = attachments,
        customFields = customFields,
    )
}

internal fun PoFormState.toStored() = PoStoredDraft(
    vendorId = vendorId,
    vendorName = vendorName,
    description = description,
    departmentId = departmentId,
    companyId = companyId,
    nominalCode = nominalCode,
    currency = currency,
    episode = episode,
    notes = notes,
    effectiveDate = effectiveDate,
    lines = lines,
    attachments = attachments,
    customFields = customFields,
)

/**
 * The windows a rental line divides into, at [days] apiece.
 *
 * Dates are ISO days, the shape the editor and the wire both use. The last
 * window is clipped to the line's own end rather than running past it: a 10-day
 * hire split weekly is 7 days and 3, not 7 and 7.
 */
internal fun periodsIn(line: PoLine, days: Int): List<Pair<String, String>> {
    val start = line.rentalStart?.isoDayNumber() ?: return emptyList()
    val end = line.rentalEnd?.isoDayNumber() ?: return emptyList()
    if (end <= start || days <= 0) return emptyList()
    val windows = mutableListOf<Pair<String, String>>()
    var cursor = start
    while (cursor < end && windows.size < MAX_PERIODS) {
        val next = minOf(cursor + days, end)
        windows += cursor.toIsoDay() to next.toIsoDay()
        cursor = next
    }
    return windows
}

/** A guard, not a rule: a mis-typed year should not produce ten thousand lines. */
private const val MAX_PERIODS = 104

/** Money is divided in pennies so the children always add back to the parent. */
private const val PENNIES_PER_UNIT = 100.0
