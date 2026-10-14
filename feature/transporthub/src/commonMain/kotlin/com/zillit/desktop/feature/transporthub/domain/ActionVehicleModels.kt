package com.zillit.desktop.feature.transporthub.domain

/**
 * Action Vehicles — the register of everything that appears ON CAMERA.
 *
 * A separate entity from the Fleet's `/vehicle` records by design: a hero car,
 * its stunt double and a background filler are three picture vehicles, none of
 * which carries crew. The flow is role-based and the ladder is
 * request → source → approval → on set → return, split across an AD who asks,
 * a transport captain who sources, and approvers who decide.
 *
 * The vocabularies below are the server's enums, but every one of them also
 * accepts a production's own value — the web's pickers let a coordinator add
 * "Camera car" as a category and store the typed label as the value. So each
 * list here is the **built-ins**, not a closed set, and the record holds
 * strings.
 */

/** A picture vehicle's place in the flow. */
enum class AvStatus(val wire: String) {
    Requested("requested"),
    Sourced("sourced"),
    PendingApproval("pending_approval"),
    Approved("approved"),
    OnSet("on_set"),
    ReturnPending("return_pending"),
    Returned("returned"),
    Rejected("rejected"),
    Cancelled("cancelled"),
    ;

    /** Approved onward — the register both sides of the flow can see. */
    val isFinalized: Boolean get() = this in FINALIZED

    /**
     * True once the lifecycle is over.
     *
     * Nothing more happens to the record, its private comment thread included:
     * a completed request can no longer be commented on.
     */
    val isTerminal: Boolean get() = this == Returned || this == Cancelled

    /** The statuses this one may move to directly. */
    val transitions: List<AvStatus>
        get() = when (this) {
            Requested -> listOf(Sourced, Cancelled)
            Sourced -> listOf(PendingApproval, Cancelled)
            PendingApproval -> listOf(Approved, Rejected)
            Rejected -> listOf(Sourced, Cancelled)
            Approved -> listOf(OnSet, Cancelled)
            OnSet -> listOf(ReturnPending)
            ReturnPending -> listOf(Returned, OnSet)
            Returned, Cancelled -> emptyList()
        }

    companion object {
        val FINALIZED: List<AvStatus> = listOf(Approved, OnSet, ReturnPending, Returned)

        fun from(value: String?): AvStatus {
            val raw = value?.trim()?.lowercase().orEmpty()
            return entries.firstOrNull { it.wire == raw } ?: Requested
        }
    }
}

/** How the vehicle reads on camera. */
enum class AvUseType(val wire: String) {
    Interior("interior"),
    Exterior("exterior"),
    Both("both"),
    ;

    companion object {
        fun from(value: String?): AvUseType {
            val raw = value?.trim()?.lowercase().orEmpty()
            return entries.firstOrNull { it.wire == raw } ?: Both
        }
    }
}

/** Where a sourced vehicle comes from. */
enum class AvSourceType(val wire: String) {
    Rented("rented"),
    ProductionOwned("production_owned"),
    Vendor("vendor"),
    PrivateOwner("private_owner"),
    ;

    /** Production's own car: there is no owner, rate or agreement to collect. */
    val isOwned: Boolean get() = this == ProductionOwned

    companion object {
        fun from(value: String?): AvSourceType {
            val raw = value?.trim()?.lowercase().orEmpty()
            return entries.firstOrNull { it.wire == raw } ?: Rented
        }
    }
}

/** How a rate is charged. */
enum class AvRatePeriod(val wire: String) {
    Daily("daily"),
    Weekly("weekly"),
    Flat("flat"),
    ;

    companion object {
        fun from(value: String?): AvRatePeriod {
            val raw = value?.trim()?.lowercase().orEmpty()
            return entries.firstOrNull { it.wire == raw } ?: Daily
        }
    }
}

/** Whether the owner's cover is in place. */
enum class AvInsuranceStatus(val wire: String) {
    Covered("covered"),
    Pending("pending"),
    NotCovered("not_covered"),
    ;

    companion object {
        fun from(value: String?): AvInsuranceStatus {
            val raw = value?.trim()?.lowercase().orEmpty()
            return entries.firstOrNull { it.wire == raw } ?: Pending
        }
    }
}

/*
 * The built-in option lists. A production may add its own to any of them, and
 * a custom entry stores its own label as the value — which is why these are
 * plain strings and not enums.
 */

/** `value to label` for the categories the server ships. */
val avCategories: List<Pair<String, String>> = listOf(
    "hero" to "Hero",
    "stunt" to "Stunt double",
    "background" to "Background",
    "process" to "Process / rig",
    "period" to "Period",
    "specialty" to "Specialty",
)

val avConditions: List<Pair<String, String>> = listOf(
    "showroom" to "Showroom clean",
    "dressed" to "In-character",
    "dirtied" to "Dirtied",
    "distressed" to "Distressed / damaged",
    "wrecked" to "Wrecked",
)

