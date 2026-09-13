package com.zillit.desktop.feature.accounthub.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.feature.accounthub.domain.AgreementDocument
import com.zillit.desktop.feature.accounthub.domain.AgreementFiles
import com.zillit.desktop.feature.accounthub.domain.BudgetImportMeta
import com.zillit.desktop.feature.accounthub.domain.BudgetImports
import com.zillit.desktop.feature.accounthub.domain.PickedAgreementFile
import com.zillit.desktop.feature.accounthub.domain.SetupUpload

/**
 * Importing a budget file — the web's `ImportBudgetWizard`.
 *
 * Upload, Preview, Commit. Choosing a file only stages it; "Parse file"
 * uploads it and asks the server what it makes of it, writing nothing. The
 * review is the point: the parse is a guess at somebody else's spreadsheet,
 * and the commit writes codes into the chart of accounts every other tool
 * codes against.
 */
@Suppress("TooManyFunctions") // One handler per step of the wizard.
internal class BudgetImportActions(
    private val vm: AccountHubViewModel,
    private val files: AgreementFiles?,
) {

    /** Whether the host wired storage; without it the import cannot start. */
    val isAvailable: Boolean get() = files != null

    /** Whether a file can be dragged onto the upload step. */
    val acceptsDrops: Boolean get() = files?.acceptsDrops == true

    fun onEvent(event: AccountHubEvent): Boolean {
        when (event) {
            AccountHubEvent.OpenBudgetImport -> open()
            AccountHubEvent.CloseBudgetImport -> updateImport { BudgetImportState() }
            AccountHubEvent.PickBudgetFile -> pick()
            is AccountHubEvent.DropBudgetFile -> drop(event.name, event.bytes)
            AccountHubEvent.ParseBudgetFile -> parse()
            AccountHubEvent.BackToBudgetUpload -> updateImport {
                copy(step = ImportStep.Upload, commitError = null)
            }
            is AccountHubEvent.EditBudgetImportMeta -> updateImport { copy(meta = event.meta) }
            is AccountHubEvent.SetCoaImportMode -> updateImport { copy(mode = event.mode) }
            AccountHubEvent.CommitBudgetImport -> commit()
            else -> return false
        }
        return true
    }

    private fun updateImport(change: BudgetImportState.() -> BudgetImportState) =
        vm.update { copy(budget = budget.copy(import = budget.import.change())) }

    /**
     * Opens on the upload step and reads every version the production has.
     *
     * Read here, as the web's wizard does, rather than taken from the page: a
     * suggested version is only worth anything if it saw a budget someone else
     * imported since the page loaded. A failed read never blocks the import —
     * the page's list stands in, and the server stays the authority on clashes.
     */
    private fun open() {
        if (!vm.mayEdit()) return
        // Read out here: inside the update, `acceptsDrops` would be the state's own field, always false.
        val drops = acceptsDrops
        updateImport { BudgetImportState(open = true, acceptsDrops = drops) }
        vm.runResult(vm.repo::budgetVersions, { rows ->
            updateImport { if (open) copy(existing = rows) else this }
        }, {
            updateImport { if (open) copy(existing = vm.setupState.budget.versions) else this }
        })
    }

    private fun pick() {
        val source = files ?: return
        if (!vm.mayEdit()) return
        vm.launchWork {
            val file = source.pick(SetupUpload.BudgetImport, multiple = false) { refusal ->
                updateImport { copy(parseError = refusal) }
            }.firstOrNull() ?: return@launchWork
            stage(file)
        }
    }

    /** A file dragged onto the drop zone, held exactly as a picked one. */
    private fun drop(name: String, bytes: ByteArray) {
        val source = files ?: return
        if (!vm.mayEdit() || vm.setupState.budget.import.uploading) return
        source.adopt(name, bytes, SetupUpload.BudgetImport) { refusal ->
            updateImport { copy(parseError = refusal) }
        }?.let(::stage)
    }

    private fun stage(file: PickedAgreementFile) = updateImport {
        if (open) copy(picked = file, parseError = null) else this
    }

    /**
     * Uploads the staged file, then the dry run. Either failure stays on the
     * upload step with the reason and a Retry — the web's inline banner — and
     * the staged file is kept, so a retry needs no second pick.
     */
    private fun parse() {
        val source = files ?: return
        val import = vm.setupState.budget.import
        val file = import.picked ?: return
        if (!import.canParse || !vm.mayEdit()) return
        updateImport { copy(uploading = true, parseError = null) }
        vm.launchWork {
            when (val stored = source.upload(file, caption = "", purpose = SetupUpload.BudgetImport)) {
                is ZillitResult.Failure -> updateImport {
                    copy(uploading = false, parseError = stored.error.localised())
                }
                is ZillitResult.Success -> dryRun(stored.data.copy(name = file.name), file.name)
            }
        }
    }

    private fun dryRun(document: AgreementDocument, fileName: String) {
        vm.runResult({ vm.repo.dryRunBudgetImport(document) }, { (parsed, upload) ->
            updateImport {
                if (!open) return@updateImport this
                val known = existing ?: vm.setupState.budget.versions
                copy(
                    uploading = false,
                    step = ImportStep.Preview,
                    parsed = parsed,
                    upload = upload,
                    commitError = null,
                    // Suggested, not imposed: the accountant only has to type
                    // if they want something else.
                    meta = BudgetImportMeta(
                        version = BudgetImports.suggestNextVersion(known),
                        label = BudgetImports.labelFrom(fileName),
                        description = "Imported from $fileName",
                    ),
                )
            }
        }, { error ->
            updateImport { copy(uploading = false, parseError = error.localised()) }
        })
    }

    /**
     * Writes the reviewed parse.
     *
     * On success the page's list is refreshed at once and the new version
     * selected, while the wizard shows what was imported until Done — the
     * web's `onImported`. A refusal (usually a taken version or a code the
     * chart rejects) stays on the preview, everything intact, with the reason
     * above the buttons.
     */
    private fun commit() {
        val import = vm.setupState.budget.import
        val parsed = import.parsed ?: return
        val upload = import.upload ?: return
        if (!import.canCommit || !vm.mayEdit()) return

        updateImport { copy(committing = true, commitError = null) }
        vm.runResult({ vm.repo.commitBudgetImport(upload, parsed, import.meta, import.mode) }, { created ->
            updateImport { copy(committing = false, step = ImportStep.Done, created = created) }
            vm.reloadBudget(selecting = created?.id?.takeIf { it.isNotBlank() })
            // The commit wrote codes into the chart every other screen reads.
            vm.chart.load()
        }, { error ->
            updateImport { copy(committing = false, commitError = error.localised()) }
        })
    }
}
