package com.zillit.desktop.feature.auth.ui

import com.zillit.desktop.core.units.ProductionUnit
import com.zillit.desktop.feature.auth.domain.Department
import com.zillit.desktop.feature.auth.domain.Designation
import com.zillit.desktop.feature.auth.domain.JoinDraft
import com.zillit.desktop.feature.auth.domain.JoinFieldError
import com.zillit.desktop.feature.auth.domain.JoinPhoto
import com.zillit.desktop.feature.auth.domain.JoinStatus
import com.zillit.desktop.feature.auth.domain.Project

/**
 * Asking to join a production.
 *
 * Two steps, because they are two different questions: *which* production, then
 * *who are you on it*. Merging them would put a form in front of someone who
 * has not yet established the code is even valid.
 */
data class JoinFlowState(
    val codeText: String = "",
    /** Set once the code resolves; the details step needs the production. */
    val project: Project? = null,
    val draft: JoinDraft = JoinDraft(),
    val departments: List<Department> = emptyList(),
    val units: List<ProductionUnit> = emptyList(),
    val errors: Set<JoinFieldError> = emptySet(),
    val isBusy: Boolean = false,
    val error: String? = null,
    /**
     * The stored picture.
     *
     * Held here rather than on [draft] because the draft is replaced wholesale
     * every time a field changes — a picture living on it would be wiped by
     * the next keystroke that rebuilt it. It is merged into the draft once, at
     * submit, where the request is actually assembled.
     */
    val photo: JoinPhoto? = null,
    /** The picture is on its way to storage. */
    val isStoringPhoto: Boolean = false,
    /** Why the picture did not save. Never blocks the request. */
    val photoError: String? = null,
    /** False when the host configured no storage; the control is then absent. */
    val canChoosePhoto: Boolean = false,
    /** Set once the request is in; the dialog becomes a confirmation. */
    val outcome: JoinStatus? = null,
) {
    /** Which step is on screen. */
    val step: JoinStep
        get() = when {
            outcome != null -> JoinStep.Submitted
            project != null -> JoinStep.Details
            else -> JoinStep.Code
        }

    /**
     * Roles for the chosen department only.
     *
     * Every designation in one flat list would offer a gaffer to someone in
     * accounts — the nesting the server sends is the point of asking for it.
     */
    val designations: List<Designation>
        get() = departments.firstOrNull { it.id == draft.departmentId }?.designations.orEmpty()

    /** A personal production has no departments, roles or units to ask about. */
    val isPersonal: Boolean get() = project?.isPersonal == true

    val canFindProject: Boolean get() = codeText.isNotBlank() && !isBusy

    operator fun contains(error: JoinFieldError): Boolean = error in errors
}

enum class JoinStep { Code, Details, Submitted }

/**
 * What the confirmation says.
 *
 * Approved productions can be opened straight away; pending ones cannot, and
 * saying so plainly is the whole point — a crew member who does not know their
 * request is waiting will ask again, and again.
 */
val JoinStatus.joinMessage: String
    get() = when (this) {
        JoinStatus.Approved -> "You are in. The production is ready to open."
        JoinStatus.Pending -> "Request sent. A coordinator has to approve it before " +
            "the production opens — you will see it in your list marked as waiting."
        JoinStatus.Rejected -> "That request was declined. Ask the production for the code again."
        JoinStatus.NotJoined -> "The request did not go through. Try again."
    }
