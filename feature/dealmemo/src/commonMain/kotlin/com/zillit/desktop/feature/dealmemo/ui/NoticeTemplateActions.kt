package com.zillit.desktop.feature.dealmemo.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.dealmemo.domain.NoticeRules
import kotlinx.coroutines.delay

/** The notice template editor — the web's `DMNoticeTemplatePage`. */
internal class NoticeTemplateActions(private val vm: DealMemoViewModel) {

    fun onEvent(event: NoticeTemplateEvent) {
        when (event) {
            is NoticeTemplateEvent.Edit -> edit { copy(text = event.text, justSaved = false) }
            is NoticeTemplateEvent.SetMode -> edit { copy(mode = event.mode) }
            NoticeTemplateEvent.AskReset -> edit { copy(confirmReset = true) }
            NoticeTemplateEvent.CancelReset -> edit { copy(confirmReset = false) }
            // Replaces the editor's text and switches to editing; nothing is saved until Save.
            NoticeTemplateEvent.ConfirmReset -> edit {
                copy(text = NoticeRules.DEFAULT_TEMPLATE, mode = NoticeTemplateMode.Edit, confirmReset = false)
            }
            NoticeTemplateEvent.Save -> save()
            NoticeTemplateEvent.Close -> vm.navigate(DealMemoRoute.Tab(DealTab.Notices))
        }
    }

    /** The production's template, or the default wording when none is stored or it cannot be read. */
    fun enter() {
        edit { copy(loading = !loaded) }
        vm.work {
            val stored =
                vm.repository.noticeTemplate().getOrNull()?.takeIf { it.isNotBlank() } ?: NoticeRules.DEFAULT_TEMPLATE
            edit { copy(loading = false, loaded = true, text = stored, savedText = stored) }
        }
    }

    private fun save() {
        val state = vm.ui.noticeTemplate
        if (state.saving || state.loading) return
        val value = normalise(state.text)
        edit { copy(saving = true) }
        vm.work {
            when (val result = vm.repository.saveNoticeTemplate(value)) {
                is ZillitResult.Success -> {
                    vm.toastSuccess(result.data, "notice_template_saved")
                    edit { copy(saving = false, text = value, savedText = value, justSaved = true) }
                    delay(SAVED_CHECK_MILLIS)
                    edit { copy(justSaved = false) }
                }
                is ZillitResult.Failure -> {
                    vm.toastError(result.error)
                    edit { copy(saving = false) }
                }
            }
        }
    }

    /**
     * The web's serialisation: non-breaking spaces to spaces, three or more
     * newlines collapsed to a paragraph break, trimmed.
     */
    private fun normalise(text: String): String =
        text.replace(' ', ' ').replace(Regex("\n{3,}"), "\n\n").trim()

    private fun edit(reducer: NoticeTemplateState.() -> NoticeTemplateState) =
        vm.update { copy(noticeTemplate = noticeTemplate.reducer()) }

    private companion object {
        const val SAVED_CHECK_MILLIS = 1_700L
    }
}
