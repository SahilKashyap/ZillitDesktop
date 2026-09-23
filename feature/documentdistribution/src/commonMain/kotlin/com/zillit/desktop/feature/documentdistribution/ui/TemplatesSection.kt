package com.zillit.desktop.feature.documentdistribution.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.documentdistribution.domain.EmailTemplate
import com.zillit.desktop.feature.documentdistribution.domain.HtmlText

/** Reusable subject + body pairs, and the editor the composer also opens. */
internal class TemplatesSection(private val vm: VmScope) {

    fun load() {
        vm.update { copy(loading = true, error = null) }
        vm.run {
            when (val result = vm.repository.templates()) {
                is ZillitResult.Success -> vm.update { copy(loading = false, templates = result.data) }
                is ZillitResult.Failure -> vm.update { copy(loading = false, error = result.error.userMessage) }
            }
        }
    }

    fun openNew() {
        if (vm.refusesWrite()) return
        vm.update { copy(templateEditor = TemplateEditorState()) }
    }

    fun openEdit(templateId: String) {
        if (vm.refusesWrite()) return
        val template = vm.state.templates.firstOrNull { it.id == templateId } ?: return
        vm.update {
            copy(
                templateEditor = TemplateEditorState(
                    templateId = template.id,
                    name = template.name,
                    description = template.description,
                    subject = template.subject,
                    body = HtmlText.toPlainText(template.bodyHtml),
                ),
            )
        }
    }

    fun edit(name: String?, description: String?, subject: String?, body: String?) = vm.update {
        copy(
            templateEditor = templateEditor?.copy(
                name = name ?: templateEditor.name,
                description = description ?: templateEditor.description,
                subject = subject ?: templateEditor.subject,
                body = body ?: templateEditor.body,
            ),
        )
    }

    fun close() = vm.update { copy(templateEditor = null) }

    fun save() {
        val editor = vm.state.templateEditor ?: return
        if (editor.name.isBlank()) return vm.fail(str(S.ah_template_name_required))
        if (vm.refusesWrite()) return
        val template = EmailTemplate(
            id = editor.templateId,
            name = editor.name.trim(),
            subject = editor.subject,
            bodyHtml = HtmlText.plainToHtml(editor.body),
            description = editor.description.trim(),
        )
        vm.update { copy(templateEditor = editor.copy(saving = true)) }
        vm.run {
            when (val result = vm.repository.saveTemplate(template)) {
                is ZillitResult.Success -> {
                    vm.update { copy(templateEditor = null) }
                    vm.notice(str(if (editor.isNew) S.docusign_template_saved else S.cs_update_template_saved))
                    // The composer's picker lists these; a fresh copy either way.
                    (vm.repository.templates() as? ZillitResult.Success)?.let { t ->
                        vm.update { copy(templates = t.data) }
                    }
                }
                is ZillitResult.Failure -> {
                    vm.update { copy(templateEditor = templateEditor?.copy(saving = false)) }
                    vm.report(result.error)
                }
            }
        }
    }

    fun confirmDelete(templateId: String) {
        if (vm.refusesWrite()) return
        val template = vm.state.templates.firstOrNull { it.id == templateId } ?: return
        vm.update {
            copy(
                prompt = DocDistPrompt(
                    title = str(S.desktop_docdist_delete_template_title, template.name),
                    message = str(S.desktop_docdist_delete_template_message),
                    confirmLabel = str(S.delete),
                    event = DocDistEvent.DeleteTemplate(templateId),
                ),
            )
        }
    }

    fun delete(templateId: String) {
        if (vm.refusesWrite()) return
        vm.run {
            vm.onSuccess(vm.repository.deleteTemplate(templateId)) {
                vm.update { copy(templates = templates.filterNot { it.id == templateId }) }
                vm.notice(str(S.dd_template_deleted))
            }
        }
    }
}
