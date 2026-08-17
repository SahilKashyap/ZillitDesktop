package com.zillit.desktop.feature.auth.domain

/**
 * Domain models for sign-in and project selection.
 *
 * Deliberately separate from the wire DTOs: the server sends 30+ fields per
 * project, of which the UI needs six. Mapping at the repository boundary means a
 * backend field rename does not ripple into a composable — the Android app leaks
 * `@Serializable` DTOs all the way into adapters, which is why a server change
 * there touches view code.
 */

/**
 * Zillit has no username and password. A device proves itself by verifying an
 * emailed code, and the resulting device identity is what carries authority —
 * so "sign in" here means "register or recover this device".
 */
data class DeviceIdentity(
    val deviceId: String,
    val email: String,
    val isPrimary: Boolean,
)

/**
 * What the server says about this device's registration.
 *
 * Three answers, not two, because "I could not ask" is not "revoked". A dropped
 * connection must never be read as a dead session — the user is on location,
 * their hotel wifi has gone, and signing them out for it would be the worst
 * possible response.
 */
enum class DeviceStatus {
    /** Still registered; whatever produced the 401 was not a dead session. */
    Valid,

    /** The server no longer knows this device. Only a fresh link will fix it. */
    Revoked,

    /** The question could not be put — offline, timeout, server error. */
    Unknown,
}

/** A production the user belongs to. */
data class Project(
    val id: String,
    val name: String,
    val code: String,
    val type: String?,
    val region: String?,
    val isFavourite: Boolean = false,
    val userId: String? = null,
    /** Set when this production is a sub-project; shown in parentheses. */
    val parentName: String? = null,
    val subType: String? = null,
    val isAdmin: Boolean = false,
    /**
     * The device asked to join and a coordinator has not answered yet.
     *
     * Pending productions are listed but cannot be opened — the crew member can
     * see their request is in flight rather than wondering whether it was sent.
     */
    val isPending: Boolean = false,
    val unreadCount: Int = 0,
    /** Epoch millis; null when the server omits it. */
    val createdOnMillis: Long? = null,
) {
    /** Whether the production can be entered. */
    val isOpenable: Boolean get() = !isPending

    /**
     * Which top-level filter this production belongs to.
     *
     * `project_type_id` is the field the web filters on, and "personal" is its
     * one special value; everything else is an entertainment production.
     */
    val isPersonal: Boolean get() = type?.equals("personal", ignoreCase = true) == true
}

/** A unit within a production — projects are frequently split by unit. */
data class Unit(
    val id: String,
    val name: String,
)

/**
 * Where a device stands with a project it has asked to join.
 *
 * Modelled explicitly because "requested but not yet approved" is a real,
 * long-lived state — a crew member can wait days for a production coordinator —
 * and the UI has to say so rather than looking broken.
 */
enum class JoinStatus { NotJoined, Pending, Approved, Rejected }

/**
 * A verified session.
 *
 * Tokens are **not** held here. They live in the OS keychain and are attached to
 * requests by the header provider; keeping them in a domain object invites them
 * into logs, crash reports and `toString()` output.
 */
data class AuthSession(
    val device: DeviceIdentity,
    val activeProject: Project?,
    val activeUnit: Unit?,
) {
    val isProjectSelected: Boolean get() = activeProject != null
}
