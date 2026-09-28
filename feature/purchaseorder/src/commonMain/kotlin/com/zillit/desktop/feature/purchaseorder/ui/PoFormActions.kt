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
import com.zillit.desktop.feature.purchaseorder.domain.addCivilMonths
import com.zillit.desktop.feature.purchaseorder.domain.isoDayNumber
import com.zillit.desktop.feature.purchaseorder.domain.toIsoDay
import com.zillit.desktop.feature.purchaseorder.domain.splitCadence
import com.zillit.desktop.feature.purchaseorder.domain.PoSplitType
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
            PoEvent.AddLine -> withForm { form ->
                form.copy(lines = form.lines + blankLine(nominalCode = form.nominalCode.takeIf { it.isNotBlank() }))
            }
            is PoEvent.RemoveLine -> withForm { form -> form.copy(lines = form.lines.without(event.index)) }
            is PoEvent.SplitLine -> withForm { form -> form.copy(lines = form.lines.splitLine(event.index)) }
            is PoEvent.SetLineAmount -> withForm { form ->
                form.copy(lines = form.lines.redistributeSplitAmount(event.index, event.amount))
            }
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
                    // The web pre-fills the sole company a production has,
                    // still clearable afterward (`POForm.jsx:278-290`).
                    companyId = state.companies.singleOrNull()?.id,
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
        // Guarded here as well as on the Edit button (PoDialogs.kt): the
        // amendment path re-checks again in refusesPrompt, but a plain,
        // not-yet-approved edit never goes through a confirm prompt at all.
        if (!PoAccess.canEdit(order, vm.ui.viewer, vm.ui.projectSettings.allowAmendAfterApproval)) {
            vm.fail(str(S.desktop_po_no_rights_on_project))
            return
        }
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
        val split = form.lines.splitByPeriod(index, vm.ui.projectSettings.splitCadence)
        if (split == null) {
            vm.fail(
                str(S.desktop_po_rental_window_too_short, vm.ui.projectSettings.splitCadence.label.lowercase()),
            )
            return
        }
        edit(form.copy(lines = split))
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
        // The commit is what writes: EditForm/SubmitForm can reach here with
        // any orderId the caller likes, not only one requestEdit() opened, so
        // an update is re-checked against the order as it now stands. Create
        // (orderId == null) stays open to everyone, as the web has it.
        val orderId = form.orderId
        if (orderId != null) {
            val order = vm.ui.orderById(orderId)
            val allowAmend = vm.ui.projectSettings.allowAmendAfterApproval
            if (order == null || !PoAccess.canEdit(order, vm.ui.viewer, allowAmend)) {
                vm.fail(str(S.desktop_po_no_rights_on_project))
                return
            }
        }
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
        // Vendor and description first, as the web's panel lists them.
        addAll(headerProblems(request, form, layout))
        request.validationError()?.let { add(it) }
        if (form.lines.any { it.isDivisibleRental && it.rentalEnd!! <= it.rentalStart!! }) {
            add(str(S.desktop_po_rental_end_after_start))
        }
        if (!layout.isLoaded) return@buildList
        val required = { label: String -> layout.isRequired(PoFormFields.DETAILS, label) }
        if (required(PoFormFields.ACCOUNT_CODE) && form.nominalCode.isBlank()) {
            add(str(S.desktop_po_requires_nominal_code))
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

/**
 * Splits a line into two children — or, clicking again on the parent or on any
 * existing child, adds one more child and rebalances every child evenly
 * across the parent's total. The web's "Split Line" (`lineItemSplit.js:33-77`
 * — `splitSelectedLineWith`), ported for a per-row button instead of the
 * web's select-a-line-then-click-one-button: the web resolves the *selected*
 * line up to its parent; this resolves whichever row's own button fired the
 * event. [index] is that row — parent or child, either can be clicked to
 * subdivide further.
 *
 * A childless parent that has never been split may still carry a null [PoLine.id]
 * ([blankLine] never assigns one); the first split gives it the synthetic
 * `"line-$index"` key so every later operation on this parent — a second
 * split, [redistributeSplitAmount], [rescaleSplitChildren] — has a stable,
 * non-null id to search [PoLine.splitParentId] against.
 */
@Suppress("ReturnCount") // Guard clauses over a lookup chain — nothing to fall through to.
internal fun List<PoLine>.splitLine(index: Int): List<PoLine> {
    val clicked = getOrNull(index) ?: return this
    val parentKey = clicked.splitParentId ?: clicked.id ?: "line-$index"
    val parentIndex = if (clicked.isSplitChild) indexOfFirst { it.id == parentKey } else index
    if (parentIndex < 0) return this
    val parent = this[parentIndex]
    if (parent.total <= 0) return this
    val pennies = kotlin.math.round(parent.total * PENNIES_PER_UNIT).toLong()
    val existingCount = count { it.splitParentId == parentKey }

    // First split: two even children, remainder on the second
    // (`lineItemSplit.js:51-58`) — clicked is necessarily the parent here,
    // since a line with no children yet cannot itself be a child.
    if (existingCount == 0) {
        val second = (pennies / 2) + (pennies % 2)
        val children = listOf(pennies - second, second).map { splitChildOf(parent, parentKey, it) }
        val withKey = if (parent.id == null) parent.copy(id = parentKey) else parent
        return take(parentIndex) + withKey + children + drop(parentIndex + 1)
    }

    // Subsequent split: one more child at the even share; every EXISTING
    // child rebalances to that same share, remainder on the last of them
    // (`lineItemSplit.js:60-76`) — the new child, inserted right after
    // whichever row was clicked, does not receive the remainder itself.
    val shareCount = existingCount + 1
    val per = pennies / shareCount
    val last = pennies - per * (shareCount - 1)
    var seen = 0
    val rebalanced = map { row ->
        if (row.splitParentId == parentKey) {
            val part = if (seen == existingCount - 1) last else per
            seen += 1
            row.penniesAsAmount(part)
        } else {
            row
        }
    }
    val newChild = splitChildOf(parent, parentKey, per)
    return rebalanced.take(index + 1) + newChild + rebalanced.drop(index + 1)
}

private fun splitChildOf(parent: PoLine, parentKey: String, pennies: Long): PoLine =
    parent.copy(id = null, splitParentId = parentKey).penniesAsAmount(pennies)

private fun PoLine.penniesAsAmount(pennies: Long): PoLine = copy(
    quantity = 1.0,
    unitPrice = pennies / PENNIES_PER_UNIT,
    amount = pennies / PENNIES_PER_UNIT,
)

/**
 * Editing a split child's own Amount cell — the web's `redistributeSplitAmountWith`
 * (`lineItemSplit.js:84-109`): the typed amount is this child's, and the
 * remainder of the parent's total spreads evenly across its siblings (2dp,
 * remainder on the last sibling) so the children keep summing to the parent
 * exactly. A no-op on anything that isn't a split child.
 */
internal fun List<PoLine>.redistributeSplitAmount(index: Int, newAmount: Double): List<PoLine> {
    val target = getOrNull(index) ?: return this
    val parentKey = target.splitParentId ?: return this
    val parent = firstOrNull { it.id == parentKey } ?: return this
    val siblingCount = count { it.splitParentId == parentKey } - 1
    val amountPennies = kotlin.math.round(newAmount * PENNIES_PER_UNIT).toLong()
    val parentPennies = kotlin.math.round(parent.total * PENNIES_PER_UNIT).toLong()
    val remaining = parentPennies - amountPennies
    val per = if (siblingCount > 0) remaining / siblingCount else 0L
    val last = if (siblingCount > 0) remaining - per * (siblingCount - 1) else 0L
    var seen = 0
    return mapIndexed { at, row ->
        when {
            at == index -> row.penniesAsAmount(amountPennies)
            row.splitParentId == parentKey -> {
                val part = if (seen == siblingCount - 1) last else per
                seen += 1
                row.penniesAsAmount(part)
            }
            else -> row
        }
    }
}

/**
 * The parent's own total moved (a quantity/unit-price edit on a line that
 * already has children) — rescale the children so they still sum to it,
 * keeping each child's existing SHARE of the total rather than flattening
 * them back to even. The web's `rescaleSplitChildrenWith`
 * (`lineItemSplit.js:134-158`), the counterpart of [redistributeSplitAmount]:
 * that one holds `Σ children == parent` when a child is edited, this one
 * holds it when the parent is. A no-op when [parentIndex]'s line has no
 * children, so callers can run it unconditionally after any edit that might
 * have changed a line's total.
 */
internal fun List<PoLine>.rescaleSplitChildren(parentIndex: Int): List<PoLine> {
    val parent = getOrNull(parentIndex) ?: return this
    val parentKey = parent.id ?: return this
    val children = filter { it.splitParentId == parentKey }
    if (children.isEmpty()) return this
    val targetPennies = kotlin.math.round(parent.total * PENNIES_PER_UNIT).toLong()
    val childPennies = children.map { kotlin.math.round(it.total * PENNIES_PER_UNIT).toLong() }
    val currentTotal = childPennies.sum()
    val shares = if (currentTotal > 0) {
        childPennies.map { it.toDouble() / currentTotal }
    } else {
        children.map { 1.0 / children.size }
    }
    val amounts = shares.map { kotlin.math.round(targetPennies * it).toLong() }.toMutableList()
    amounts[amounts.lastIndex] = targetPennies - amounts.dropLast(1).sum()
    var idx = 0
    return map { row ->
        if (row.splitParentId == parentKey) row.penniesAsAmount(amounts[idx++]) else row
    }
}

/**
 * Divides a rental line across the periods of its own window, [days] apiece —
 * the web's "Split by Period". Null when the window gives fewer than two
 * periods, which the caller reports rather than approximating.
 *
 * The remainder rides the last child, matching the web's own split
 * (`lineItemPeriods.jsx:197-201`), so the children still add to the parent to
 * the penny — a rental split that loses 2p reconciles wrong.
 */
internal fun List<PoLine>.splitByPeriod(index: Int, cadence: PoSplitType): List<PoLine>? {
    val parent = getOrNull(index)?.takeIf { it.isDivisibleRental } ?: return null
    val periods = periodsIn(parent, cadence)
    if (periods.size < 2) return null
    val parentKey = parent.id ?: "line-$index"
    val pennies = kotlin.math.round(parent.total * PENNIES_PER_UNIT).toLong()
    val each = pennies / periods.size
    val remainder = pennies - each * periods.size
    val children = periods.mapIndexed { position, window ->
        val part = each + if (position == periods.lastIndex) remainder else 0L
        parent.copy(
            id = null,
            quantity = 1.0,
            unitPrice = part / PENNIES_PER_UNIT,
            amount = part / PENNIES_PER_UNIT,
            rentalStart = window.first,
            rentalEnd = window.second,
            splitParentId = parentKey,
        )
    }
    val withKey = if (parent.id == null) parent.copy(id = parentKey) else parent
    return take(index) + withKey + children + drop(index + 1)
}

/** A list with one entry taken out, never emptied — a form with no lines has no add button. */
private fun List<PoLine>.without(index: Int): List<PoLine> {
    val remaining = filterIndexed { position, _ -> position != index }
    return remaining.ifEmpty { listOf(blankLine()) }
}

/**
 * Whether the form must refuse to raise an order without [field] (vendor or
 * description) — the web's `POForm.validate`, which checks a system field only
 * when the template **shows it and marks it required**.
 *
 * With the template read, that is the whole rule: a production whose Form
 * Configuration hides the vendor or the description gets no rule for a control
 * that is not on screen (hard-requiring both made such an order impossible to
 * raise). With no template read — the fetch failed, and the form is showing
 * every field — both stay required, because both controls are on screen and an
 * order with no vendor is stored with no vendor.
 */
internal fun FormLayout.requiresHeader(field: String): Boolean =
    if (isLoaded) isRequired(PoFormFields.DETAILS, field) else true

/**
 * The vendor and description refusals — only where the form can satisfy the
 * rule ([requiresHeader]). With no template read, the rule and its words are
 * the ones the order had before templates: a vendor *name*, and the two
 * "needs" messages.
 */
private fun headerProblems(request: NewPurchaseOrder, form: PoFormState, layout: FormLayout): List<String> =
    buildList {
        val loaded = layout.isLoaded
        val vendorMissing = if (loaded) form.vendorId.isNullOrBlank() else request.vendorName.isBlank()
        if (layout.requiresHeader(PoFormFields.VENDOR) && vendorMissing) {
            add(str(if (loaded) S.desktop_po_requires_vendor else S.desktop_po_needs_vendor))
        }
        if (layout.requiresHeader(PoFormFields.DESCRIPTION) && form.description.isBlank()) {
            add(str(if (loaded) S.desktop_po_requires_description else S.desktop_po_needs_description))
        }
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
 * The windows a rental line divides into at [cadence] — the web's
 * `computePeriodSplits` (`lineItemPeriods.jsx:139-205`).
 *
 * Dates are ISO days, the shape the editor and the wire both use. Every
 * cadence clips its last window to the line's own end rather than running
 * past it: a 10-day hire split weekly is 7 days and 3, not 7 and 7.
 */
@Suppress("ReturnCount") // One early return per way the line's own dates can fail to be a window.
internal fun periodsIn(line: PoLine, cadence: PoSplitType): List<Pair<String, String>> {
    val start = line.rentalStart ?: return emptyList()
    val end = line.rentalEnd ?: return emptyList()
    val startNum = start.isoDayNumber() ?: return emptyList()
    val endNum = end.isoDayNumber() ?: return emptyList()
    if (endNum <= startNum) return emptyList()
    return if (cadence == PoSplitType.Monthly) {
        monthlyPeriods(start, end, endNum)
    } else {
        dayPeriods(startNum, endNum, cadence.days)
    }
}

/**
 * Daily/Weekly/FourWeek — a fixed [step] days apiece, INCLUSIVE
 * (`[cursor, cursor + step - 1]`, clamped to the line's end), matching the
 * web's day-stepped cadences (`lineItemPeriods.jsx:150-162`) rather than the
 * half-open windows an exclusive `cursor..<cursor+step` would give.
 */
private fun dayPeriods(startNum: Int, endNum: Int, step: Int): List<Pair<String, String>> {
    if (step <= 0) return emptyList()
    val windows = mutableListOf<Pair<String, String>>()
    var cursor = startNum
    while (cursor <= endNum) {
        if (windows.size >= MAX_PERIODS) return emptyList()
        val periodEnd = minOf(cursor + step - 1, endNum)
        windows += cursor.toIsoDay() to periodEnd.toIsoDay()
        cursor += step
    }
    return windows
}

/**
 * Monthly — NOT a fixed day count. Genuine calendar-month windows anchored to
 * the rental start's own day-of-month (`lineItemPeriods.jsx:163-179`): a 15
 * Jan start steps 15 Feb, 15 Mar, … whatever the target month's length, via
 * [String.addCivilMonths]'s rollover. A trailing window covers whatever the
 * calendar-month diff undercounts — a 15 Jan → 20 Mar rental is 2 calendar
 * months (15 Jan–14 Mar) plus a 6-day tail (15–20 Mar); without it those last
 * six days were silently dropped (`lineItemPeriods.jsx:180-191`).
 */
@Suppress("ReturnCount") // The guard and each rollover failure mode return on their own line.
private fun monthlyPeriods(start: String, end: String, endNum: Int): List<Pair<String, String>> {
    val (startYear, startMonth) = start.take(ISO_YEAR_MONTH_LENGTH).split('-').let { it[0].toInt() to it[1].toInt() }
    val (endYear, endMonth) = end.take(ISO_YEAR_MONTH_LENGTH).split('-').let { it[0].toInt() to it[1].toInt() }
    val months = ((endYear - startYear) * MONTHS_PER_YEAR + (endMonth - startMonth)).coerceAtLeast(1)
    val windows = mutableListOf<Pair<String, String>>()
    for (i in 0 until months) {
        if (windows.size >= MAX_PERIODS) return emptyList()
        val periodStart = start.addCivilMonths(i) ?: return emptyList()
        val nextMonthStart = start.addCivilMonths(i + 1)?.isoDayNumber() ?: return emptyList()
        val periodEndNum = (nextMonthStart - 1).coerceAtMost(endNum)
        windows += periodStart to periodEndNum.toIsoDay()
    }
    val lastEndNum = windows.last().second.isoDayNumber() ?: return windows
    if (lastEndNum < endNum) {
        windows += (lastEndNum + 1).toIsoDay() to end
    }
    return windows
}

/** A guard, not a rule: a mis-typed year should not produce thousands of lines — the web's own MAX_PERIODS. */
private const val MAX_PERIODS = 400
private const val MONTHS_PER_YEAR = 12
private const val ISO_YEAR_MONTH_LENGTH = 7

/** Money is divided in pennies so the children always add back to the parent. */
private const val PENNIES_PER_UNIT = 100.0
