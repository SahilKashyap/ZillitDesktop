@file:Suppress("TooManyFunctions") // One handler per user act.

package com.zillit.desktop.feature.invoices.ui

import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.mvvm.ZillitViewModel
import com.zillit.desktop.feature.invoices.domain.ApprovalChain
import com.zillit.desktop.feature.invoices.domain.DepartmentUpload
import com.zillit.desktop.feature.invoices.domain.EnteredInvoice
import com.zillit.desktop.feature.invoices.domain.Invoice
import com.zillit.desktop.feature.invoices.domain.InvoiceFiles
import com.zillit.desktop.feature.invoices.domain.InvoiceFormat
import com.zillit.desktop.feature.invoices.domain.InvoiceQuery
import com.zillit.desktop.feature.invoices.domain.InvoiceRefresh
import com.zillit.desktop.feature.invoices.domain.InvoiceStatus
import com.zillit.desktop.feature.invoices.domain.InvoiceViewer
import com.zillit.desktop.feature.invoices.domain.InvoicesRepository
import com.zillit.desktop.feature.invoices.domain.PickedInvoiceFile
import com.zillit.desktop.feature.invoices.domain.ResolvedTier
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay

/**
 * Invoices (Accounts Payable): the department view — my uploads, my
 * department's, and the ones waiting on me — or, for the accounts
 * department, the register, the inbox and the approval queue.
 */
