package com.zillit.desktop.feature.settings.approvals

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.core.units.ProductionUnit

/**
 * The two queues an admin works through.
 *
 * Separate endpoints, separate payloads, and — on every other client — separate
 * screens reached from separate rows. They are one type here because everything
 * around them is identical: a list of people, a name, what they are asking for,
 * and two buttons.
 */
enum class ApprovalQueue {
    /** People who used the production code and are waiting to be let in. */
    NewCrew,

    /** Crew already in who changed their name, department or role. */
    ProfileChanges,
}

/**
 * One person waiting on an admin.
 *
 * Carries the ids as well as the names because approving means sending them
 * back: the server takes the decision *and* the values it is being made about,
 * so an approval that dropped `designation_id` would let someone in with no
 * role. Both phone clients echo the request back the same way.
 */
@Suppress("LongParameterList") // A wire row. Grouping its fields hides the shape.
data class PendingApproval(
    /**
     * What the decision is sent against.
     *
     * The join queue is keyed by the person (`user_id` plus their device); a
     * profile change is keyed by the request itself (`_id`), because one person
     * can have several outstanding. Held as one field so a list can key rows
     * without knowing which queue it is showing.
     */
    val id: String,
    val userId: String,
    val fullName: String,
    val firstName: String? = null,
    val lastName: String? = null,
    val email: String? = null,
    val deviceId: String? = null,
    val projectId: String? = null,
    val departmentId: String? = null,
    val departmentName: String? = null,
    val designationId: String? = null,
    val designationName: String? = null,
    val unitId: String? = null,
    val unitName: String? = null,
    val keepNamePrivate: Boolean = false,
    val requestedAtMillis: Long? = null,
) {
    /**
     * Never blank.
     *
     * A request with no name still has to be approvable — it is a real person
     * waiting at the door, and a row that renders as an empty line cannot be
     * acted on with any confidence.
     */
    val displayName: String
        get() = fullName.ifBlank {
            listOfNotNull(firstName, lastName).filter { it.isNotBlank() }.joinToString(" ")
                .ifBlank { email.orEmpty() }
                .ifBlank { str(S.desktop_someone_with_no_name) }
        }

    /**
     * `Transportation · Driver · Main Unit` — what this person asked to be.
     *
     * Skips whatever the request did not carry.
     *
     * All three arrive as translation keys (`transportation_department_label`),
     * so they are resolved here rather than at each of the three places that
     * show this line. [matches] searches the resolved form too: someone reading
     * "Driver" on screen and typing it should find the row.
     */
    val roleLine: String
        get() = listOfNotNull(
            departmentName?.takeIf { it.isNotBlank() },
            designationName?.takeIf { it.isNotBlank() },
            unitName?.takeIf { it.isNotBlank() },
        ).joinToString(" · ") { it.localised() }

    fun matches(query: String): Boolean {
        val needle = query.trim()
        return needle.isEmpty() ||
            displayName.contains(needle, ignoreCase = true) ||
            roleLine.contains(needle, ignoreCase = true) ||
            email.orEmpty().contains(needle, ignoreCase = true)
    }

    /**
     * What this request would change, against what the crew list says today.
     *
     * The server sends only the *requested* values — no before-and-after — so
     * an admin looking at "Approve profile changes" on the web sees a name and
     * a department with no way to tell which of them moved. The crew list is
     * already loaded here, so the comparison costs nothing and turns the page
     * from a list of people into a list of changes.
     *
     * Empty when the person is not in the crew list. Better to show the
     * requested values plainly than to invent a "from".
     */
    fun changesAgainst(known: KnownCrewMember?): List<ProfileChange> {
        if (known == null) return emptyList()
        return listOfNotNull(
            change(str(S.name), known.fullName, displayName),
            // Both sides are translation keys; a name is the person's own text.
            change(str(S.department), known.department, departmentName, isKey = true),
            change(str(S.recce_field_role), known.designation, designationName, isKey = true),
        )
    }

    /**
     * One field, before and after — or null when nothing moved.
     *
     * [isKey] translates the two values **after** they are compared. Comparing
     * the translated forms would call a real change no change whenever two keys
     * happen to read alike, and "no change" is the answer that quietly drops a
     * row from the admin's list.
     */
    private fun change(label: String, from: String?, to: String?, isKey: Boolean = false): ProfileChange? {
        val before = from?.trim().orEmpty()
        val after = to?.trim().orEmpty()
        // A field the request did not carry is not a field being cleared.
        if (after.isEmpty() || before.equals(after, ignoreCase = true)) return null
        val shown: (String) -> String = if (isKey) { value -> value.localised() } else { value -> value }
        return ProfileChange(
            label = label,
            from = before.takeIf { it.isNotBlank() }?.let(shown) ?: str(S.dm_gpr_not_set),
            to = shown(after),
        )
    }
}

/** One field this request would move. */
data class ProfileChange(val label: String, val from: String, val to: String)

/** What the crew list already holds for someone, for [PendingApproval.changesAgainst]. */
data class KnownCrewMember(
    val fullName: String,
    val department: String? = null,
    val designation: String? = null,
)

/** A job title inside a department. */
data class CrewRole(val id: String, val name: String)

/**
 * A department on this production, with its roles nested.
 *
 * Nested rather than fetched per department, because the server offers
 * `departments?designations=true` for exactly this — a round trip on every
 * dropdown change would make the form feel broken on a unit's hotel wifi.
 */
data class CrewDepartment(
    val id: String,
    val name: String,
    val roles: List<CrewRole> = emptyList(),
)

/** Everything the review form can choose between. */
data class CrewPresets(
    val departments: List<CrewDepartment> = emptyList(),
    val units: List<ProductionUnit> = emptyList(),
)

/**
 * Loads the choices the review form offers.
 *
 * Supplied from the composition root rather than built here: departments belong
 * to the join flow's repository and units to their own service, and a settings
 * module reaching into another feature's data layer to get them is the coupling
 * the module layout exists to prevent.
 *
 * A partial answer is still an answer — see [ApprovalsViewModel]. Failing only
 * when nothing at all could be read keeps a units outage from making the
 * department picker unusable.
 */
fun interface ApprovalPresets {
    suspend fun load(): ZillitResult<CrewPresets>
}

/**
 * Reading and deciding the two approval queues.
 *
 * An interface so the view model can be tested against a fake — these are the
 * two calls in the app that let a stranger onto a production, and "does decline
 * really send `no`" is not a question to answer by reading the implementation.
 */
interface ApprovalsRepository {

    suspend fun pending(queue: ApprovalQueue): ZillitResult<List<PendingApproval>>

    /**
     * Approves or declines [request].
     *
     * Declining is not a delete: the row leaves the queue, and the person is
     * free to ask again. Approving is what actually admits them.
     */
    suspend fun decide(
        queue: ApprovalQueue,
        request: PendingApproval,
        approved: Boolean,
    ): ZillitResult<Unit>
}
