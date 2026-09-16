package com.zillit.desktop.feature.email.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitToast
import com.zillit.desktop.core.designsystem.component.ZillitToastTone
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.socket.SocketEventBus
import com.zillit.desktop.core.workspace.ToolProvider
import com.zillit.desktop.core.workspace.WindowNavigator
import com.zillit.desktop.core.workspace.WorkspaceRoute
import com.zillit.desktop.feature.email.domain.ComposeMode
import com.zillit.desktop.feature.email.domain.EmailAttachment
import com.zillit.desktop.feature.email.domain.EmailDraft
import com.zillit.desktop.feature.email.domain.EmailMessage
import com.zillit.desktop.feature.email.domain.SignatureRepository
import com.zillit.desktop.feature.email.domain.printableHtml
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the mailbox window cannot do for itself — every seam a host wires.
 *
 * Gathered so the mailbox, its pop-outs and a test build the provider the
 * same way, and so a new seam is one field rather than a new parameter.
 */
data class EmailHost(
    /** Resolves a sender's address to their crew photo; initials without one. */
    val loadAvatar: suspend (String) -> ImageBitmap? = { null },
    /** An image attachment, decoded for the reading pane's inline preview. */
    val loadThumbnail: suspend (EmailAttachment, String, String) -> ImageBitmap? = { _, _, _ -> null },
    /** Opens a web link from a body in the browser; null uses the platform handler. */
    val onOpenLink: ((String) -> Unit)? = null,
    /** Puts a print-ready page in front of the printer (or the browser's print dialog). */
    val onPrint: (title: String, html: String) -> Unit = { _, _ -> },
    /** Opens the production's calendar — the frame owns that route. */
    val onOpenCalendar: () -> Unit = {},
    /** Read receipts for a sent message; null hides "Read By User". */
    val readBy: (suspend (messageId: String) -> MailReadBy?)? = null,
    /** Whether the signed-in user is an admin here — the Email Group entry is admin-only. */
    val isAdmin: () -> Boolean = { false },
    /** A pending user gets Mail only (ZL-21084). */
    val isPending: () -> Boolean = { false },
    /** Whether files can be attached — false on a build with no storage. */
    val canAttach: Boolean = true,
    /**
     * A message another screen asked us to start, taken once when the mailbox
     * opens. Claimed rather than pushed: the composer is raised by an effect,
     * and an effect emitted before this window exists has nobody collecting
     * it and is simply lost.
     */
    val claimPendingCompose: () -> Pair<String, String>? = { null },
)

/**
 * Mail as a workspace window.
 *
 * Like Home, a `ToolProvider` rather than a privileged screen — so mail can be
 * torn off into its own OS window and sit beside a call sheet, which is the
 * thing the phone client fundamentally cannot do. The composers it opens live
 * on [composers], which outlives the window's composition: a tab switch must
 * not lose a half-written reply.
 */
