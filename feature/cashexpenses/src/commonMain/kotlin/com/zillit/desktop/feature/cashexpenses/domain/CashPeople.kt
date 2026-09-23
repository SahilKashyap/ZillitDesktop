package com.zillit.desktop.feature.cashexpenses.domain

import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * Names for the user ids the cash service sends.
 *
 * The service stores people as ids. `holder_name` / `full_name` are read when a
 * row carries them, but most rows carry neither — and a top-up puts the
 * holder's *id* in `holder_name` — so every screen that fell back to what the
 * row held printed a column of ObjectIds. The web never reads those fields: each
 * name on each of its cash screens is `getUserName(user_id)`, a lookup in the
 * production's user list (`helpers.js`). This is that lookup, over the same
 * crew list the assign picker offers.
 *
 * Where the web runs out of names it prints the id. This does not: an id is not
 * something an accountant can act on, and "Unknown" at least says so.
 */
class CashPeople(crew: List<AssigneeOption> = emptyList()) {

    private val byId: Map<String, AssigneeOption> =
        crew.filter { it.userId.isNotBlank() }.associateBy { it.userId }

    /**
     * The name to show for [userId], or null when nobody can say.
     *
     * The crew list first, as on the web. Then [recorded] — whatever name the
     * row itself carried — unless that is an id too, which is looked up in
     * turn rather than printed.
     */
    fun nameOrNull(userId: String?, recorded: String? = null): String? {
        listed(userId)?.let { return it }
        val written = recorded?.trim().orEmpty()
        if (written.isEmpty()) return null
        return listed(written) ?: written.takeUnless { it == userId || it.looksLikeId() }
    }

    /** As [nameOrNull], with [UNKNOWN] in place of nothing. */
    fun nameOf(userId: String?, recorded: String? = null): String = nameOrNull(userId, recorded) ?: UNKNOWN

    private fun listed(userId: String?): String? =
        userId?.let(byId::get)?.fullName?.takeIf { it.isNotBlank() }

    companion object {
        val UNKNOWN: String get() = str(S.desktop_unknown)
    }
}

/** A Mongo ObjectId or a UUID: the two shapes an id takes on this wire, neither of them a name. */
private fun String.looksLikeId(): Boolean = OBJECT_ID.matches(this) || UUID.matches(this)

private val OBJECT_ID = Regex("^[0-9a-fA-F]{24}$")
private val UUID = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
