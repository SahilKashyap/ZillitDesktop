package com.zillit.desktop

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.database.UserSnapshot
import com.zillit.desktop.core.localization.Labels
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.network.CallOptions
import com.zillit.desktop.core.permissions.ProjectPermissions
import com.zillit.desktop.core.workspace.ToolProvider
import com.zillit.desktop.feature.auth.domain.Project
import com.zillit.desktop.feature.chat.domain.ChatPick
import com.zillit.desktop.feature.chat.domain.CrewContact
import com.zillit.desktop.feature.chat.ui.ChatToolProvider
import com.zillit.desktop.feature.calls.domain.CallMode
import com.zillit.desktop.feature.calls.domain.CallProvider
import com.zillit.desktop.feature.calls.domain.CallType
import com.zillit.desktop.feature.calls.ui.CallEvent
import com.zillit.desktop.feature.calls.ui.CallViewModel
import com.zillit.desktop.feature.chat.ui.ChatViewModel
import com.zillit.desktop.feature.crewlist.data.CrewListRepositoryImpl
import com.zillit.desktop.feature.crewlist.domain.CrewListViewer
import com.zillit.desktop.feature.crewlist.ui.CrewListToolProvider
import com.zillit.desktop.feature.crewlist.ui.CrewListViewModel
import com.zillit.desktop.feature.home.ui.MediaCapture
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
 * Attachments, voice notes and calls work here too, each told which
 * production it is acting on: files go to that production's own storage
 * (`uploaderForProject`, from its own storage settings), and a call carries
 * that production's id and the caller's id there, which the call API has
 * always been able to take.
 *
 * ## What is still the open production's
 *
 * Unread badges. The badge store counts for the production the main window
 * shows, so a widget on another one reads its messages without a count to
 * clear — the rail's numbers stay honest, which is the safer of the two
 * wrongs.
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
    options: CallOptions,
    permissions: ProjectPermissions,
    calls: CallViewModel?,
): ToolProvider? {
    val loader = projectContext ?: return null
    val meThere = project.userId.orEmpty().ifBlank { return null }
    val crew = when (val users = loader.usersOf(project.id, meThere)) {
        is ZillitResult.Success -> users.data.toCrewContacts()
        is ZillitResult.Failure -> return null
    }

    // This production's own storage decides where a file lands, so its
    // details are asked of it rather than of the open production. Without
    // them there is nowhere safe to put a file — better no attach button than
    // one that uploads into the wrong production — so the media seams are
    // left at their refusing defaults.
    val capture = (loader.projectOf(project.id, meThere) as? ZillitResult.Success)?.data?.let { snapshot ->
        homeMediaCapture(
            ready = this,
            uploader = uploaderForProject(snapshot, options),
            storageType = { snapshot.storageType },
        )
    }

    val viewModel = scopedChatViewModel(project.id, meThere, capture)

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
        // One call at a time whichever production it belongs to, so this is
        // the app's own CallViewModel — told which production to place it on.
        lines = { callLines(project.id) },
        onCall = calls?.let { vm ->
            { peer, isGroup, video, line ->
                vm.onEvent(
                    CallEvent.Place(
                        chatRoomId = if (isGroup) peer.userId else "",
                        receiverDeviceId = if (isGroup) "" else peer.deviceId.orEmpty(),
                        mode = if (isGroup) CallMode.Group else CallMode.Private,
                        type = if (video) CallType.Video else CallType.Audio,
                        displayName = peer.fullName,
                        provider = line.toProvider(),
                        receiverUserId = if (isGroup) "" else peer.userId,
                        projectId = project.id,
                        callerUserId = meThere,
                    ),
                )
            }
        },
        callLog = calls?.let { vm ->
            { CallLogTab(this, vm, otherProjectId = project.id, otherUserId = meThere) }
        },
        compact = true,
    )
}

/**
 * The conversations themselves, for one production.
 *
 * Split out so [scopedChatProvider] reads as what it assembles rather than
 * how; the seams here are the ones that had to be told which production.
 */
private fun AppGraph.Ready.scopedChatViewModel(
    projectId: String,
    meThere: String,
    capture: MediaCapture?,
) = ChatViewModel(
    repository = chatRepositoryForProject(projectId, meThere),
    nowMillis = System::currentTimeMillis,
    newUniqueId = { UUID.randomUUID().toString() },
    // Presence is a per-device fact, not a per-production one, so it carries
    // over; the id it reports against is this production's.
    presence = chatPresence,
    presenceProjectId = { projectId },
    pickAttachment = { capture?.let { pickChatAttachment(this, capture = it) } ?: ChatPick.Cancelled },
    pickAttachmentOf = { kind -> capture?.let { pickChatAttachment(this, kind, it) } ?: ChatPick.Cancelled },
    uploadMedia = { name, type, bytes, onProgress ->
        capture?.let { uploadChatMedia(this, name, type, bytes, onProgress, it) }
    },
    staticMap = { lat, lng -> fetchStaticMapBytes(this, lat, lng) },
    voice = capture?.let { chatVoice(this, it) },
)

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
