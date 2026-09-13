package com.zillit.desktop.feature.boxschedule.ui

import com.zillit.desktop.feature.boxschedule.domain.DiaryPdfOptions
import com.zillit.desktop.feature.boxschedule.domain.DiaryRevision
import com.zillit.desktop.feature.boxschedule.domain.HistoryAction
import com.zillit.desktop.feature.boxschedule.domain.HistoryEntry
import com.zillit.desktop.feature.boxschedule.domain.UserPreset
import kotlinx.datetime.LocalDate

/**
 * "Schedule Types" — `ScheduleTypeManager`. One row edits at a time; the
 * add row stays pinned under the list.
 */
data class TypesManager(
    val editingId: String? = null,
    val editTitle: String = "",
    val editColor: String = DEFAULT_TYPE_COLOR,
    val newTitle: String = "",
    val newColor: String = DEFAULT_TYPE_COLOR,
    val creating: Boolean = false,
    val savingEdit: Boolean = false,
) {
    companion object {
        const val DEFAULT_TYPE_COLOR = "#9B59B6"
    }
}

/** "History" — the activity log, its filters, and the details of one row. */
data class HistoryPanel(
    val loading: Boolean = true,
    val entries: List<HistoryEntry> = emptyList(),
    val revisions: List<DiaryRevision> = emptyList(),
    /** Saved presets, so a distribute preset id reads as its name. */
    val presets: List<UserPreset> = emptyList(),
    val action: HistoryAction? = null,
    val day: LocalDate? = null,
    val detailId: String? = null,
)

/** "Presets" — the list, or the form that creates or edits one. */
data class PresetsPanel(
    val loading: Boolean = true,
    val presets: List<UserPreset> = emptyList(),
    val search: String = "",
    val form: PresetForm? = null,
    /** "Delete preset?" — the preset's id. */
    val confirmDelete: String? = null,
    val deleting: Boolean = false,
    /** The preset whose members are showing. */
    val membersOf: String? = null,
)

data class PresetForm(
    val presetId: String? = null,
    val name: String = "",
    val userIds: List<String> = emptyList(),
    val query: String = "",
    val saving: Boolean = false,
) {
    val isEdit: Boolean get() = presetId != null
    val canSave: Boolean get() = name.isNotBlank() && userIds.isNotEmpty() && !saving

    companion object {
        const val NAME_LIMIT = 120
    }
}

/** Where a PDF goes once its options are chosen. */
enum class PdfDestination { Print, Publish }

/**
 * "PDF options" — the layout the server renders and whether the viewer's
 * own Personal Notes go into it. Each open re-asks: a remembered Without
 * would silently drop content from a later export.
 */
data class PdfSheet(
    val destination: PdfDestination,
    val options: DiaryPdfOptions = DiaryPdfOptions(),
    val busy: Boolean = false,
)

/** "Print selected days" — the in-app printout's own Personal Notes choice. */
data class PrintPrompt(val includePersonalNotes: Boolean = true, val printing: Boolean = false)

/** "Share Schedule" — a read-only link, or the schedule as text. */
data class SharePanel(
    val link: String? = null,
    val generating: Boolean = false,
    val linkCopied: Boolean = false,
    val textCopied: Boolean = false,
)