val avModifications: List<Pair<String, String>> = listOf(
    "paint_wrap" to "Paint / wrap",
    "aging_distress" to "Aging / distress",
    "roll_cage" to "Roll cage",
    "camera_rig" to "Camera mount / rig",
    "remote_rc" to "Remote / RC system",
    "fire_suppression" to "Fire suppression",
    "breakaway_parts" to "Breakaway parts",
    "period_accurate" to "Period-accurate",
    "mechanical_prep" to "Mechanical prep",
    "interior_dressing" to "Interior dressing",
    "action_gag" to "Action gag / effect",
)

/** What the owner allows. "other" is replaced by the typed detail on save. */
val avPermittedUses: List<Pair<String, String>> = listOf(
    "static" to "Static",
    "driven" to "Driven",
    "stunts" to "Stunts",
)

/**
 * One acceptable vehicle on a request.
 *
 * A request may name several cars that would all work, so the AD's form
 * collects options rather than one vehicle — and the server derives its own
 * summary from them. Once a captain sources the car, the record's scalar
 * `make`/`model`/`colour` hold the one actually found.
 */
data class AvVariant(
    val make: String = "",
    val model: String = "",
    val year: Int? = null,
    val colors: List<String> = emptyList(),
    val images: List<HubAttachment> = emptyList(),
) {
    val isEmpty: Boolean
        get() = make.isBlank() && model.isBlank() && year == null &&
            colors.isEmpty() && images.isEmpty()

    /** "Ford Mustang 1968 · Black" — what a row shows for one option. */
    val summary: String
        get() = listOf(make, model, year?.toString().orEmpty())
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .let { head ->
                if (colors.isEmpty()) head else "$head · ${colors.joinToString(", ")}"
            }
}

/** Somebody asked to decide on a record, and what they decided. */
data class AvApprover(
    val userId: String,
    val name: String = "",
    val designation: String = "",
    /** "pending", "approved" or "rejected". */
    val status: String = "pending",
) {
    val hasApproved: Boolean get() = status.equals("approved", ignoreCase = true)
    val hasRejected: Boolean get() = status.equals("rejected", ignoreCase = true)
}

/**
 * Where one approval round stands.
 *
 * First write wins: any single approve or reject finalizes the whole request,
 * so once the round is [resolved] the reviewers still marked pending are moot
 * and the UI stops showing them as outstanding.
 */
data class AvApprovalRound(
    val approved: Int,
    val declined: Boolean,
    val approvedByName: String,
    val total: Int,
) {
    val resolved: Boolean get() = approved > 0 || declined

    companion object {
        fun of(approvers: List<AvApprover>): AvApprovalRound = AvApprovalRound(
            approved = approvers.count { it.hasApproved },
            declined = approvers.any { it.hasRejected },
            approvedByName = approvers.firstOrNull { it.hasApproved }?.name.orEmpty(),
            total = approvers.size,
        )
    }
}

/** One message on a record's private thread. */
data class AvComment(
    val id: String,
    val userId: String = "",
    val name: String = "",
    val roleLabel: String = "",
    val text: String = "",
    val createdMs: Long = 0,
    /**
     * When it was edited, or 0.
     *
     * The server does not send this yet on any deployment checked, so this is
     * inert on today's payload — three spellings are read so that whichever
     * one ships, the tag lights up with no further change. `updated_at` only
     * counts when it is meaningfully later than `created_at`: the record sets
     * the two equal at creation, and treating its presence as an edit would
     * tag every comment ever written.
     */
    val editedMs: Long = 0,
)

/** How the viewer relates to one record. The server answers this per row. */
data class AvMy(
    val isRequester: Boolean = false,
    val isApprover: Boolean = false,
    val isReturnApprover: Boolean = false,
    val inComments: Boolean = false,
)

