package com.zillit.desktop.feature.esignature.ui.flows

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.esignature.domain.EnvelopeTemplate
import com.zillit.desktop.feature.esignature.domain.TemplateDraft
import com.zillit.desktop.feature.esignature.ui.EsignStore
import com.zillit.desktop.feature.esignature.ui.orFail

/** The template library — the web's `TemplateLibrary`: list, duplicate, delete. Use and Edit open the editor. */
internal class TemplatesFlow(private val store: EsignStore) {

    fun load() {
        store.update { copy(templates = templates.copy(loading = true)) }
        store.runTask {
            when (val items = store.repository.templates()) {
                is ZillitResult.Failure -> {
                    store.update { copy(templates = templates.copy(loading = false, loaded = true)) }
                    store.failed(items.error.userMessage)
                }
                is ZillitResult.Success -> store.update {
                    val sorted = items.data.sortedByDescending { it.updated ?: it.created ?: 0 }
                    copy(templates = templates.copy(items = sorted, loading = false, loaded = true))
                }
            }
        }
    }

    /** A copy named "(copy)", the same design, saved straight away. */
    fun duplicate(template: EnvelopeTemplate) {
        if (store.refusesPost()) return
        store.update { copy(templates = templates.copy(busyId = template.id)) }
        store.runTask {
            val created = store.orFail {
                store.repository.createTemplate(
                    TemplateDraft(
                        name = "${template.name} (copy)",
                        description = template.description,
                        category = template.category,
                        documents = template.documents,
                        slots = template.recipients,
                        fields = template.fields,
                        settings = template.settings,
                    ),
                )
            }
            store.update { copy(templates = templates.copy(busyId = null)) }
            if (created != null) {
                store.notice("Duplicated “${template.name}”")
                load()
            }
        }
    }

    fun confirmDelete() {
        val id = store.current.templates.confirmDeleteId ?: return
        val name = store.current.templates.items.firstOrNull { it.id == id }?.name.orEmpty()
        store.update { copy(templates = templates.copy(confirmDeleteId = null, busyId = id)) }
        if (store.refusesPost()) {
            store.update { copy(templates = templates.copy(busyId = null)) }
            return
        }
        store.runTask {
            val ok = store.orFail { store.repository.deleteTemplate(id) }
            store.update { copy(templates = templates.copy(busyId = null, detail = null)) }
            if (ok != null) {
                store.notice("Deleted “$name”")
                load()
            }
        }
    }
}