class EmailToolProvider(
    private val viewModel: EmailViewModel,
    /** Everything a composer needs. */
    private val composing: Composing,
    private val host: EmailHost = EmailHost(),
) : ToolProvider {

    override val path: String = "/email"
    override val title: String = "Email"
    override val icon = ZillitIcons.Mail

    /** The composers, and their view models — shared with the pop-out window. */
    val composers: ComposerDeck = ComposerDeck(
        factory = { composer ->
            ComposeViewModel(
                deps = composing,
                mode = composer.mode,
                replyTo = composer.replyTo,
                // Reopening a draft reads the copy the Drafts folder already holds.
                editing = composer.draftId?.let(::draftById),
                addressedTo = composer.addressedTo,
                about = composer.about,
                bodyHtml = composer.bodyHtml,
                attachments = composer.attachments,
            )
        },
    )

    /** The conversations popped out of the pane, each snapshotted as it was. */
    val poppedThreads: PoppedThreads = PoppedThreads()

    private fun draftById(id: String): EmailDraft? = viewModel.state.value.draft(id)

    /** Whether [address] is someone the mailbox already knows, for the reading pane's "add to contacts". */
    private fun isKnown(address: String): Boolean =
        composers.state.value.mapNotNull { composers.viewModel(it.id) }
            .any { it.currentState.isKnown(address) } ||
            composing.crew().any { it.address.equals(address, ignoreCase = true) }

    @Composable
    override fun Content(route: WorkspaceRoute, navigator: WindowNavigator) {
        val state by viewModel.state.collectAsState()
        val downloads by viewModel.downloader.state.collectAsState()
        val folderEdit by viewModel.folderEditor.state.collectAsState()
        val open by composers.state.collectAsState()
        var toast by remember { mutableStateOf<String?>(null) }
        MailboxEffects(navigator)

        val inline = open.firstOrNull { it.window == ComposerWindow.Inline }
        val inlineViewModel = inline?.let { composers.viewModel(it.id) }

        EmailScreen(
            state = state,
            onEvent = viewModel::onEvent,
            hooks = ReadingPaneHooks(
                downloads = downloads,
                loadAvatar = host.loadAvatar,
                loadThumbnail = host.loadThumbnail,
                onOpenLink = host.onOpenLink,
                onMailTo = { address -> composers.open(ComposeMode.New, replyTo = null, addressedTo = address) },
                isKnownAddress = ::isKnown,
                readBy = host.readBy,
            ),
            folderEdit = folderEdit,
            navigation = MailNavigation(
                // The production's one calendar, not a mail-only copy.
                onOpenCalendar = {
                    host.onOpenCalendar()
                    navigator.openInNewWindow(WorkspaceRoute.Home)
                },
                onOpenContacts = { navigator.openInNewWindow(WorkspaceRoute.Tool(EMAIL_CONTACTS_PATH)) },
                onSetting = { entry -> onSetting(entry, navigator) },
                settingsEntries = settingsEntries(state),
                showsOtherViews = !host.isPending(),
            ),
            inlineCompose = inlineViewModel?.let { composeViewModel ->
                {
                    InlineComposer(
                        composer = inline,
                        viewModel = composeViewModel,
                        navigator = navigator,
                        onToast = { toast = it },
                    )
                }
            },
        )

        ZillitToast(message = toast, onDismiss = { toast = null }, tone = ZillitToastTone.Success)
    }

    /** Loading, the requests other screens make, and the mailbox's one-shot effects. */
    @Composable
    private fun MailboxEffects(navigator: WindowNavigator) {
        // Loaded when the window opens, not at startup: the mailbox calls carry
        // project and user in their headers.
        LaunchedEffect(viewModel) {
            viewModel.onEvent(EmailEvent.Load)
            host.claimPendingCompose()?.let { (address, subject) ->
                composers.open(ComposeMode.New, replyTo = null, addressedTo = address, about = subject)
            }
        }

        // Messages other screens ask for — the crew list's "write to", the
        // address book — raised whether the request came before this window
        // existed or while it was open.
        LaunchedEffect(viewModel) {
            viewModel.composeRequests.pending.collect { request ->
                if (request != null) {
                    viewModel.composeRequests.claim()
                    composers.open(
                        ComposeMode.New,
                        replyTo = null,
                        addressedTo = request.addressedTo,
                        about = request.about,
                        bodyHtml = request.bodyHtml,
                        attachments = request.attachments,
                    )
                }
            }
        }

        LaunchedEffect(viewModel) {
            viewModel.effects.collect { effect ->
                when (effect) {
                    is EmailEffect.OpenComposer ->
                        composers.open(effect.mode, effect.replyTo, effect.addressedTo, effect.about)
                    is EmailEffect.OpenDraft -> composers.openDraft(effect.draftId)
                    is EmailEffect.PopOutThread -> {
                        val popped = poppedThreads.add(effect.subject, effect.messages)
                        navigator.openInNewWindow(WorkspaceRoute.Tool(popped.routePath))
                    }
                    is EmailEffect.Print -> host.onPrint(effect.title, effect.html)
                    is EmailEffect.AddToContacts ->
                        navigator.openInNewWindow(WorkspaceRoute.Tool("$EMAIL_CONTACTS_PATH/new/${effect.address}"))
                }
            }
        }
    }

    /** The composer in the pane, with its effects wired to the deck and the mailbox. */
    @Composable
    private fun InlineComposer(
        composer: OpenComposer,
        viewModel: ComposeViewModel,
        navigator: WindowNavigator,
        onToast: (String) -> Unit,
    ) {
        val composeState by viewModel.state.collectAsState()
        ComposerEffects(composer, viewModel, navigator, onToast)
        ComposePane(
            state = composeState,
            onEvent = viewModel::onEvent,
            canAttach = host.canAttach && composing.uploader != null,
            onPopOut = {
                composers.popOut(composer.id)
                navigator.openInNewWindow(WorkspaceRoute.Tool(composer.routePath))
                // The pane goes back to the folder's newest row, as the web does.
                this@EmailToolProvider.viewModel.onEvent(EmailEvent.OpenFirst)
            },
        )
    }

    /**
     * What a composer's effects do, wherever it is drawn: close on Sent,
     * Discarded and DraftSaved (the pane then shows the folder's newest row),
     * open the file chooser, the signature manager or the address book.
     */
    @Composable
    internal fun ComposerEffects(
        composer: OpenComposer,
        viewModel: ComposeViewModel,
        navigator: WindowNavigator,
        onToast: (String) -> Unit,
        onClosed: () -> Unit = {},
    ) {
        LaunchedEffect(viewModel) {
            viewModel.effects.collect { effect ->
                when (effect) {
                    ComposeEffect.Sent, ComposeEffect.Discarded, ComposeEffect.DraftSaved -> {
                        composers.close(composer.id, save = false)
                        this@EmailToolProvider.viewModel.onEvent(EmailEvent.OpenFirst)
                        onClosed()
                    }
                    ComposeEffect.ChooseFiles ->
                        composing.chooseFiles().forEach { viewModel.onEvent(ComposeEvent.AttachFile(it)) }
                    is ComposeEffect.ChooseFilesOf ->
                        composing.chooseFilesOf(effect.kind).forEach { viewModel.onEvent(ComposeEvent.AttachFile(it)) }
                    ComposeEffect.OpenSignatures ->
                        navigator.openInNewWindow(WorkspaceRoute.Tool(SIGNATURES_PATH))
                    is ComposeEffect.AddToContacts ->
                        navigator.openInNewWindow(WorkspaceRoute.Tool("$EMAIL_CONTACTS_PATH/new/${effect.address}"))
                    is ComposeEffect.Notice -> onToast(effect.message)
                }
            }
        }
    }

    /** The web's settings popover, gated as it gates them. */
    private fun settingsEntries(state: EmailUiState): List<MailSettingsEntry> {
        val pending = host.isPending()
        val accounts = state.mailboxes.active == com.zillit.desktop.feature.email.domain.MailboxKind.Accounts
        return MailSettingsEntry.entries.filter { entry ->
            when (entry) {
                MailSettingsEntry.ImportContacts -> !pending
                MailSettingsEntry.EmailGroup -> host.isAdmin() && !pending
                MailSettingsEntry.Credentials -> !pending && !accounts
                MailSettingsEntry.MailboxTour -> state.mailboxes.hasAccounts
                else -> true
            }
        }
    }

    private fun onSetting(entry: MailSettingsEntry, navigator: WindowNavigator) {
        when (entry) {
            MailSettingsEntry.Signatures -> navigator.openInNewWindow(WorkspaceRoute.Tool(SIGNATURES_PATH))
            MailSettingsEntry.ConversationView -> viewModel.onEvent(EmailEvent.OpenConversationDialog)
            MailSettingsEntry.ImportContacts -> navigator.openInNewWindow(WorkspaceRoute.Tool(EMAIL_CONTACTS_PATH))
            MailSettingsEntry.BccPresets -> navigator.openInNewWindow(WorkspaceRoute.Tool("$EMAIL_SETTINGS_PATH/bcc"))
            MailSettingsEntry.EmailGroup ->
                navigator.openInNewWindow(WorkspaceRoute.Tool("$EMAIL_SETTINGS_PATH/groups"))
            MailSettingsEntry.Credentials ->
                navigator.openInNewWindow(WorkspaceRoute.Tool("$EMAIL_SETTINGS_PATH/credentials"))
            MailSettingsEntry.Forwarding ->
                navigator.openInNewWindow(WorkspaceRoute.Tool("$EMAIL_SETTINGS_PATH/forwarding"))
            MailSettingsEntry.Rules -> navigator.openInNewWindow(WorkspaceRoute.Tool("$EMAIL_SETTINGS_PATH/rules"))
            MailSettingsEntry.MailboxTour -> viewModel.onEvent(EmailEvent.ShowTour)
        }
    }
}

