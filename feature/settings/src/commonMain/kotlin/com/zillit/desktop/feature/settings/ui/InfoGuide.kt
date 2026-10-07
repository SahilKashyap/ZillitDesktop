@file:Suppress("MaxLineLength") // Published URLs, copied from the web byte for byte.

package com.zillit.desktop.feature.settings.ui

/**
 * The two links behind a row's ⓘ besides its text — the web's `TooltipInfo` on
 * the Settings and Admin Settings buttons: **More**, the tile's page on
 * documentation.zillit.com (`utils/tutorialURLLink.js`), and **Watch video**,
 * its tutorial clip (`utils/videoURL.js`).
 *
 * @property docs the documentation page, `#anchor` included, without the query
 *   the web adds — see [docsUrl].
 * @property video the clip; the web plays its generic one for a row whose
 *   `infoVideoURL` names none, so a row with no clip of its own passes null.
 */
data class InfoGuide(val docs: String, val video: String? = null) {

    /** What Watch video plays: the tile's clip, or the web's generic one. */
    val videoUrl: String get() = video ?: STATIC_VIDEO

    /**
     * The address More opens.
     *
     * The web sets `for=admin` on it for an admin, then `project_type`,
     * "default" when the production has none, both into the query ahead of the
     * `#anchor`, as `URL.searchParams.set` does.
     */
    fun docsUrl(projectType: String, forAdmin: Boolean = true): String {
        val type = projectType.takeIf { it.isNotBlank() } ?: DEFAULT_TYPE
        val base = docs.substringBefore('#')
        val anchor = docs.substringAfter('#', "")
        val audience = if (forAdmin) "for=admin&" else ""
        return "$base?${audience}project_type=${encode(type)}" + if (anchor.isEmpty()) "" else "#$anchor"
    }

    /** `URLSearchParams`' escaping: unreserved characters stay, the rest go as UTF-8 percent bytes. */
    private fun encode(value: String): String = buildString {
        value.encodeToByteArray().forEach { byte ->
            val c = byte.toInt().toChar()
            if (byte >= 0 && (c.isLetterOrDigit() || c in "-_.~")) {
                append(c)
            } else {
                val unsigned = byte.toInt() and BYTE_MASK
                append('%').append(HEX[unsigned / HEX.length]).append(HEX[unsigned % HEX.length])
            }
        }
    }

    companion object {
        private const val DEFAULT_TYPE = "default"
        private const val HEX = "0123456789ABCDEF"
        private const val BYTE_MASK = 0xFF
        private const val DOCS = "https://documentation.zillit.com/#"
        private const val VIDEOS = "https://zillit-tutorial-videos.s3.dualstack.us-east-1.amazonaws.com/web/Settings/Adminsettings/"
        const val STATIC_VIDEO = "https://zillit-tutorial-videos.s3.dualstack.us-east-1.amazonaws.com/static%20video.mp4"

        private const val PROFILE_VIDEOS = "https://zillit-tutorial-videos.s3.dualstack.us-east-1.amazonaws.com/web/Settings/"

        val EditProfile = InfoGuide("${DOCS}edit-my-profile", "${PROFILE_VIDEOS}How%20to%20Edit%20profile%20for%20user%20Web.mp4")

        /** The web plays a different clip for an admin (`getUserOrAdminUrl`). */
        val EditProfileAdmin = InfoGuide("${DOCS}edit-my-profile", "${PROFILE_VIDEOS}How%20to%20Edit%20profile%20for%20admin%20Web.mp4")
        val InviteUser = InfoGuide("${DOCS}invite-users", "${PROFILE_VIDEOS}How%20to%20share%20invite%20code%20%20Web.mp4")
        val RecoveryEmail = InfoGuide("${DOCS}recovery-code-or-email", "${PROFILE_VIDEOS}Recovery%20code%20or%20email%20Web.mp4")
        val LeaveProject = InfoGuide("${DOCS}leave-this-project", "${PROFILE_VIDEOS}How%20to%20leave%20project%20Web.mp4")

        val ApproveNewCrew = InfoGuide("${DOCS}approve-new-user-requests", "${VIDEOS}Approve%20new%20user%20web.mp4")
        val ApproveProfile = InfoGuide("${DOCS}approve-user-profile", "${VIDEOS}How%20to%20approvereject%20User%20Profile%20Web.mp4")
        val HomeUnits = InfoGuide("${DOCS}create-update-home-units", "${VIDEOS}How%20to%20Create%2Cdelete%20or%20edit%20Home%20unit%20%20Web.mp4")
        val JoinUnits = InfoGuide("${DOCS}create-join-unit", "${VIDEOS}How%20to%20Create%2C%20Edit%20or%20Delete%20Join%20Unit%20-%20Web.mp4")
        val Departments = InfoGuide("${DOCS}create-new-department", "${VIDEOS}How%20to%20create%20new%20department%20Web.mp4")
        val Designations = InfoGuide("${DOCS}create-new-designation", "${VIDEOS}How%20to%20create%20new%20designation%20%20Web.mp4")
        val RemoteUnit = InfoGuide("${DOCS}create-remote-shooting-units", "${VIDEOS}How%20to%20Create%20remote%20shooting%20unit%20%20Web.mp4")
        val ToolAvailability = InfoGuide("${DOCS}customization-of-tools", "${VIDEOS}How%20to%20Customize%20tools%20Web.mp4")
        val ToolGroups = InfoGuide("${DOCS}manage-tool-group", "${VIDEOS}How%20to%20manage%20tool%20groups%20Web.mp4")
        val CrewListOrder = InfoGuide("${DOCS}change-department-listing-order-for-crew-list", "${VIDEOS}How%20to%20Change%20Department%20Listing%20Order%20for%20Crew%20List%20%20Web.mp4")
        val ProductionName = InfoGuide("${DOCS}edit-project-name", "${VIDEOS}How%20to%20edit%20Project%20Name%20Web.mp4")
        val PreApproved = InfoGuide("${DOCS}pre-approved-users", "${VIDEOS}How%20to%20create%20pre-approved%20users%20Web.mp4")
        val Sos = InfoGuide("${DOCS}set-view-sos-receivers", "${VIDEOS}How%20to%20set%20%26%20view%20SOS%20receivers%20%20Web.mp4")
        val UserManagement = InfoGuide("${DOCS}user-management", "${VIDEOS}user%20management%20Web.mp4")
        val PermissionGrid = InfoGuide("${DOCS}viewing-and-posting-rights-grid", "${VIDEOS}How%20to%20view%2C%20grant%20or%20revoke%20Viewing%20%26%20Posting%20Rights%20%20for%20settings%20Web%20TARAN.mp4")
        val Watermark = InfoGuide("${DOCS}watermark-logo-of-company", "${VIDEOS}How%20to%20upload%20watermark%20logo%20%20Web.mp4")
        val FileCabinet = InfoGuide("${DOCS}file-cabinet-documents", "${VIDEOS}File%20cabinet%20web.mp4")
        val CompanyDetails = InfoGuide("${DOCS}company-details", "${VIDEOS}How%20to%20make%20Company%20Details%20Web.mp4")

        /** The web names a guide page for this tile and no clip, so Watch video plays the generic one. */
        val ProductionSetup = InfoGuide("${DOCS}production-setup-guide")
        val SetupNotes = InfoGuide("${DOCS}project-setup-notes", "${VIDEOS}Project%20Set%20Up%20Notes%20Web.mp4")
        val DeleteProduction = InfoGuide("${DOCS}delete-project", "${VIDEOS}How%20to%20Delete%20project%20%20Web.mp4")
    }
}
