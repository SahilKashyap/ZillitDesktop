package com.zillit.desktop.feature.sos.ui

import com.zillit.desktop.feature.sos.domain.ExternalContactDraft
import com.zillit.desktop.feature.sos.domain.IsdCode
import com.zillit.desktop.feature.sos.domain.SosAlert
import com.zillit.desktop.feature.sos.domain.SosContact
import com.zillit.desktop.feature.sos.domain.SosContactKind
import com.zillit.desktop.feature.sos.domain.SosCrewMember
import com.zillit.desktop.feature.sos.domain.SosRelation
import com.zillit.desktop.feature.sos.domain.SosViewer

/** The receiver list's two halves — the web's two tabs (`SOS.jsx:528`, `:601`). */
enum class SosContactTab(val id: String, val label: String) {
    Member("member", "Member"),
    Outsider("outsider", "Outsider"),
    ;

    companion object {
        fun fromId(id: String): SosContactTab = entries.firstOrNull { it.id == id } ?: Member
    }
}

/**
 * Something destructive or irreversible waiting on the reader's "Yes".
 *
 * Sending is in here with the deletes on purpose: the web guards the button
 * with a tooltip that says "tap only if you are in real danger"
 * (`SOSMain.jsx:596-604`), and a desktop button is far easier to hit by
 * accident than a phone's.
 */
sealed interface SosConfirm {
    data object SendAlert : SosConfirm
    data class DeleteAlert(val alertId: String) : SosConfirm
    data object DeleteAllAlerts : SosConfirm
    data class DeleteContact(val contactId: String) : SosConfirm
}

/**
 * The receiver list and the form above it.
 *
 * [editingId] blank means the form adds; set, it edits that row — the web's
 * `editIconClicked` plus `selectedRecord` (`SOS.jsx:381-409`).
 */
data class SosContactsState(
    val loading: Boolean = false,
    val busy: Boolean = false,
    val rows: List<SosContact> = emptyList(),
    val relations: List<SosRelation> = emptyList(),
    val isdCodes: List<IsdCode> = emptyList(),
    /** The production's crew, handed in by the host — this module issues no user call. */
    val crew: List<SosCrewMember> = emptyList(),
    val tab: SosContactTab = SosContactTab.Member,
    val crewSearch: String = "",
    val codeSearch: String = "",
    val draft: ExternalContactDraft = EMPTY_DRAFT,
    val editingId: String = "",
    /** What is wrong with the outsider form right now — [ExternalContactDraft.problem]. */
    val formError: String? = null,
) {
    val members: List<SosContact> get() = rows.filter { it.kind == SosContactKind.Internal }

    val outsiders: List<SosContact> get() = rows.filter { it.kind == SosContactKind.External }

    /**
     * The crew who could still be added: not already a receiver, and not the
     * viewer themself — the web filters both (`SOS.jsx:59-76`, `:363-369`).
     * [crewSearch] then narrows by name, which the web does with a plain
     * `Select` and the desktop does with a search field.
     */
    fun addableCrew(viewerUserId: String): List<SosCrewMember> {
        val taken = members.filterNot { it.id == editingId }.map { it.userId }.toSet()
        val needle = crewSearch.trim()
        return crew
            .filterNot { it.userId in taken || it.userId == viewerUserId }
            .filter { needle.isEmpty() || it.fullName.contains(needle, ignoreCase = true) }
    }

    /** Codes matching the search on either the country's name or its digits (`SOS.jsx:686-693`). */
    val visibleCodes: List<IsdCode>
        get() {
            val needle = codeSearch.trim()
            if (needle.isEmpty()) return isdCodes
            return isdCodes.filter {
                it.name.contains(needle, ignoreCase = true) || it.dialCode.contains(needle)
            }
        }

    val isEditing: Boolean get() = editingId.isNotEmpty()

    private companion object {
        val EMPTY_DRAFT = ExternalContactDraft(
            contactName = "",
            relation = "",
            countryCode = "",
            phoneNumber = "",
        )
    }
}

data class SosUiState(
    val viewer: SosViewer = SosViewer(),
    /** The first page, or a refresh, in flight. */
    val loading: Boolean = false,
    /** An older page in flight — the list stays up. */
    val loadingMore: Boolean = false,
    /** An alarm or a delete in flight. */
    val busy: Boolean = false,
    /** True once a first page has answered, so an empty list reads as empty rather than pending. */
    val loaded: Boolean = false,
    val alerts: List<SosAlert> = emptyList(),
    val hasMore: Boolean = false,
    val error: String? = null,
    val confirm: SosConfirm? = null,
    val contacts: SosContactsState = SosContactsState(),
) {
    /**
     * Whether the outsider form is offered.
     *
     * The web refuses the tab outright until the profile carries a phone number
     * and sends the user to their preferences instead (`SOS.jsx:129-135`), the
     * reason being that an outsider alert is relayed from the sender's number.
     */
    val canAddOutsider: Boolean get() = viewer.phone.isNotBlank()

    /** Personal productions have no designations to show (`SOS.jsx:560`). */
    val showsDesignation: Boolean get() = !viewer.isPersonalProject
}

sealed interface SosEvent {
    data object Refresh : SosEvent
    data object LoadOlder : SosEvent

    data object AskSendAlert : SosEvent
    data class AskDeleteAlert(val alertId: String) : SosEvent
    data object AskDeleteAllAlerts : SosEvent
    data class AskDeleteContact(val contactId: String) : SosEvent
    data object ConfirmAction : SosEvent
    data object CancelConfirm : SosEvent

    data class OpenMap(val alertId: String) : SosEvent

    data class SelectContactTab(val tab: SosContactTab) : SosEvent
    data class CrewSearchChanged(val text: String) : SosEvent
    data class CodeSearchChanged(val text: String) : SosEvent
    /** Adds the crew member, or repoints the row being edited at them. */
    data class SubmitMember(val userId: String) : SosEvent
    data class ContactNameChanged(val text: String) : SosEvent
    data class RelationChanged(val relation: String) : SosEvent
    data class CountryCodeChanged(val dialCode: String) : SosEvent
    data class PhoneChanged(val text: String) : SosEvent
    data object SubmitOutsider : SosEvent
    data class EditContact(val contactId: String) : SosEvent
    data object CancelEdit : SosEvent

    data object DismissError : SosEvent
}

sealed interface SosEffect {
    data class Notice(val message: String) : SosEffect

    /** The alert's map link, for the host to open in the system browser. */
    data class OpenLink(val url: String) : SosEffect
}
