package com.zillit.desktop.feature.boxschedule.domain

import com.zillit.desktop.core.common.ZillitResult

/**
 * Who an event or note is distributed to — the web's Distribute-To value
 * (`DistributeToField.jsx`, `SelectInviteesModal.jsx`, ZL-18856).
 *
 * The server's enum is single-valued, so exactly one of the id lists is ever
 * filled: picking on one tab of the picker clears every other. All four keys
 * travel on every write, arrays never null, `userPresetId` null unless the
 * mode is [AudienceMode.Presets] (plural on the wire).
 */
enum class AudienceMode(val wire: String, val label: String) {
    None("", "Select"),
    Self("self", "Only Me"),
    AllDepartments("all_departments", "All Departments"),
    Departments("departments", "Specific Departments"),
    Users("users", "Specific Users"),
    Presets("presets", "Saved Preset"),
    ;

    companion object {
        fun fromWire(value: String?): AudienceMode = entries.firstOrNull { it.wire == value?.trim() } ?: None
    }
}

data class DiaryAudience(
    val mode: AudienceMode = AudienceMode.None,
    val userIds: List<String> = emptyList(),
    val departmentIds: List<String> = emptyList(),
    val presetId: String? = null,
) {
    val isSet: Boolean get() = mode != AudienceMode.None

    /** The closed field's line — `DistributeToField`'s summary. Blank when nothing is chosen. */
    fun summary(presetName: String?): String = when (mode) {
        AudienceMode.None -> ""
        AudienceMode.Self -> "Only Me"
        AudienceMode.AllDepartments -> "All Departments"
        AudienceMode.Departments ->
            if (departmentIds.isEmpty()) "Selected Departments" else "Selected Departments: ${departmentIds.size}"
        AudienceMode.Users ->
            "${userIds.size} ${if (userIds.size == 1) "user" else "users"} selected — click to edit"
        AudienceMode.Presets -> presetName?.takeIf { it.isNotBlank() } ?: "Preset"
    }

    /**
     * What the audience chip on a saved row reads — `DistributeAudienceChip`'s
     * `describeAudience`. Null draws no chip, so legacy rows stay clean.
     */
    val chipLabel: String?
        get() = when (mode) {
            AudienceMode.None -> null
            AudienceMode.Self -> "Only Me"
            AudienceMode.AllDepartments -> "All Departments"
            AudienceMode.Departments -> when (departmentIds.size) {
                0 -> "Departments"
                1 -> "1 Department"
                else -> "${departmentIds.size} Departments"
            }
            AudienceMode.Users -> when (userIds.size) {
                0 -> null
                1 -> "1 user"
                else -> "${userIds.size} users"
            }
            AudienceMode.Presets -> "Preset"
        }

    /** The chip's hover line, where the web gives one. */
    val chipHint: String?
        get() = when (mode) {
            AudienceMode.Self -> "Only the creator can see this"
            AudienceMode.AllDepartments -> "Visible to every department"
            AudienceMode.Departments -> if (departmentIds.isEmpty()) "No departments selected" else null
            AudienceMode.Presets -> "Distributed to a saved user preset"
            else -> null
        }

    /**
     * The first thing wrong with this audience, in the web's validation order,
     * or null when it is complete. [noun] is "event" or "note".
     */
    fun problem(noun: String): String? = when {
        mode == AudienceMode.None -> "Please choose who to distribute this $noun to"
        mode == AudienceMode.Users && userIds.isEmpty() -> "Pick at least one user"
        mode == AudienceMode.Departments && departmentIds.isEmpty() -> "Pick at least one department"
        mode == AudienceMode.Presets && presetId.isNullOrBlank() -> "Select a preset"
        else -> null
    }

    companion object {
        /** What a Personal Note sends — nothing to distribute. */
        val Nobody = DiaryAudience()
    }
}

/** A crew member as the diary's pickers show them. */
data class DiaryPerson(
    val id: String,
    val fullName: String,
    val department: String = "",
    val designation: String = "",
    val isAdmin: Boolean = false,
) {
    /** "Camera · Focus Puller" — the picker row's second line. */
    val subtitle: String get() = listOf(department, designation).filter { it.isNotBlank() }.joinToString(" · ")
}

data class DiaryDepartment(val id: String, val name: String)

data class PresetMember(val id: String, val fullName: String, val designation: String)

/** A saved group of users — `/api/v2/user-preset`, the web's `mapPreset`. */
data class UserPreset(val id: String, val name: String, val members: List<PresetMember>) {
    val memberCount: Int get() = members.size
}

/**
 * The crew and departments the audience pickers offer — the host's project
 * context and department catalogue. People are read from memory and cannot
 * fail; departments are a network read that can.
 */
interface DiaryDirectory {
    fun people(): List<DiaryPerson>

    suspend fun departments(): ZillitResult<List<DiaryDepartment>>

    companion object {
        val None: DiaryDirectory = object : DiaryDirectory {
            override fun people(): List<DiaryPerson> = emptyList()
            override suspend fun departments(): ZillitResult<List<DiaryDepartment>> = ZillitResult.Success(emptyList())
        }
    }
}

/** The web's `checkEmail`: something@somewhere.tld, case-insensitive. */
object GuestEmails {
    private val EMAIL = Regex("""^[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}$""", RegexOption.IGNORE_CASE)

    fun isValid(text: String): Boolean = EMAIL.matches(text.trim())

    /**
     * The typed addresses onto the wire shape, keeping the `_id` of every
     * address the event already had so the server does not re-invite them.
     */
    fun toWire(emails: List<String>, original: List<GuestEmail>, newId: () -> String): List<GuestEmail> =
        emails.map { mail ->
            val known = original.firstOrNull { it.mail.equals(mail, ignoreCase = true) }
            GuestEmail(id = known?.id ?: newId(), mail = mail)
        }
}
