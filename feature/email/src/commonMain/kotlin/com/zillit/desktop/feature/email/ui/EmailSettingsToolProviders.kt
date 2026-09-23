package com.zillit.desktop.feature.email.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.socket.SocketEventBus
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.core.workspace.ToolProvider
import com.zillit.desktop.feature.email.rules.EmailRulesViewModel
import com.zillit.desktop.feature.email.rules.EmailRulesRepositoryImpl
import com.zillit.desktop.feature.email.rules.EmailRulesEvent
import com.zillit.desktop.feature.email.rules.DriveFolderSource
import com.zillit.desktop.feature.email.domain.EmailFolder
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.workspace.WindowNavigator
import com.zillit.desktop.core.workspace.WorkspaceRoute
import com.zillit.desktop.feature.email.data.BccPresetRepositoryImpl
import com.zillit.desktop.feature.email.data.ContactRepositoryImpl
import com.zillit.desktop.feature.email.data.ConversationViewRepositoryImpl
import com.zillit.desktop.feature.email.data.EmailForwardingRepositoryImpl
import com.zillit.desktop.feature.email.data.EmailGroupRepositoryImpl
import com.zillit.desktop.feature.email.data.MailboxCredentialsRepositoryImpl
import com.zillit.desktop.feature.email.domain.EmailContact
import com.zillit.desktop.feature.email.domain.MailboxDirectory
import com.zillit.desktop.feature.email.domain.MailboxScope
import com.zillit.desktop.feature.email.domain.SavedContact
import com.zillit.desktop.feature.email.ui.contacts.EmailContactsEffect
import com.zillit.desktop.feature.email.ui.contacts.EmailContactsEvent
import com.zillit.desktop.feature.email.ui.contacts.EmailContactsScreen
import com.zillit.desktop.feature.email.ui.contacts.EmailContactsViewModel
import com.zillit.desktop.feature.email.ui.settings.BccPresetsEvent
import com.zillit.desktop.feature.email.ui.settings.BccPresetsViewModel
import com.zillit.desktop.feature.email.ui.settings.EmailForwardingEvent
import com.zillit.desktop.feature.email.ui.settings.EmailForwardingViewModel
import com.zillit.desktop.feature.email.ui.settings.EmailGroupsEvent
import com.zillit.desktop.feature.email.ui.settings.EmailGroupsViewModel
import com.zillit.desktop.feature.email.ui.settings.EmailSettingsEvent
import com.zillit.desktop.feature.email.ui.settings.EmailSettingsScreen
import com.zillit.desktop.feature.email.ui.settings.EmailSettingsSection
import com.zillit.desktop.feature.email.ui.settings.EmailSettingsViewModel
import com.zillit.desktop.feature.email.ui.settings.MailboxCredentialsEvent
import com.zillit.desktop.feature.email.ui.settings.MailboxCredentialsViewModel
import com.zillit.desktop.feature.email.ui.settings.SectionBinding

/**
 * The two mail windows off the sidebar that are not the mailbox: **Email
 * Settings** and **Contacts**.
 *
 * Both build their own view models from the API client rather than taking
 * them from the app graph: they are opened rarely, hold no state anyone else
 * reads, and every one of their repositories is a thin wrapper over the same
 * client. Android reaches both from the mail drawer
 * (`FolderDrawerFragment.kt:70-81`).
 */