/**
 * A composer torn off into its own window — the web's `PopoutCompose`.
 *
 * Served from the mailbox's own deck, so the pop-out keeps saving into the
 * same draft with the same idempotency key. Closing the window closes the
 * composer, saving what it held.
 */
class EmailComposePopoutProvider(private val mailbox: EmailToolProvider) : ToolProvider {

    override val path: String = COMPOSE_POPOUT_PATH
    override val title: String = "New Email"
    override val icon = ZillitIcons.Edit
    override val defaultSize: DpSize = DpSize(820.dp, 720.dp)

    override fun titleFor(route: WorkspaceRoute): String =
        mailbox.composers.atRoute(route.path)?.let { mailbox.composers.viewModel(it.id)?.currentState?.title } ?: title

    @Composable
    override fun Content(route: WorkspaceRoute, navigator: WindowNavigator) {
        val open by mailbox.composers.state.collectAsState()
        val composer = open.firstOrNull { it.routePath == route.path }
        val viewModel = composer?.let { mailbox.composers.viewModel(it.id) }
        var toast by remember { mutableStateOf<String?>(null) }

        if (composer == null || viewModel == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                ZillitEmptyState(title = "This message was sent or closed.", icon = ZillitIcons.Mail)
            }
            return
        }

        val state by viewModel.state.collectAsState()
        mailbox.ComposerEffects(composer, viewModel, navigator, onToast = { toast = it }, onClosed = navigator::close)
        Box(Modifier.fillMaxSize()) {
            ComposePane(state = state, onEvent = viewModel::onEvent, onPopOut = null, canAttach = true)
            ZillitToast(
                message = toast,
                onDismiss = { toast = null },
                tone = ZillitToastTone.Success,
                modifier = Modifier.align(Alignment.BottomCenter).padding(ZillitTheme.spacing.lg),
            )
        }
    }
}

