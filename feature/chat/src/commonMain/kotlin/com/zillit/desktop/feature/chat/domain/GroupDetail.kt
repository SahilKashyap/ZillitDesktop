package com.zillit.desktop.feature.chat.domain

/** One person in a group room, as `GET chat-room/{id}` lists `members`. */
data class GroupMember(val userId: String, val isAdmin: Boolean)

/**
 * A group room in full — what the Group info panel needs beyond the listing's
 * [GroupRoom]: who is in it, who administers it, and its picture.
 */
data class GroupDetail(
    val id: String,
    val name: String,
    val ownedBy: String?,
    /** A department's own room: it cannot be renamed, left, deleted or re-pictured. */
    val isSystemDefined: Boolean = false,
    val picture: ChatAttachment? = null,
    val createdMillis: Long? = null,
    val members: List<GroupMember> = emptyList(),
) {
    fun isMember(userId: String?): Boolean = userId != null && members.any { it.userId == userId }

    fun isAdmin(userId: String?): Boolean = userId != null && members.any { it.userId == userId && it.isAdmin }

    private val adminCount: Int get() = members.count { it.isAdmin }

    /** Renaming and deleting — an administrator's, never a system room's. */
    fun canManage(userId: String?): Boolean = !isSystemDefined && isAdmin(userId)

    /** Anyone in a user-made room may change its picture (`InfoSiderGroup.jsx`, `canEditAvatar`). */
    fun canChangePicture(userId: String?): Boolean = !isSystemDefined && isMember(userId)

    /**
     * Leaving: a member may, unless they are the room's only administrator —
     * the room would be left with nobody to run it, which the web hides the
     * button for (`adminsID.length > 1 || !CurrentUser.chat_group_admin`).
     */
    fun canLeave(userId: String?): Boolean =
        !isSystemDefined && isMember(userId) && (adminCount > 1 || !isAdmin(userId))

    /** Administrators first, then A→Z by [nameOf] — the web's member order. */
    fun membersInOrder(nameOf: (String) -> String): List<GroupMember> =
        members.sortedWith(
            compareByDescending<GroupMember> { it.isAdmin }.thenBy { nameOf(it.userId).lowercase() },
        )
}
