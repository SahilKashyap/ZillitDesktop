package com.zillit.desktop.feature.crewlist.ui

import androidx.compose.ui.graphics.ImageBitmap
import com.zillit.desktop.feature.crewlist.domain.CompanyDetails
import com.zillit.desktop.feature.crewlist.domain.CompanyProblem
import com.zillit.desktop.feature.crewlist.domain.CrewListPdf
import com.zillit.desktop.feature.crewlist.domain.CrewListViewer
import com.zillit.desktop.feature.crewlist.domain.CrewMember
import com.zillit.desktop.feature.crewlist.domain.CrewPdfPage
import com.zillit.desktop.feature.crewlist.domain.CrewUnit
import com.zillit.desktop.feature.crewlist.domain.DepartmentOrder
import com.zillit.desktop.feature.crewlist.domain.DialCode
import com.zillit.desktop.feature.crewlist.domain.LayoutHistory
import com.zillit.desktop.feature.crewlist.domain.MemberOverride
import com.zillit.desktop.feature.crewlist.domain.PeopleOrder
import com.zillit.desktop.feature.crewlist.domain.PhoneProblem
import com.zillit.desktop.feature.crewlist.domain.PickedLogo

data class CrewListUiState(
    val viewer: CrewListViewer = CrewListViewer(),
    /** Whose rows the drawer may not call or email — yourself. */
    val selfUserId: String = "",
    val units: List<CrewUnit> = emptyList(),
    val isLoading: Boolean = false,
    val hasLoaded: Boolean = false,
    val query: String = "",

    /** Edit is a mode you enter and leave; the cells are text until then. */
    val editing: Boolean = false,
    /** Pending document-only edits, by user id. */
    val overrides: Map<String, MemberOverride> = emptyMap(),
    /** Rows whose phone and dial code disagree — they block Done and the PDF. */
    val problems: Map<String, PhoneProblem> = emptyMap(),
    val dialCodes: List<DialCode> = emptyList(),

    /** The letterhead, with its undo history. */
    val layout: LayoutHistory = LayoutHistory(),
    val hideInternalLines: Boolean = false,
    /** The generate chooser's Yes/No, kept between openings as the web keeps it. */
    val hideExternalLabel: Boolean = false,

    /** The Generate PDF chooser, when open. */
    val chooserOpen: Boolean = false,
    /** A generate or publish is in flight; the sheet shows its loader. */
    val working: CrewWork? = null,
    val pdf: PdfViewerState? = null,
    /** "Are you sure you want to publish the Crew List?" */
    val confirmPublish: Boolean = false,
    val distribution: DistributionPrompt? = null,

    val customise: CustomiseState? = null,
    val departments: DepartmentOrderState? = null,
    val company: CompanyEditorState? = null,
    val profile: CrewMember? = null,
    val addingExternalUser: Boolean = false,
    val error: String? = null,
) {
    val hasOverrides: Boolean get() = overrides.isNotEmpty()

    /** Pending edits or a designed letterhead — what the web guards a reload for. */
    val hasUnsavedDesign: Boolean get() = hasOverrides || layout.current.isCustomised
}

/** What the loader over the sheet is waiting for. */
enum class CrewWork { Generating, Publishing, Distributing }

/** The three things the chooser does with a fresh PDF — and the widget's plain download. */
enum class GenerateAction { View, Publish, Distribute, Download }

/** The in-app viewer; the bytes it drew stay in the view model, for Download. */
data class PdfViewerState(
    val pdf: CrewListPdf,
    val pages: List<CrewPdfPage> = emptyList(),
    val loading: Boolean = true,
    val failed: String? = null,
)

/**
 * Document Distribution's confirmation, then its success note — the web's
 * `useDistributeToDocDist` popups, naming the file (ZL-19833).
 */
data class DistributionPrompt(
    val pdf: CrewListPdf,
    val stage: Stage = Stage.Confirm,
    /** Cancelling from the chooser's path reopens the chooser; from the viewer it does not. */
    val fromChooser: Boolean = false,
) {
    enum class Stage { Confirm, Sending, Done }
}

/** Design (the arranger) or Preview (the backend's real render). */
enum class CanvasMode { Design, Preview }

/** A document for the canvas; a new [key] is a reload, anything else leaves the page alone. */
data class CanvasDocument(val key: Int, val html: String)

data class CustomiseState(
    val mode: CanvasMode = CanvasMode.Design,
    /** The backend's stacked render, before the arranger is layered on. */
    val designSource: String = "",
    val designLoading: Boolean = true,
    val design: CanvasDocument? = null,
    val preview: CanvasDocument? = null,
    val previewLoading: Boolean = false,
    /** Changed since the Preview tab last rendered — the tab's orange dot. */
    val previewDirty: Boolean = true,
    /** The slider's label while dragging; committed on release. */
    val logoSizeDraft: Int,
    val failure: String? = null,
)

data class DepartmentOrderState(
    val order: DepartmentOrder = DepartmentOrder(),
    val loading: Boolean = true,
    val saving: Boolean = false,
    val query: String = "",
    /** Cancel with changes asks "Do you want to save changes?" first. */
    val confirmDiscard: Boolean = false,
    val failure: String? = null,
    /** The people inside one department, when a department was clicked. */
    val people: PeopleOrderState? = null,
)

data class PeopleOrderState(
    val order: PeopleOrder,
    val loading: Boolean = true,
    val saving: Boolean = false,
    val query: String = "",
    val failure: String? = null,
)

data class CompanyEditorState(
    val details: CompanyDetails = CompanyDetails(),
    val loading: Boolean = true,
    val saving: Boolean = false,
    /** The stored logo, or the picked one once chosen. */
    val logoImage: ImageBitmap? = null,
    val pickedLogo: PickedLogo? = null,
    val removeLogo: Boolean = false,
    val detailsExpanded: Boolean = true,
    val confirmRemoveLogo: Boolean = false,
    val problems: List<CompanyProblem> = emptyList(),
    val failure: String? = null,
) {
    val hasLogo: Boolean get() = logoImage != null || (details.logo != null && !removeLogo)
}
