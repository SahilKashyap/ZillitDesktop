package com.zillit.desktop.feature.accounthub.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.accounthub.domain.AgreementDocument
import com.zillit.desktop.feature.accounthub.domain.AgreementFiles
import com.zillit.desktop.feature.accounthub.domain.SetupUpload

/**
 * Picking, uploading and removing the production's agreement documents.
 *
 * Its own collaborator for the reason [SetupSections] and [VendorActions] are.
 *
 * The upload is two steps and both must be reported honestly: the bytes go to
 * S3 one file at a time, then one call appends what actually landed. A file
 * that fails to upload is dropped from the batch and named, rather than
 * failing the whole queue — the web does the same, and losing four good
 * uploads to one bad one is a worse answer than a partial success the user
 * can see.
 */
internal class AgreementActions(
    private val vm: AccountHubViewModel,
    private val files: AgreementFiles?,
) {

    fun load() {
        vm.update { copy(setup = setup.copy(agreementsLoading = true)) }
        vm.runResult(vm.repo::agreementDocuments, { rows ->
            vm.update { copy(setup = setup.copy(agreements = rows, agreementsLoading = false)) }
        }, { error ->
            vm.update { copy(setup = setup.copy(agreementsLoading = false)) }
            vm.report(error)
        })
    }

    fun onEvent(event: AccountHubEvent): Boolean {
        when (event) {
            AccountHubEvent.PickAgreementFiles -> pick()
            is AccountHubEvent.EditAgreementQueue ->
                vm.update { copy(setup = setup.copy(agreementQueue = event.queue)) }
            AccountHubEvent.UploadAgreementFiles -> upload()
            is AccountHubEvent.DeleteAgreementDocument -> delete(event.id)
            is AccountHubEvent.OpenAgreementDocument -> open(event.id)
            else -> return false
        }
        return true
    }

    /** Opens a stored document in the OS — by presigned URL, as both phones do. */
    private fun open(id: String) {
        val opener = vm.documentOpener ?: return
        val document = vm.setupState.setup.agreements.firstOrNull { it.id == id } ?: return
        vm.launchWork {
            when (val opened = opener.open(document)) {
                is ZillitResult.Failure -> vm.report(opened.error)
                is ZillitResult.Success -> Unit
            }
        }
    }

    /**
     * The single terms document a purchase order is issued with.
     *
     * One file, uploaded straight away rather than queued: there is one slot,
     * so there is nothing to name or describe first and nothing to batch.
     */
    fun pickPoTerms() {
        val source = files ?: return
        if (!vm.mayEdit()) return
        vm.launchWork {
            // Refusals and failures show under the block, as the web's do,
            // rather than as a toast over it; the next attempt clears them.
            vm.update { copy(setup = setup.copy(poTermsError = null)) }
            val picked = source.pick(
                SetupUpload.PurchaseOrderTerms,
                multiple = false,
            ) { refusal ->
                vm.update { copy(setup = setup.copy(poTermsError = refusal)) }
            }.firstOrNull() ?: return@launchWork
            vm.update { copy(setup = setup.copy(poTermsUploading = true)) }
            when (val stored = source.upload(picked, caption = "", purpose = SetupUpload.PurchaseOrderTerms)) {
                is ZillitResult.Success -> vm.update {
                    copy(
                        setup = setup.copy(
                            poSetup = setup.poSetup.edit(
                                setup.poSetup.edited.copy(
                                    termsDocument = stored.data.copy(title = picked.name),
                                ),
                            ),
                            poTermsUploading = false,
                        ),
                    )
                }
                is ZillitResult.Failure -> vm.update {
                    copy(setup = setup.copy(poTermsUploading = false, poTermsError = UPLOAD_FAILED))
                }
            }
        }
    }

    /**
     * Opens the terms document in the OS — the web's View, which shows a PDF
     * inline and downloads a Word file. Works on a just-uploaded, not-yet-saved
     * document too: the file is already in storage by then.
     */
    fun openPoTerms() {
        val document = vm.setupState.setup.poSetup.edited.termsDocument ?: return
        if (!document.openable) {
            vm.update { copy(setup = setup.copy(poTermsError = METADATA_MISSING)) }
            return
        }
        val opener = vm.documentOpener ?: return
        vm.update { copy(setup = setup.copy(poTermsOpening = true, poTermsError = null)) }
        vm.launchWork {
            val opened = opener.open(document)
            vm.update {
                copy(
                    setup = setup.copy(
                        poTermsOpening = false,
                        poTermsError = if (opened is ZillitResult.Failure) OPEN_FAILED else null,
                    ),
                )
            }
        }
    }

    private fun pick() {
        val source = files ?: return
        if (!vm.mayEdit()) return
        vm.launchWork {
            val picked = source.pick(SetupUpload.Agreement, multiple = true) { refusal ->
                vm.sendSideEffect(AccountHubEffect.Failed(refusal))
            }
            if (picked.isEmpty()) return@launchWork
            vm.update {
                copy(setup = setup.copy(agreementQueue = setup.agreementQueue + picked.map { QueuedAgreementFile(it) }))
            }
        }
    }

    private fun upload() {
        val source = files ?: return
        if (!vm.mayEdit()) return
        val queue = vm.setupState.setup.agreementQueue
        if (queue.isEmpty()) return
        vm.update { copy(setup = setup.copy(agreementsUploading = true)) }
        vm.launchWork {
            val stored = mutableListOf<AgreementDocument>()
            val failed = mutableListOf<String>()
            queue.forEach { row ->
                when (val result = source.upload(row.file, row.description)) {
                    is ZillitResult.Success ->
                        stored += result.data.copy(title = row.title, description = row.description)
                    is ZillitResult.Failure -> failed += row.file.name
                }
            }
            if (stored.isEmpty()) {
                vm.update { copy(setup = setup.copy(agreementsUploading = false)) }
                vm.sendSideEffect(AccountHubEffect.Failed(failureMessage(failed)))
                return@launchWork
            }
            append(stored, failed)
        }
    }

    /** The rows landed in S3; this is the call that makes them the production's. */
    private fun append(stored: List<AgreementDocument>, failed: List<String>) {
        vm.runResult({ vm.repo.addAgreementDocuments(stored) }, { rows ->
            vm.update {
                copy(
                    setup = setup.copy(
                        // The server answers with the whole list; an empty
                        // answer would blank a list that just grew, so what is
                        // already known wins over nothing.
                        agreements = rows.ifEmpty { setup.agreements + stored },
                        agreementQueue = emptyList(),
                        agreementsUploading = false,
                    ),
                    notice = "${stored.size} document(s) added.",
                )
            }
            if (failed.isNotEmpty()) vm.sendSideEffect(AccountHubEffect.Failed(failureMessage(failed)))
        }, { error ->
            vm.update { copy(setup = setup.copy(agreementsUploading = false)) }
            vm.report(error)
        })
    }

    private fun delete(id: String) {
        if (!vm.mayEdit()) return
        vm.runResult({ vm.repo.deleteAgreementDocument(id) }, {
            vm.update {
                copy(
                    setup = setup.copy(agreements = setup.agreements.filterNot { it.id == id }),
                    notice = "Document removed.",
                )
            }
        }, vm::report)
    }

    private fun failureMessage(failed: List<String>): String =
        "Could not upload: " + failed.joinToString(", ")
}

// The web's `TermsDocumentSection` copy, verbatim.
private const val UPLOAD_FAILED = "Upload failed — please try again"
private const val METADATA_MISSING = "Can't open — attachment metadata missing"
private const val OPEN_FAILED = "Couldn't open the document — please try again"
