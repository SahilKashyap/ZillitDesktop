package com.zillit.desktop.feature.accounthub.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.feature.accounthub.domain.AgreementFiles
import com.zillit.desktop.feature.accounthub.domain.BudgetImportMeta
import com.zillit.desktop.feature.accounthub.domain.BudgetImports
import com.zillit.desktop.feature.accounthub.domain.SetupUpload

/**
 * Importing a budget file.
 *
 * Two calls with a review between them, and the review is the point: the parse
 * is a guess at somebody else's spreadsheet, and the commit writes codes into
 * the chart of accounts that every other tool codes against. Nothing is
 * written until the accountant has looked at what the parse made.
 */
internal class BudgetImportActions(
    private val vm: AccountHubViewModel,
    private val files: AgreementFiles?,
) {

    /** Whether the host wired storage; without it the import cannot start. */
    val isAvailable: Boolean get() = files != null

    fun onEvent(event: AccountHubEvent): Boolean {
        when (event) {
            AccountHubEvent.OpenBudgetImport ->
                vm.update { copy(budget = budget.copy(import = BudgetImportState(open = true))) }

            AccountHubEvent.CloseBudgetImport -> close()

            AccountHubEvent.PickBudgetFile -> pick()

            is AccountHubEvent.EditBudgetImportMeta ->
                vm.update { copy(budget = budget.copy(import = budget.import.copy(meta = event.meta))) }

            is AccountHubEvent.SetCoaImportMode ->
                vm.update { copy(budget = budget.copy(import = budget.import.copy(mode = event.mode))) }

            AccountHubEvent.CommitBudgetImport -> commit()

            else -> return false
        }
        return true
    }

    /**
     * Closes the wizard, and reloads the versions when one was created.
     *
     * The reload is here rather than at the commit so the accountant sees the
     * result they were shown, then finds the list already carrying it.
     */
    private fun close() {
        val created = vm.setupState.budget.import.created
        vm.update { copy(budget = budget.copy(import = BudgetImportState())) }
        if (created != null) {
            vm.reloadBudget(selecting = created.id.takeIf { it.isNotBlank() })
            // The commit wrote codes into the chart every other screen reads.
            vm.chart.load()
        }
    }

    private fun pick() {
        val source = files ?: return
        if (!vm.mayEdit()) return
        vm.launchWork {
            val file = source.pick(SetupUpload.BudgetImport, multiple = false) { refusal ->
                vm.sendSideEffect(AccountHubEffect.Failed(refusal))
            }.firstOrNull() ?: return@launchWork

            vm.update { copy(budget = budget.copy(import = budget.import.copy(uploading = true))) }
            when (val stored = source.upload(file, caption = "", purpose = SetupUpload.BudgetImport)) {
                is ZillitResult.Failure -> {
                    vm.update { copy(budget = budget.copy(import = budget.import.copy(uploading = false))) }
                    vm.report(stored.error)
                }
                is ZillitResult.Success -> parse(stored.data.copy(name = file.name))
            }
        }
    }

    /** The dry run: what the server made of the file, without writing anything. */
    private fun parse(document: com.zillit.desktop.feature.accounthub.domain.AgreementDocument) {
        vm.runResult({ vm.repo.dryRunBudgetImport(document) }, { (parsed, upload) ->
            vm.update {
                copy(
                    budget = budget.copy(
                        import = budget.import.copy(
                            uploading = false,
                            step = ImportStep.Preview,
                            parsed = parsed,
                            upload = upload,
                            // Suggested, not imposed: the accountant only has
                            // to type if they want something else.
                            meta = BudgetImportMeta(
                                version = BudgetImports.suggestNextVersion(budget.versions),
                                label = BudgetImports.labelFrom(upload.fileName),
                                description = "Imported from ${upload.fileName}",
                            ),
                        ),
                    ),
                )
            }
        }, { error ->
            vm.update { copy(budget = budget.copy(import = budget.import.copy(uploading = false))) }
            vm.report(error)
        })
    }

    private fun commit() {
        val import = vm.setupState.budget.import
        val parsed = import.parsed ?: return
        val upload = import.upload ?: return
        if (!import.canCommit || !vm.mayEdit()) return

        vm.update { copy(budget = budget.copy(import = budget.import.copy(committing = true, commitError = null))) }
        vm.runResult({ vm.repo.commitBudgetImport(upload, parsed, import.meta, import.mode) }, { created ->
            vm.update {
                copy(
                    budget = budget.copy(
                        import = budget.import.copy(
                            committing = false,
                            step = ImportStep.Done,
                            created = created,
                        ),
                    ),
                )
            }
        }, { error ->
            // The wizard stays on Preview with everything intact: the usual
            // refusal is a duplicate version or a code the chart rejects, and
            // both are fixed here rather than by starting again.
            // Named on the Preview step, where the Import button lives — the
            // web surfaces it there for the same reason.
            vm.update {
                copy(budget = budget.copy(import = budget.import.copy(
                    committing = false,
                    commitError = error.localised(),
                )))
            }
        })
    }
}
