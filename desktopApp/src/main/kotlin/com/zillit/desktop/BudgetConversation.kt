package com.zillit.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.AudioPlayer
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.permissions.RightsKind
import com.zillit.desktop.feature.budget.domain.BudgetChatEntry
import com.zillit.desktop.feature.budget.domain.BudgetViewer
import com.zillit.desktop.feature.budget.ui.BudgetUiState
import com.zillit.desktop.feature.chat.domain.ChatAttachment
import com.zillit.desktop.feature.chat.domain.ChatScope
import com.zillit.desktop.feature.chat.domain.CrewContact
import com.zillit.desktop.feature.chat.domain.GroupRoom
import com.zillit.desktop.feature.chat.ui.ChatConversation
import com.zillit.desktop.feature.chat.ui.ChatEvent
import com.zillit.desktop.feature.chat.ui.ChatSeams
import com.zillit.desktop.feature.chat.ui.ChatViewModel
import com.zillit.desktop.feature.home.domain.NoticeAttachment
import com.zillit.desktop.feature.home.ui.decodeImageBitmap

/**
 * The conversation beside a budget.
 *
 * The budget tools discuss their documents on the same socket C&C uses, under
 * their own tool name and scoped to a department and a document — so this is
 * the chat tool's own thread, not a second implementation of one. A budget
 * room therefore arrives with replies, attachments, receipts and voice notes
 * already working.
 *
 * One view model per (tool, department, document): switching version
 * switches conversation, and each keeps its own messages rather than sharing
 * one thread that would show the wrong room for a frame after every click.
 */
@Composable
@Suppress("LongMethod") // One pane: the scope, the thread it opens, and the seams the thread needs.
internal fun BudgetConversationPane(
    ready: AppGraph.Ready,
    state: BudgetUiState,
    player: AudioPlayer? = null,
    modifier: Modifier = Modifier,
) {
    val document = state.selected ?: return
    val open = state.selectedChat ?: return
    val scope = remember(state.mode, state.scopeDepartmentId, document.id) {
        ChatScope(
            tool = state.mode.tool,
            departmentId = state.scopeDepartmentId,
            // The document is part of the scope: a budget room is created
            // against one (the server refuses "Cnc Budget Document Id
            // Required" without it) and its messages are read back the same way.
            budgetDocumentId = document.id,
            // Rooms belong to their own tool; messages always say
            // `main_budget_tool`, as all three of the web's send paths do.
            messageTool = BudgetViewer.MAIN_TOOL,
            // And every event of theirs is `budget:`-prefixed — the whole
            // conversation surface is a mirror of C&C's, not C&C itself.
            eventPrefix = "budget:",
        )
    }
    val model = remember(ready, scope) { ready.budgetChat(scope) }
    val chatState by model.state.collectAsState()
    val crew = remember(ready) { ready.crewContacts() }
    val selfId = ready.projectContext?.context?.value?.profile?.userId

    // The rail's pick becomes the thread: a person opens their private
    // conversation, a room its group thread.
    LaunchedEffect(model, open.key) {
        when (open) {
            is BudgetChatEntry.Person -> model.onEvent(
                ChatEvent.OpenThread(
                    crew.firstOrNull { it.userId == open.userId } ?: CrewContact(
                        userId = open.userId,
                        fullName = open.name,
                        designation = open.designation.ifBlank { null },
                        isAdmin = open.isAdmin,
                        deviceId = open.deviceId.ifBlank { null },
                        hasLeft = open.hasLeft,
                    ),
                ),
            )

            is BudgetChatEntry.Group -> model.onEvent(
                ChatEvent.OpenGroup(
                    GroupRoom(
                        id = open.roomId,
                        name = open.name,
                        ownedBy = open.ownedBy.ifBlank { null },
                        departmentId = open.departmentId.ifBlank { null },
                    ),
                ),
            )
        }
    }

    Column(modifier.fillMaxSize().background(ZillitTheme.colors.canvas)) {
        // The web's `chatNotAllowedForDepartmentRevokedUser`: someone who has
        // lost access to the budget can be read but no longer written to —
        // the server answers a send `budget_cnc_user_not_valid`.
        if (state.selectedChatRevoked) {
            ZillitNotice(
                text = "This person no longer has access to this budget, so new messages will be refused.",
                tone = StatusTone.Pending,
                icon = ZillitIcons.Warning,
                modifier = Modifier.fillMaxWidth().padding(ZillitTheme.spacing.md),
            )
        }
        Box(Modifier.fillMaxSize()) {
            ChatConversation(
                state = chatState,
                onEvent = model::onEvent,
                seams = ChatSeams(
                    canDownload = { state.viewer.canDownload(state.mode) },
                    requestDownloadRights = { ready.rightsRequests.ask(state.mode.title, RightsKind.Download) },
                    loadFullImage = { file -> fetchBudgetChatImage(ready, file, preview = false) },
                    onOpenUrl = ::openInBrowser,
                ),
                resolveName = { id -> crewNameOf(ready, id) ?: chatState.peer?.fullName },
                resolveContact = { id -> crew.firstOrNull { it.userId == id } },
                forwardPeople = crew.filterNot { it.userId == selfId || it.hasLeft },
                selfId = selfId,
                onOpenAttachment = { file -> openChatAttachment(ready, file) },
                loadAvatar = crewFaceLoader(ready),
                loadThumbnail = { file -> fetchBudgetChatImage(ready, file, preview = true) },
                player = player,
                loadAudio = { file -> fetchChatAudio(ready, file) },
            )
        }
    }
}

/** The crew as chat contacts — the same cut the chat tool's directory makes. */
private fun AppGraph.Ready.crewContacts(): List<CrewContact> =
    projectContext?.context?.value?.users.orEmpty()
        .filterNot { it.keepNamePrivate }
        .filter { it.fullName.isNotBlank() }
        .map { user ->
            CrewContact(
                userId = user.userId,
                fullName = user.fullName,
                designation = user.designationText(),
                department = user.department?.takeIf { it.isNotBlank() },
                email = user.email,
                isAdmin = user.isAdmin,
                deviceId = user.deviceId,
                lastActiveMillis = user.lastActiveMillis,
                hasLeft = user.status == "left" || user.status == "removed",
            )
        }

/**
 * A chat picture's bytes, decoded — the bubble's preview or the lightbox's
 * full object, through the same storage source the boards use.
 */
private suspend fun fetchBudgetChatImage(
    ready: AppGraph.Ready,
    file: ChatAttachment,
    preview: Boolean,
): androidx.compose.ui.graphics.ImageBitmap? =
    (
        ready.noticeMedia.fetch(
            NoticeAttachment(
                media = file.media,
                fileName = file.name,
                thumbnail = file.thumbnail,
                bucket = file.bucket,
                region = file.region,
            ),
            preview = preview,
        ) as? ZillitResult.Success
        )?.data?.let(::decodeImageBitmap)

/**
 * A chat view model for one budget surface.
 *
 * Deliberately thinner than the C&C one: no presence, no favourites, no
 * picker seams that the budget pane offers no buttons for. What it does have
 * is the same repository, so messages, receipts and attachments behave
 * identically.
 */
internal fun AppGraph.Ready.budgetChat(scope: ChatScope) = ChatViewModel(
    repository = chatRepositoryFor(scope),
    nowMillis = System::currentTimeMillis,
    newUniqueId = { java.util.UUID.randomUUID().toString() },
    offline = offlineSupport,
    uploadMedia = { name, type, bytes, onProgress -> uploadChatMedia(this, name, type, bytes, onProgress) },
    staticMap = { lat, lng -> fetchStaticMapBytes(this, lat, lng) },
)