/** A picture vehicle. */
@Suppress("LongParameterList") // One field per column the register carries.
data class ActionVehicle(
    val id: String,
    val displayName: String = "",
    val status: AvStatus = AvStatus.Requested,
    val categories: List<String> = emptyList(),
    val vehicleTypes: List<String> = emptyList(),
    val make: String = "",
    val model: String = "",
    val color: String = "",
    val year: Int? = null,
    val variants: List<AvVariant> = emptyList(),
    val description: String = "",
    val condition: String = "",
    val modifications: List<String> = emptyList(),
    val useType: AvUseType = AvUseType.Both,
    val scenes: List<String> = emptyList(),
    val location: HubLocation = HubLocation.Empty,
    val needRehearsalMs: Long = 0,
    val needShootingMs: Long = 0,
    val reminderDays: Int? = null,
    val approvedDrivers: List<String> = emptyList(),
    val notes: String = "",
    // Sourcing and the agreement — the captain's half of the record.
    val sourceType: AvSourceType? = null,
    val ownerName: String = "",
    val ownerContactNumber: String = "",
    val rate: Double? = null,
    val ratePeriod: AvRatePeriod = AvRatePeriod.Daily,
    val agreementStartMs: Long = 0,
    val agreementEndMs: Long = 0,
    val insuranceStatus: AvInsuranceStatus? = null,
    val policyRef: String = "",
    val replacementValue: Double? = null,
    val licensePlate: String = "",
    val permittedUses: List<String> = emptyList(),
    val propPlates: List<String> = emptyList(),
    val images: List<HubAttachment> = emptyList(),
    // The flow's own state.
    val requesterName: String = "",
    val requesterUserId: String? = null,
    val approvers: List<AvApprover> = emptyList(),
    val returnApprovers: List<AvApprover> = emptyList(),
    val returnRequestedMs: Long = 0,
    val rejectionReason: String = "",
    val comments: List<AvComment> = emptyList(),
    val commentAudience: List<String> = emptyList(),
    val my: AvMy = AvMy(),
    val createdMs: Long = 0,
) {
    val title: String get() = displayName.ifBlank { "—" }

    /** "Ford Mustang 1968" for a sourced car, else the options' own summary. */
    val vehicleLine: String
        get() = listOf(make, model, year?.toString().orEmpty())
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .ifBlank { variants.filterNot { it.isEmpty }.joinToString(" / ") { it.summary } }

    /** True while an owner, a rate and an agreement are worth collecting. */
    val wantsOwnerTerms: Boolean get() = sourceType?.isOwned == false
}

/**
 * What the viewer may do in Action Vehicles.
 *
 * Two different rules, deliberately not one:
 *
 *  - [canRequest] is an AD designation **or** posting access on
 *    `transportation_tool` **or** project admin — a union of three unrelated
 *    systems, so an AD keeps raising requests with no transport rights at all.
 *  - [canSource] is posting access **only**. A designation does not grant it
 *    and neither does being an admin, because the server would refuse them —
 *    showing the button would only produce a 403.
 */
data class AvCapabilities(
    val canRequest: Boolean = false,
    val canSource: Boolean = false,
) {
    companion object {
        /** Designations the server counts as an AD's, which may request. */
        val AD_DESIGNATIONS: Set<String> = setOf(
            "designation_1st_assistant_director_assistant_directors",
            "designation_2nd_assistant_director_assistant_directors",
            "designation_3rd_assistant_director_assistant_directors",
        )

        fun of(
            designationIdentifier: String?,
            hasPostingRights: Boolean,
            isAdmin: Boolean,
        ): AvCapabilities {
            val id = designationIdentifier?.trim()?.lowercase().orEmpty()
            return AvCapabilities(
                canRequest = id in AD_DESIGNATIONS || hasPostingRights || isAdmin,
                canSource = hasPostingRights,
            )
        }
    }
}

/** A picture vehicle being requested, sourced or edited. */
@Suppress("LongParameterList") // It mirrors the record it writes.
data class AvDraft(
    val displayName: String = "",
    val categories: List<String> = emptyList(),
    val vehicleTypes: List<String> = emptyList(),
    val variants: List<AvVariant> = emptyList(),
    val make: String = "",
    val model: String = "",
    val color: String = "",
    val year: Int? = null,
    val description: String = "",
    val condition: String = "",
    val modifications: List<String> = emptyList(),
    val useType: AvUseType = AvUseType.Both,
    val scenes: List<String> = emptyList(),
    val location: HubLocation = HubLocation.Empty,
    val needRehearsalMs: Long = 0,
    val needShootingMs: Long = 0,
    val reminderDays: Int? = null,
    val approvedDrivers: List<String> = emptyList(),
    val notes: String = "",
    val sourceType: AvSourceType? = null,
    val ownerName: String = "",
    val ownerContactNumber: String = "",
    val rate: Double? = null,
    val ratePeriod: AvRatePeriod = AvRatePeriod.Daily,
    val agreementStartMs: Long = 0,
    val agreementEndMs: Long = 0,
    val insuranceStatus: AvInsuranceStatus? = null,
    val policyRef: String = "",
    val replacementValue: Double? = null,
    val licensePlate: String = "",
    val permittedUses: List<String> = emptyList(),
    val propPlates: List<String> = emptyList(),
    val images: List<HubAttachment> = emptyList(),
    /**
     * True when this is the requester's own submit.
     *
     * It decides the shape of the body, and it is tied to the **form**, not to
     * the viewer's capabilities: somebody who can both request and source must
     * still send `variants[]` when raising a request, or the server refuses it
     * for having none. On a requester's submit the scalar make, model and
     * colour stay empty — the options carry them, and an array on `/source`
     * answers 406.
     */
    val isRequesterSubmit: Boolean = false,
)
