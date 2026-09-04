package com.zillit.desktop

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.database.UserSnapshot
import com.zillit.desktop.core.localization.Labels
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.network.CallOptions
import com.zillit.desktop.core.permissions.ProjectPermissions
import com.zillit.desktop.core.workspace.ToolProvider
import com.zillit.desktop.feature.auth.domain.Project
import com.zillit.desktop.feature.chat.domain.CrewContact
import com.zillit.desktop.feature.chat.ui.ChatToolProvider
import com.zillit.desktop.feature.chat.ui.ChatViewModel
import com.zillit.desktop.feature.crewlist.data.CrewListRepositoryImpl
import com.zillit.desktop.feature.crewlist.domain.CrewListViewer
import com.zillit.desktop.feature.crewlist.ui.CrewListToolProvider
import com.zillit.desktop.feature.crewlist.ui.CrewListViewModel
import com.zillit.desktop.feature.home.ui.decodeImageBitmap
import java.util.UUID

/**
 * The Chat and Crew List widgets' stacks for a production the app is **not**
 * open on.
 *
 * Everything here is built per production and speaks only for it: the calls
 * carry [CallOptions] naming the production and the user's id there, the chat
 * socket frames are filtered to it, and the rights come from that
 * production's own tool grid.
 *
 * ## What is deliberately left out
 *
 * Attachments, voice notes, calls and badge counts all travel through seams
 * that carry no production of their own — the media uploader, the call
 * engine and the badge store act on whichever production the main window is
 * showing. Rather than upload a file into the wrong production's storage,
 * those seams are simply not wired here; `ChatViewModel`'s defaults refuse
 * them, and the widget says so above the tool. Opening the production in the
 * main window is the way to do those things.
 */

/** The crew, as chat addresses them — the same rules the rail's chat applies. */
internal fun List<UserSnapshot>.toCrewContacts(): List<CrewContact> = this
    // The keep-name-private honour, applied before the screen ever sees the
    // list — the same rule Android's members tab keeps.
    .filterNot { it.keepNamePrivate }
    .filter { it.fullName.isNotBlank() }
    // Invited-but-not-joined people are not someone to message yet.
    .filter { it.hasJoined() }
    .map { user ->
        CrewContact(
            userId = user.userId,
            fullName = user.fullName,
            // Translated words, not the wire's label key — and the
            // placeholder "member" designation dropped here, as the raw key
            // it is on the wire, before translation hides it from the
            // comparison.
            designation = user.designationText(),
            department = user.department?.takeIf { it.isNotBlank() }?.let { Labels.translate(it) },
            email = user.email,
            isAdmin = user.isAdmin,
            deviceId = user.deviceId,
            lastActiveMillis = user.lastActiveMillis,
            // "left"/"removed" stay listed (Android keeps them in the roster)
            // but the thread shows Disconnected and refuses sends.
            hasLeft = user.status == "left" || user.status == "removed",
        )
    }

/**
 * Chat for another production: its own repository, its own crew, and none of
 * the seams that would act on the open production instead.
 *
 * Null when that production's crew cannot be read — without names the
 * directory is a list of ids, which is not worth showing.
 */
internal suspend fun AppGraph.Ready.scopedChatProvider(
    project: Project,
    permissions: ProjectPermissions,
): ToolProvider? {
    val loader = projectContext ?: return null
    val meThere = project.userId.orEmpty().ifBlank { return null }
    val crew = when (val users = loader.usersOf(project.id, meThere)) {
        is ZillitResult.Success -> users.data.toCrewContacts()
        is ZillitResult.Failure -> return null
    }

    val viewModel = ChatViewModel(
        repository = chatRepositoryForProject(project.id, meThere),
        nowMillis = System::currentTimeMillis,
        newUniqueId = { UUID.randomUUID().toString() },
        // Presence is a per-device fact, not a per-production one, so it
        // carries over; the id it reports against is this production's.
        presence = chatPresence,
        presenceProjectId = { project.id },
    )

    return ChatToolProvider(
        crew = { crew },
        selfId = { meThere },
        loadAvatar = { userId -> fetchAvatar(this, userId)?.let(::decodeImageBitmap) },
        viewModel = viewModel,
        // The download right on THIS production's C&C tool. A production whose
        // tools list never mentions it leaves chat ungated, as the phones do.
        canDownload = {
            permissions.tools.none { it.identifier == CNC_WIDGET_TOOL } ||
                permissions.canDownload(CNC_WIDGET_TOOL)
        },
        // No calls, no call log: the call engine speaks for the open
        // production only. See this file's header.
        onCall = null,
        callLog = null,
        compact = true,
    )
}

/** The Crew List for another production — the roster and its PDF, both scoped. */
internal fun AppGraph.Ready.scopedCrewProvider(
    project: Project,
    options: CallOptions,
    permissions: ProjectPermissions,
): ToolProvider = CrewListToolProvider(
    viewModel = CrewListViewModel(
        repository = CrewListRepositoryImpl(
            apiClient = apiClient,
            config = config,
            bus = socketEvents,
            // The socket's own filter: only this production's reorder frames
            // refresh this roster.
            currentProjectId = { project.id },
            callOptions = { options },
        ),
        transfer = crewListTransfer(),
        resolveViewer = { CrewListViewer.from(permissions) },
        translate = { key -> key.localised() },
    ),
    compact = true,
)

/** The C&C tool's identifier, as the rights grid names it. */
private const val CNC_WIDGET_TOOL = "cnc_section"