class InvoicesViewModel(
    private val repository: InvoicesRepository,
    private val files: InvoiceFiles,
    private val resolveViewer: () -> InvoiceViewer,
    private val projectCurrency: () -> String,
    private val resolveUser: (String) -> String?,
    private val departmentName: (String) -> String?,
    private val nowMillis: () -> Long,
    /** Every department of the production, id → name; the Enter form's picker. */
    private val departments: () -> Map<String, String> = { emptyMap() },
) : ZillitViewModel<InvoicesUiState, InvoicesEvent, InvoicesEffect>(InvoicesUiState()) {

    private val actions = InvoiceActions(this)
    private val forms = InvoiceForms(this)

    /** Bumped per list load so a late answer for the previous tab is dropped. */
    private var loadToken = 0

    fun start() {
        setState {
            copy(
                viewer = resolveViewer(),
                projectCurrency = projectCurrency().ifBlank { "GBP" },
                departmentNames = departmentNames + departments(),
            )
        }
        loadReference()
        listenOnce()
        refresh()
    }

    /**
     * Folds the socket's announcements into the screen: another client's
     * upload, decision or payment lands as a refetch of whatever tab is open
     * (and a re-read of an open detail), and vendor / tier / settings changes
     * re-pull the reference data — the web's `ah:invoice:*` refetch pattern.
     * Guarded so reopening the window does not stack collectors, and debounced
     * per kind because one action fans into several frames (the web coalesces
     * at `accountHubListeners.js` `DEBOUNCE_MS = 500`).
     */
    private fun listenOnce() {
        if (listening) return
        listening = true
        launch {
            repository.refreshes.collect { kind ->
                syncJobs.remove(kind)?.cancel()
                syncJobs[kind] = launch {
                    delay(SYNC_DEBOUNCE_MILLIS)
                    when (kind) {
                        InvoiceRefresh.Rows -> {
                            refresh()
                            state.value.detail?.invoice?.id?.let(::refreshDetail)
                        }
                        InvoiceRefresh.Reference -> loadReference()
                    }
                }
            }
        }
    }

    private var listening = false
    private val syncJobs = mutableMapOf<InvoiceRefresh, Job>()

    @Suppress("CyclomaticComplexMethod", "LongMethod") // Event fan-out.
    override fun onEvent(event: InvoicesEvent) {
        when (event) {
            is InvoicesEvent.SelectDepartmentTab -> {
                setState { copy(departmentTab = event.tab, invoices = emptyList(), selected = emptySet()) }
                refresh()
            }
            is InvoicesEvent.SelectQuickFilter -> setState { copy(quickFilter = event.filter) }
            is InvoicesEvent.SelectPage -> {
                setState { copy(page = event.page, invoices = emptyList(), selected = emptySet(), search = "") }
                refresh()
            }
            is InvoicesEvent.SelectRegisterChip -> setState { copy(registerChip = event.chip) }
            is InvoicesEvent.SelectRegisterDepartment -> setState { copy(registerDepartment = event.departmentId) }
            is InvoicesEvent.Search -> setState { copy(search = event.query) }
            InvoicesEvent.Refresh -> refresh()
            InvoicesEvent.DismissError -> setState { copy(error = null) }

            is InvoicesEvent.Open -> open(event.invoice)
            InvoicesEvent.CloseDetail -> setState { copy(detail = null) }
            InvoicesEvent.OpenAttachment -> openAttachment()
            InvoicesEvent.ShowHistory -> showHistory()
            InvoicesEvent.HideHistory -> setState { copy(detail = detail?.copy(historyOpen = false)) }

            is InvoicesEvent.Approve -> actions.approve(event.invoice)
            InvoicesEvent.StartReject -> setState { copy(detail = detail?.copy(rejecting = true, rejectReason = "")) }
            is InvoicesEvent.RejectReasonChanged -> setState {
                copy(detail = detail?.copy(rejectReason = event.reason))
            }
            InvoicesEvent.ConfirmReject -> actions.reject()
            InvoicesEvent.CancelReject -> setState { copy(detail = detail?.copy(rejecting = false)) }
            is InvoicesEvent.Override -> actions.override(event.invoice)
            is InvoicesEvent.OverrideAndPay -> actions.overrideAndPay(event.invoice)
            is InvoicesEvent.Chase -> actions.chase(event.invoice)
            is InvoicesEvent.ToggleSelect -> setState {
                copy(selected = if (event.id in selected) selected - event.id else selected + event.id)
            }
            InvoicesEvent.ClearSelection -> setState { copy(selected = emptySet()) }
            InvoicesEvent.ApproveSelected -> actions.approveSelected()

            is InvoicesEvent.RequestDelete -> setState { copy(confirmDelete = event.invoice) }
            InvoicesEvent.ConfirmDelete -> actions.deleteConfirmed()
            InvoicesEvent.CancelDelete -> setState { copy(confirmDelete = null) }

            InvoicesEvent.UploadInvoice -> forms.uploadInvoice()
            is InvoicesEvent.ChooseUploadType -> setState { copy(upload = upload?.copy(type = event.type)) }
            InvoicesEvent.SendUpload -> forms.sendUpload()
            InvoicesEvent.CancelUpload -> setState { copy(upload = null) }

            InvoicesEvent.OpenEnter -> forms.openEnter()
            InvoicesEvent.CloseEnter -> setState { copy(enter = null) }
            is InvoicesEvent.SelectEnterTab -> setState { copy(enter = enter?.copy(tab = event.tab, error = null)) }
            InvoicesEvent.EnterPickFile -> forms.enterPickFile()
            is InvoicesEvent.EnterChanged -> setState { copy(enter = event.form.copy(error = null)) }
            is InvoicesEvent.EnterNetChanged -> forms.netChanged(event.value)
            is InvoicesEvent.EnterTaxChanged -> forms.taxChanged(event.value)
            is InvoicesEvent.EnterGrossChanged -> forms.grossChanged(event.value)
            InvoicesEvent.SubmitEnter -> forms.submitEnter()
        }
    }

    // -- loading -------------------------------------------------------------

    private fun loadReference() {
        launch {
            when (val r = repository.settings()) {
                // Older backends have no settings; the buttons fall back to designation.
                is ZillitResult.Failure -> Unit
                is ZillitResult.Success -> setState { copy(viewer = viewer.withSettings(r.data)) }
            }
        }
        launch {
            when (val r = repository.approvalTiers()) {
                is ZillitResult.Failure -> setState {
                    copy(error = "Could not load approval tiers: ${r.error.localised()}")
                }
                is ZillitResult.Success -> setState { copy(tierConfigs = r.data) }
            }
        }
        launch {
            (repository.vendors() as? ZillitResult.Success)?.let { r ->
                setState { copy(vendors = r.data.associateBy { it.id }) }
            }
        }
        launch { (repository.bankAccounts() as? ZillitResult.Success)?.let { r -> setState { copy(banks = r.data) } } }
    }

    internal fun refresh() {
        val s = state.value
        val token = ++loadToken
        setState { copy(loading = true) }
        launch {
            val result = if (s.isAccountant) {
                when (s.page) {
                    AccountantPage.Register -> repository.list(InvoiceQuery())
                    AccountantPage.Inbox -> repository.list(InvoiceQuery(statuses = listOf(InvoiceStatus.Inbox)))
                    AccountantPage.ApprovalQueue ->
                        repository.list(InvoiceQuery(statuses = listOf(InvoiceStatus.Approval, InvoiceStatus.Rejected)))
                }
            } else {
                when (s.departmentTab) {
                    DepartmentTab.ApprovalQueue -> repository.approvalQueue()
                    DepartmentTab.MyDepartment -> if (s.viewer.departmentId.isBlank()) {
                        ZillitResult.Success(emptyList())
                    } else {
                        repository.list(InvoiceQuery(departmentId = s.viewer.departmentId))
                    }
                    DepartmentTab.MyInvoices -> repository.mine()
                }
            }
            if (token != loadToken) return@launch
            when (result) {
                is ZillitResult.Failure -> setState { copy(loading = false, error = result.error.localised()) }
                is ZillitResult.Success -> setState {
                    val ids = result.data.map { it.id }.toSet()
                    copy(
                        loading = false,
                        invoices = result.data,
                        departmentNames = departmentNames + namesOfDepartments(result.data),
                        selected = selected.filter { it in ids }.toSet(),
                    )
                }
            }
        }
    }

    private fun namesOfDepartments(rows: List<Invoice>): Map<String, String> =
        rows.map { it.departmentId }.filter { it.isNotBlank() }.distinct()
            .mapNotNull { id -> departmentName(id)?.let { id to it } }.toMap()

    // -- detail --------------------------------------------------------------

    private fun open(invoice: Invoice) {
        setState { copy(detail = InvoiceDetail(invoice = invoice, names = namesFor(invoice))) }
        launch {
            when (val r = repository.invoice(invoice.id)) {
                is ZillitResult.Failure -> setState {
                    copy(detail = detail?.copy(loading = false), error = r.error.localised())
                }
                is ZillitResult.Success -> {
                    setState {
                        val d = detail?.takeIf { it.invoice.id == invoice.id } ?: return@setState this
                        copy(detail = d.copy(invoice = r.data, loading = false, names = d.names + namesFor(r.data)))
                    }
                    loadPreview(r.data)
                }
            }
        }
    }

    /** Re-reads an open detail after a mutation so the chain and status are current. */
    internal fun refreshDetail(id: String) {
        if (state.value.detail?.invoice?.id != id) return
        launch {
            (repository.invoice(id) as? ZillitResult.Success)?.let { r ->
                setState {
                    val d = detail?.takeIf { it.invoice.id == id } ?: return@setState this
                    copy(detail = d.copy(invoice = r.data, names = d.names + namesFor(r.data)))
                }
            }
        }
    }

    private fun loadPreview(invoice: Invoice) {
        val attachment = invoice.firstAttachment?.takeIf { it.isImage } ?: return
        setState { copy(detail = detail?.copy(previewLoading = true)) }
        launch {
            val r = files.fetch(attachment)
            setState {
                val d = detail?.takeIf { it.invoice.id == invoice.id } ?: return@setState this
                copy(
                    detail = when (r) {
                        is ZillitResult.Success -> d.copy(preview = AttachmentBytes(r.data), previewLoading = false)
                        is ZillitResult.Failure -> d.copy(previewLoading = false, previewFailed = true)
                    },
                )
            }
        }
    }

    private fun openAttachment() {
        val d = state.value.detail ?: return
        val attachment = d.invoice.firstAttachment ?: return
        setState { copy(detail = detail?.copy(opening = true)) }
        launch {
            val bytes = d.preview?.bytes?.let { ZillitResult.Success(it) } ?: files.fetch(attachment)
            val outcome = when (bytes) {
                is ZillitResult.Failure -> bytes
                is ZillitResult.Success -> files.saveAndOpen(
                    attachment.name.ifBlank { "invoice.${attachment.extension}" },
                    bytes.data,
                )
            }
            val failure = (outcome as? ZillitResult.Failure)?.error?.userMessage
            setState { copy(detail = detail?.copy(opening = false), error = failure) }
            if (outcome is ZillitResult.Success) sendEffect(InvoicesEffect.Notice("Saved to Downloads"))
        }
    }

    private fun showHistory() {
        val d = state.value.detail ?: return
        setState { copy(detail = detail?.copy(historyOpen = true, historyLoading = detail.history == null)) }
        if (d.history != null) return
        launch {
            when (val r = repository.history(d.invoice.id)) {
                is ZillitResult.Failure -> setState {
                    copy(detail = detail?.copy(historyLoading = false), error = r.error.localised())
                }
                is ZillitResult.Success -> setState {
                    val current = detail?.takeIf { it.invoice.id == d.invoice.id } ?: return@setState this
                    val actors = r.data.map { it.actionBy }.filter { it.isNotBlank() }.distinct()
                        .mapNotNull { id -> resolveUser(id)?.let { id to it } }
                    copy(
                        detail = current.copy(history = r.data, historyLoading = false, names = current.names + actors),
                    )
                }
            }
        }
    }

    // -- shared helpers for the action/form helpers ----------------------------

    internal fun tiersFor(invoice: Invoice): List<ResolvedTier> = ApprovalChain.tiersFor(
        state.value.tierConfigs,
        invoice,
    )

    internal fun namesFor(invoice: Invoice): Map<String, String> {
        val ids = buildSet {
            add(invoice.userId)
            add(invoice.updatedBy)
            add(invoice.rejectedBy)
            invoice.approvals.forEach { add(it.userId) }
            tiersFor(invoice).forEach { addAll(it.userIds) }
        }.filter { it.isNotBlank() }
        return ids.mapNotNull { id -> resolveUser(id)?.let { id to it } }.toMap()
    }

    internal fun update(reducer: InvoicesUiState.() -> InvoicesUiState) = setState(reducer)

    internal fun notice(text: String) = sendEffect(InvoicesEffect.Notice(text))

    internal fun run(block: suspend () -> Unit) = launch { block() }

    internal fun now(): Long = nowMillis()

    internal suspend fun pickOne(): PickedInvoiceFile? = files.pick().firstOrNull()

    internal suspend fun upload(file: PickedInvoiceFile) = files.upload(file)

    internal val repo: InvoicesRepository get() = repository

    internal fun matchVendor(name: String): String? {
        val needle = name.trim().lowercase()
        if (needle.isEmpty()) return null
        val vendors = state.value.vendors.values
        val exact = vendors.firstOrNull { it.name.trim().lowercase() == needle }
        return (exact ?: vendors.firstOrNull { it.name.lowercase().contains(needle) })?.id
    }

    internal fun departmentUpload(flow: UploadFlow): DepartmentUpload = DepartmentUpload(
        type = flow.type ?: error("type chosen"),
        fileName = flow.file.name,
        attachment = flow.attachment,
        extraction = flow.extraction,
        departmentId = state.value.viewer.departmentId.ifBlank { null },
        projectCurrency = state.value.projectCurrency,
    )

    internal fun entered(form: EnterInvoiceForm): EnteredInvoice? {
        val attachment = form.attachment ?: return null
        val invoiceDate = InvoiceFormat.parseDateInput(form.invoiceDate) ?: return null
        val gross = form.grossValue ?: return null
        return EnteredInvoice(
            attachment = attachment,
            invoiceNumber = form.invoiceNumber.trim(),
            vendorId = form.vendorId,
            description = form.description.trim(),
            grossAmount = gross,
            invoiceDateMs = invoiceDate,
            dueDateMs = InvoiceFormat.parseDateInput(form.dueDate) ?: (nowMillis() + DEFAULT_TERMS_MS),
            effectiveDateMs = InvoiceFormat.parseDateInput(form.effectiveDate),
            payMethod = form.payMethod,
            currency = form.currency.trim().uppercase().ifBlank { state.value.projectCurrency },
            netAmount = form.netValue,
            taxAmount = form.taxValue,
            bankId = form.bankId.ifBlank { null },
            episode = form.episode.trim().ifBlank { null },
            departmentId = form.departmentId.ifBlank { null },
            poNumber = form.poNumber.trim().ifBlank { null },
            uploadId = form.extraction?.uploadId?.ifBlank { null },
        )
    }

    companion object {
        const val MAX_FILE_BYTES = 10L * 1024 * 1024
        val UPLOAD_EXTENSIONS = setOf("pdf", "jpg", "jpeg", "png")
        val ENTER_EXTENSIONS = UPLOAD_EXTENSIONS + setOf("doc", "docx")
        private const val DEFAULT_TERMS_MS = 30L * 86_400_000L

        /** The web's refetch coalescing window — accountHubListeners.js `DEBOUNCE_MS`. */
        const val SYNC_DEBOUNCE_MILLIS = 500L
    }
}
