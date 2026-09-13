package com.zillit.desktop.feature.productionreport.ui

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.feature.productionreport.domain.ManageTab
import com.zillit.desktop.feature.productionreport.domain.SavedTemplate

/**
 * Templates: "Create Template" (the stock picker), the project's saved
 * layouts in Drafts, and the editor's Save as / Update Template —
 * `ProductionReportApp.jsx:1673-1680, 2136-2246`.
 */
internal class TemplateController(private val ctx: ReportContext) {

    fun onEvent(event: DialogEvent) {
        when (event) {
            DialogEvent.CreateTemplate -> create()
            is DialogEvent.PickTemplate -> ctx.update {
                copy(dialog = (dialog as? ReportDialog.TemplatePicker)?.copy(selected = event.index) ?: dialog)
            }
            is DialogEvent.UseTemplate -> use(event.index)
            is DialogEvent.OpenSavedTemplate -> openSaved(event.template)
            is DialogEvent.DeleteSavedTemplate -> ctx.update {
                copy(
                    dialog = ReportDialog.Confirm(
                        action = ConfirmAction.DeleteTemplate(event.template),
                        title = "Delete Template",
                        message = "\"${event.template.name}\" will be removed for everyone in this project.",
                        confirmLabel = "Delete",
                        danger = true,
                    ),
                )
            }
            else -> Unit
        }
    }

    /** More than one stock layout opens the picker; otherwise straight into the editor. */
    private fun create() {
        if (!ctx.state.isPoster) return
        val templates = ctx.state.stockTemplates
        if (templates.size > 1) {
            val firstPickable = templates.indexOfFirst { !it.isCreateYourOwn }.coerceAtLeast(0)
            ctx.update { copy(dialog = ReportDialog.TemplatePicker(templates, firstPickable)) }
        } else {
            ctx.editor.openNew(templates.firstOrNull()?.payload, fromSavedTemplate = false)
        }
    }

    private fun use(index: Int) {
        val picker = ctx.state.dialog as? ReportDialog.TemplatePicker ?: return
        val template = picker.templates.getOrNull(index) ?: return
        ctx.update { copy(dialog = null) }
        ctx.editor.openNew(template.payload, fromSavedTemplate = false)
    }

    /** A saved layout opens as saved; "Update Template" appears once the call sheet merge is done. */
    private fun openSaved(template: SavedTemplate) {
        if (!ctx.state.isPoster) return
        ctx.launchWork {
            when (val result = ctx.repository.savedTemplate(template.id)) {
                is ZillitResult.Success -> {
                    val payload = result.data.payload
                    if (payload == null) {
                        ctx.toast("Couldn't open template: Template has no content", isError = true)
                        return@launchWork
                    }
                    ctx.editor.openNew(
                        payload,
                        fromSavedTemplate = true,
                        template = TemplateRef(template.id, template.name.ifBlank { result.data.name }),
                    )
                }
                is ZillitResult.Failure -> if (result.error.isGone()) {
                    ctx.toast("That template no longer exists — list refreshed.", isError = true)
                    ctx.lists.refreshSavedTemplates()
                } else {
                    ctx.toast("Couldn't open template: ${result.error.localised()}", isError = true)
                }
            }
        }
    }

    fun delete(template: SavedTemplate) {
        ctx.launchWork {
            when (val result = ctx.repository.deleteTemplate(template.id)) {
                is ZillitResult.Success -> ctx.toast("Template deleted.")
                is ZillitResult.Failure -> if (!result.error.isGone()) {
                    ctx.toast("Delete failed: ${result.error.localised()}", isError = true)
                }
            }
            ctx.lists.refreshSavedTemplates()
        }
    }

    /**
     * "Save as Template": the document shared with the whole project; the
     * server names it. Not held behind the review-restart prompt — a template
     * save does not touch the report.
     */
    fun saveAsTemplate() {
        val editor = ctx.state.editor ?: return
        if (editor.savingTemplate || !ctx.state.isPoster) return
        ctx.update { copy(editor = editor.copy(savingTemplate = true)) }
        ctx.launchWork {
            when (val result = ctx.repository.createTemplate(editor.document)) {
                is ZillitResult.Success -> {
                    val name = result.data?.name?.ifBlank { null } ?: "Draft Template"
                    ctx.toast("Saved as \"$name\" — visible to everyone who can create production reports.")
                    leaveToDrafts()
                }
                is ZillitResult.Failure -> {
                    ctx.update { copy(editor = this.editor?.copy(savingTemplate = false)) }
                    ctx.toast("Template save failed: ${result.error.localised()}", isError = true)
                }
            }
        }
    }

    /** "Update Template": overwrites the saved layout; names are immutable. */
    fun updateTemplate() {
        val editor = ctx.state.editor ?: return
        val template = editor.template ?: return
        if (editor.savingTemplate || !ctx.state.isPoster) return
        ctx.update { copy(editor = editor.copy(savingTemplate = true)) }
        ctx.launchWork {
            when (val result = ctx.repository.updateTemplate(template.id, editor.document)) {
                is ZillitResult.Success -> {
                    ctx.toast("\"${template.name}\" updated for the whole project.")
                    leaveToDrafts()
                }
                is ZillitResult.Failure -> {
                    ctx.update {
                        copy(
                            editor = this.editor?.copy(
                                savingTemplate = false,
                                template = if (result.error.isGone()) null else template,
                            ),
                        )
                    }
                    if (result.error.isGone()) {
                        ctx.toast("That template no longer exists — list refreshed.", isError = true)
                        ctx.lists.refreshSavedTemplates()
                    } else {
                        ctx.toast("Template update failed: ${result.error.localised()}", isError = true)
                    }
                }
            }
        }
    }

    private suspend fun leaveToDrafts() {
        ctx.update { copy(editor = null, workspace = Workspace.Manage, tab = ManageTab.Drafts) }
        ctx.lists.refreshSavedTemplates()
        ctx.lists.loadDrafts()
    }

    private fun ZillitError.isGone(): Boolean =
        (this as? ZillitError.Http)?.status == NOT_FOUND || localised().contains(Regex("\\b404\\b"))

    private companion object {
        const val NOT_FOUND = 404
    }
}
