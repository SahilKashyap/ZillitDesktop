package com.zillit.desktop.feature.email.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.workspace.ToolProvider
import com.zillit.desktop.core.workspace.WindowNavigator
import com.zillit.desktop.core.workspace.WorkspaceRoute
import com.zillit.desktop.feature.email.domain.ComposeMode
import com.zillit.desktop.feature.email.domain.AttachmentUploader
import com.zillit.desktop.feature.email.domain.ContactRepository
import com.zillit.desktop.feature.email.domain.PickedFile
import com.zillit.desktop.feature.email.domain.SignatureRepository
import com.zillit.desktop.feature.email.domain.DraftRepository
import com.zillit.desktop.feature.email.domain.EmailAttachment
import com.zillit.desktop.feature.email.domain.EmailContact
import com.zillit.desktop.feature.email.domain.EmailDraft
import com.zillit.desktop.feature.email.domain.EmailMessage
import com.zillit.desktop.feature.email.domain.EmailRepository

/**
 * Mail as a workspace window.
 *
 * Like Home, a `ToolProvider` rather than a privileged screen — so mail can be
 * torn off into its own OS window and sit beside a call sheet, which is the
 * thing the phone client fundamentally cannot do.
 */
class EmailToolProvider(
    private val viewModel: EmailViewModel,
    /**
     * Everything a composer needs.
     *
     * Held by the mailbox because composers now stand inside it rather than in
     * windows of their own — see [ComposerDock].
     */
    private val composing: Composing,
    /**
     * The message being replied to.
     *
     * Read from what the mailbox already has open rather than re-fetched: a
     * user can only reply to something they are looking at.
     */
    private val messageById: (String) -> EmailMessage? = { null },
    /** A draft being reopened, from the Drafts folder the mailbox holds. */
    private val draftById: (String) -> EmailDraft? = { null },
    /** Resolves a sender's address to their crew photo; initials without one. */
    private val loadAvatar: suspend (String) -> androidx.compose.ui.graphics.ImageBitmap? =
        { null },
    /** An image attachment, decoded for the reading pane's inline preview. */
    private val loadThumbnail:
    suspend (EmailAttachment, String) -> androidx.compose.ui.graphics.ImageBitmap? =
        { _, _ -> null },
) : ToolProvider {

    override val path: String = "/email"
    override val title: String = "Email"
    override val icon = ZillitIcons.Mail

    @Composable
    override fun Content(route: WorkspaceRoute, navigator: WindowNavigator) {
        val state by viewModel.state.collectAsState()

        // Loaded when the window opens, not at startup: the mailbox calls carry
        // project and user in their headers.
        LaunchedEffect(viewModel) { viewModel.onEvent(EmailEvent.Load) }

        // Composers stand on the bottom edge of the mailbox rather than opening
        // windows: minimising one keeps a half-written reply to hand while the
        // thread it answers is read behind it.
        LaunchedEffect(viewModel) {
            viewModel.effects.collect { effect ->
                when (effect) {
                    is EmailEffect.OpenComposer ->
                        viewModel.composers.open(effect.mode, effect.replyTo?.id)
                    is EmailEffect.OpenDraft -> viewModel.composers.openDraft(effect.draftId)
                }
            }
        }

        val composers by viewModel.composers.state.collectAsState()

        val downloads by viewModel.downloader.state.collectAsState()
        val folderEdit by viewModel.folderEditor.state.collectAsState()
        val search by viewModel.search.state.collectAsState()

        EmailScreen(
            state = state,
            onEvent = viewModel::onEvent,
            downloads = downloads,
            folderEdit = folderEdit,
            search = search,
            loadAvatar = loadAvatar,
            loadThumbnail = loadThumbnail,
        ) {
            ComposerDock(
                composers = composers,
                deps = composing,
                messageById = messageById,
                draftById = draftById,
                onWindow = viewModel.composers::setWindow,
                onClose = viewModel.composers::close,
                onOpenSignatures = {
                    navigator.openInNewWindow(WorkspaceRoute.Tool(SIGNATURES_PATH))
                },
            )
        }
    }
}

/**
 * Managing signatures, as a workspace window.
 *
 * Its own window rather than a dialog over the composer: editing a signature is
 * a task in its own right, and a modal over a half-written message forces one
 * of the two to be abandoned. Opened from the composer's signature menu, and
 * reachable directly by route so Settings can link to it later.
 */
class SignatureToolProvider(private val repository: SignatureRepository) : ToolProvider {

    override val path: String = SIGNATURES_PATH
    override val title: String = "Signatures"
    override val icon = ZillitIcons.Mail

    override val defaultSize: DpSize = DpSize(640.dp, 560.dp)

    @Composable
    override fun Content(route: WorkspaceRoute, navigator: WindowNavigator) {
        val viewModel = remember { SignatureManagerViewModel(repository) }
        val state by viewModel.state.collectAsState()

        LaunchedEffect(viewModel) { viewModel.onEvent(SignatureEvent.Load) }

        SignatureManagerScreen(state = state, onEvent = viewModel::onEvent)
    }
}

const val SIGNATURES_PATH = "/email/signatures"
