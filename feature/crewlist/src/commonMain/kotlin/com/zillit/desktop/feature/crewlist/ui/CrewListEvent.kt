package com.zillit.desktop.feature.crewlist.ui

import com.zillit.desktop.feature.crewlist.domain.CompanyDetails
import com.zillit.desktop.feature.crewlist.domain.CrewMember
import com.zillit.desktop.feature.crewlist.domain.LogoAlign
import com.zillit.desktop.feature.crewlist.domain.MemberOverride
import com.zillit.desktop.feature.crewlist.domain.OrderedDepartment

/** Everything the crew list screen can ask for, grouped by the surface that asks. */
sealed interface CrewListEvent {

    /** The sheet itself: the roster, the search, edit mode, the drawer. */
    sealed interface Sheet : CrewListEvent {
        data object Refresh : Sheet
        data class Search(val query: String) : Sheet
        data object StartEditing : Sheet
        data object DoneEditing : Sheet
        data class EditMember(val member: CrewMember, val edit: MemberOverride) : Sheet
        data class OpenProfile(val member: CrewMember) : Sheet
        data object CloseProfile : Sheet
        data object AddExternalUser : Sheet
        /** The shared form closed; [notice] is its success line when a contact was added. */
        data class ExternalUserFinished(val notice: String?) : Sheet
        data object DismissError : Sheet
    }

    /** The PDF: the chooser, the viewer, publishing it to Info and to the library. */
    sealed interface Document : CrewListEvent {
        data object OpenChooser : Document
        data object CloseChooser : Document
        data class HideExternalLabel(val hide: Boolean) : Document
        data class Run(val action: GenerateAction) : Document
        data object CloseViewer : Document
        data object DownloadViewed : Document
        data object AskPublishViewed : Document
        data object ConfirmPublish : Document
        data object CancelPublish : Document
        data object DistributeViewed : Document
        data object ConfirmDistribution : Document
        data object CancelDistribution : Document
        data object DismissDistribution : Document
    }

    /** Customise & Preview: the letterhead and the canvas. */
    sealed interface Design : CrewListEvent {
        data object Open : Design
        data object Close : Design
        data class Switch(val mode: CanvasMode) : Design
        data object Undo : Design
        data object Redo : Design
        data object Reset : Design
        data class AlignLogo(val align: LogoAlign) : Design
        data class DragLogoSize(val size: Int) : Design
        data class CommitLogoSize(val size: Int) : Design
        data class ShowInternalLines(val show: Boolean) : Design
        /** One line of JSON from the Design canvas. */
        data class CanvasMessage(val text: String) : Design
        data object Retry : Design
    }

    /** The two admin editors reachable from the sheet. */
    sealed interface Admin : CrewListEvent {
        data object OpenDepartments : Admin
        data object CloseDepartments : Admin
        data class MoveDepartment(val from: Int, val to: Int) : Admin
        data class PositionDepartment(val index: Int, val position: Int) : Admin
        data class SearchDepartments(val query: String) : Admin
        data object ResetDepartments : Admin
        data object SaveDepartments : Admin
        /** The unsaved-changes question's answer: save, or throw the order away and close. */
        data class ResolveDiscard(val save: Boolean) : Admin

        data class OpenPeople(val department: OrderedDepartment) : Admin
        data object ClosePeople : Admin
        data class MovePerson(val from: Int, val to: Int) : Admin
        data class PositionPerson(val index: Int, val position: Int) : Admin
        data class SearchPeople(val query: String) : Admin
        data object SavePeople : Admin

        data object OpenCompany : Admin
        data object CloseCompany : Admin
        data class EditCompany(val details: CompanyDetails) : Admin
        data object ToggleCompanyDetails : Admin
        data object PickLogo : Admin
        data object AskRemoveLogo : Admin
        data class ResolveRemoveLogo(val remove: Boolean) : Admin
        data object SaveCompany : Admin
    }
}

sealed interface CrewListEffect {
    data class Toast(val text: String, val tone: Tone = Tone.Info) : CrewListEffect

    enum class Tone { Info, Success, Error }
}