/** A conversation popped out of the pane, as it was when it was popped. */
data class PoppedThread(val id: String, val subject: String, val messages: List<EmailMessage>) {
    val routePath: String get() = "$THREAD_POPOUT_PATH/$id"
}

/** The popped-out conversations, keyed for their windows. */
class PoppedThreads {
    private val _state = MutableStateFlow<List<PoppedThread>>(emptyList())
    val state: StateFlow<List<PoppedThread>> = _state.asStateFlow()
    private var next = 0

    fun add(subject: String, messages: List<EmailMessage>): PoppedThread {
        val popped = PoppedThread(id = "thread-${next++}", subject = subject, messages = messages)
        _state.value = _state.value + popped
        return popped
    }

    fun atRoute(path: String): PoppedThread? = _state.value.firstOrNull { it.routePath == path }

    fun messageById(id: String): EmailMessage? =
        _state.value.asSequence().flatMap { it.messages.asSequence() }.firstOrNull { it.id == id }

    fun remove(id: String) {
        _state.value = _state.value.filterNot { it.id == id }
    }
}

/**
 * A conversation in a window of its own — the web's `PopoutEmailDetail`: a
 * snapshot of the thread, with reply verbs that open composers back in the
 * mailbox's deck.
 */
class EmailThreadPopoutProvider(
    private val mailbox: EmailToolProvider,
    private val host: EmailHost = EmailHost(),
) : ToolProvider {

    override val path: String = THREAD_POPOUT_PATH
    override val title: String = "Email"
    override val icon = ZillitIcons.Mail
    override val defaultSize: DpSize = DpSize(900.dp, 720.dp)

    override fun titleFor(route: WorkspaceRoute): String =
        mailbox.poppedThreads.atRoute(route.path)?.subject?.ifBlank { "(no subject)" } ?: title

    @Composable
    override fun Content(route: WorkspaceRoute, navigator: WindowNavigator) {
        val popped = mailbox.poppedThreads.atRoute(route.path)
        if (popped == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                ZillitEmptyState(title = "This conversation is no longer open.", icon = ZillitIcons.Mail)
            }
            return
        }
        Box(Modifier.fillMaxSize().background(ZillitTheme.colors.canvas)) {
            MessageTrail(
                thread = popped.messages,
                conversationView = true,
                onEvent = { event ->
                    when (event) {
                        // A reply from a popped-out thread opens a popped-out
                        // composer, as the web's `PopoutEmailDetail` does.
                        is EmailEvent.Compose -> {
                            val composer = mailbox.composers.open(event.mode, event.replyTo)
                            mailbox.composers.popOut(composer.id)
                            navigator.openInNewWindow(WorkspaceRoute.Tool(composer.routePath))
                        }
                        is EmailEvent.Print -> {
                            val messages = event.message?.let(::listOf) ?: popped.messages
                            host.onPrint(popped.subject, printableHtml(popped.subject, messages))
                        }
                        else -> Unit
                    }
                },
                hooks = ReadingPaneHooks(
                    loadAvatar = host.loadAvatar,
                    loadThumbnail = host.loadThumbnail,
                    onOpenLink = host.onOpenLink,
                    readBy = host.readBy,
                ),
            )
        }
    }
}

/**
 * Managing signatures, as a workspace window.
 *
 * Its own window rather than a dialog over the composer: editing a signature is
 * a task in its own right, and a modal over a half-written message forces one
 * of the two to be abandoned. Opened from the mailbox sidebar and from the
 * composer's signature menu.
 */
class SignatureToolProvider(
    private val repository: SignatureRepository,
    /** The socket, so a sign-off written elsewhere lands on this page. */
    private val events: SocketEventBus? = null,
) : ToolProvider {

    override val path: String = SIGNATURES_PATH
    override val title: String = "Signatures"
    override val icon = ZillitIcons.Signature

    override val defaultSize: DpSize = DpSize(640.dp, 560.dp)

    @Composable
    override fun Content(route: WorkspaceRoute, navigator: WindowNavigator) {
        val viewModel = remember { SignatureManagerViewModel(repository, events = events) }
        val state by viewModel.state.collectAsState()

        LaunchedEffect(viewModel) { viewModel.onEvent(SignatureEvent.Load) }

        SignatureManagerScreen(state = state, onEvent = viewModel::onEvent)
    }
}

const val SIGNATURES_PATH = "/email/signatures"
const val THREAD_POPOUT_PATH = "/email/message"