@Suppress("LongParameterList") // Every host seam the settings pages need; a bundle would only rename them.
class EmailSettingsToolProvider(
    private val apiClient: ApiClient,
    private val config: AppConfig,
    /** The production's crew, for the group and BCC pickers. */
    private val crew: () -> List<EmailContact> = { emptyList() },
    private val isAdmin: () -> Boolean = { true },
    private val onOpenSignatures: () -> Unit = {},
    private val onCopy: (String) -> Unit = {},
    /** The mailbox's folders, for a rule's Move action. */
    private val folders: suspend () -> ZillitResult<List<EmailFolder>> = { ZillitResult.Success(emptyList()) },
    /** The Drive, for a rule's Save-attachments action; null hides the picker. */
    private val driveFolders: DriveFolderSource? = null,
    /** The socket, so a distribution group saved elsewhere lands on this page. */
    private val events: SocketEventBus? = null,
    /**
     * Which mailbox the settings belong to — the same switch the mailbox
     * flips, so the shared Accounts mailbox's forwarding, rules, presets and
     * conversation view are the ones edited while it is active.
     */
    private val scope: MailboxScope = MailboxScope.Personal,
    private val directory: MailboxDirectory? = null,
) : ToolProvider {

    override val path: String = EMAIL_SETTINGS_PATH
    override val title: String get() = str(S.email_settings)
    override val icon = ZillitIcons.Settings
    override val defaultSize: DpSize = DpSize(720.dp, 720.dp)

    @Composable
    override fun Content(route: WorkspaceRoute, navigator: WindowNavigator) {
        val settings = remember {
            EmailSettingsViewModel(
                repository = ConversationViewRepositoryImpl(apiClient, config, scope = scope, directory = directory),
                isAdmin = isAdmin,
            )
        }
        val groups = remember {
            EmailGroupsViewModel(EmailGroupRepositoryImpl(apiClient, config), crew = crew, events = events)
        }
        val presets = remember {
            BccPresetsViewModel(
                BccPresetRepositoryImpl(apiClient, config, scope = scope, directory = directory),
                crew = crew,
            )
        }
        val forwarding = remember { EmailForwardingViewModel(EmailForwardingRepositoryImpl(apiClient, config, scope)) }
        val credentials = remember {
            MailboxCredentialsViewModel(MailboxCredentialsRepositoryImpl(apiClient, config, scope = scope))
        }
        val rules = remember {
            EmailRulesViewModel(EmailRulesRepositoryImpl(apiClient, config, scope), folders, driveFolders)
        }

        // A route with a tail opens straight onto that page — the mailbox's
        // Settings menu deep-links each entry (`/email/settings/rules`).
        LaunchedEffect(route.path) {
            sectionFor(route.path)?.let { settings.onEvent(EmailSettingsEvent.Open(it)) }
        }

        val settingsState by settings.state.collectAsState()
        val groupsState by groups.state.collectAsState()
        val presetsState by presets.state.collectAsState()
        val forwardingState by forwarding.state.collectAsState()
        val credentialsState by credentials.state.collectAsState()
        val rulesState by rules.state.collectAsState()

        // Each section loads when the window opens rather than when its card
        // is opened: the cards say what is configured, and a card that reads
        // "loading" every time it is looked at is worse than one fetch.
        LaunchedEffect(Unit) {
            settings.onEvent(EmailSettingsEvent.Load)
            groups.onEvent(EmailGroupsEvent.Load)
            presets.onEvent(BccPresetsEvent.Load)
            forwarding.onEvent(EmailForwardingEvent.Load)
            credentials.onEvent(MailboxCredentialsEvent.Load)
            rules.onEvent(EmailRulesEvent.Load)
        }

        EmailSettingsScreen(
            state = settingsState,
            onEvent = settings::onEvent,
            groups = SectionBinding(groupsState, groups::onEvent),
            bccPresets = SectionBinding(presetsState, presets::onEvent),
            forwarding = SectionBinding(forwardingState, forwarding::onEvent),
            credentials = SectionBinding(credentialsState, credentials::onEvent),
            rules = SectionBinding(rulesState, rules::onEvent),
            onOpenSignatures = onOpenSignatures,
            onCopy = onCopy,
        )
    }
}

/** The mailbox's own address book — Android's `ContactListActivity`, the web's `ContactListModal`. */
class EmailContactsToolProvider(
    private val apiClient: ApiClient,
    private val config: AppConfig,
    /** Opens the composer addressed to someone. */
    private val onWriteTo: (String) -> Unit = {},
    /** The socket, so an address saved elsewhere lands on this page. */
    private val events: SocketEventBus? = null,
    /** The shared Accounts mailbox keeps its own address book. */
    private val scope: MailboxScope = MailboxScope.Personal,
) : ToolProvider {

    override val path: String = EMAIL_CONTACTS_PATH
    override val title: String get() = str(S.contacts_txt)
    override val icon = ZillitIcons.Users
    override val defaultSize: DpSize = DpSize(680.dp, 640.dp)

    @Composable
    override fun Content(route: WorkspaceRoute, navigator: WindowNavigator) {
        val viewModel = remember {
            EmailContactsViewModel(ContactRepositoryImpl(apiClient, config, scope), events = events)
        }
        val state by viewModel.state.collectAsState()

        LaunchedEffect(viewModel) {
            viewModel.onEvent(EmailContactsEvent.Load)
            viewModel.effects.collect { effect ->
                when (effect) {
                    is EmailContactsEffect.WriteTo -> onWriteTo(effect.address)
                }
            }
        }

        // `/email/contacts/new/<address>` — "Add to contacts" from a message
        // or a composer chip: the form opens with the address filled in.
        LaunchedEffect(route.path) {
            newContactAddress(route.path)?.let { address ->
                viewModel.onEvent(EmailContactsEvent.Edit(SavedContact(address = address)))
            }
        }

        EmailContactsScreen(state = state, onEvent = viewModel::onEvent)
    }
}

/** The settings page a deep-linked route names, or null for the card list. */
internal fun sectionFor(path: String): EmailSettingsSection? = when (path.removePrefix(EMAIL_SETTINGS_PATH).trim('/')) {
    "rules" -> EmailSettingsSection.Rules
    "groups" -> EmailSettingsSection.Groups
    "bcc" -> EmailSettingsSection.BccPresets
    "forwarding" -> EmailSettingsSection.Forwarding
    "credentials" -> EmailSettingsSection.Credentials
    else -> null
}

/** The address a `/email/contacts/new/<address>` route carries, or null. */
internal fun newContactAddress(path: String): String? =
    path.removePrefix(EMAIL_CONTACTS_PATH).trim('/').takeIf { it.startsWith("new/") }?.removePrefix("new/")
        ?.takeIf { it.isNotBlank() }

const val EMAIL_SETTINGS_PATH = "/email/settings"
const val EMAIL_CONTACTS_PATH = "/email/contacts"
