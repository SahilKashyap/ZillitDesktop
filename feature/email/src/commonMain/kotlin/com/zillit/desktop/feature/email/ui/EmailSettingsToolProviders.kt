package com.zillit.desktop.feature.email.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.network.ApiClient
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
) : ToolProvider {

    override val path: String = EMAIL_SETTINGS_PATH
    override val title: String = "Email Settings"
    override val icon = ZillitIcons.Settings
    override val defaultSize: DpSize = DpSize(720.dp, 720.dp)

    @Composable
    override fun Content(route: WorkspaceRoute, navigator: WindowNavigator) {
        val settings = remember {
            EmailSettingsViewModel(
                repository = ConversationViewRepositoryImpl(apiClient, config),
                isAdmin = isAdmin,
            )
        }
        val groups = remember { EmailGroupsViewModel(EmailGroupRepositoryImpl(apiClient, config), crew = crew) }
        val presets = remember { BccPresetsViewModel(BccPresetRepositoryImpl(apiClient, config), crew = crew) }
        val forwarding = remember { EmailForwardingViewModel(EmailForwardingRepositoryImpl(apiClient, config)) }
        val credentials = remember { MailboxCredentialsViewModel(MailboxCredentialsRepositoryImpl(apiClient, config)) }
        val rules = remember { EmailRulesViewModel(EmailRulesRepositoryImpl(apiClient, config), folders, driveFolders) }

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

/** The mailbox's own address book — Android's `ContactListActivity`. */
class EmailContactsToolProvider(
    private val apiClient: ApiClient,
    private val config: AppConfig,
    /** Opens the composer addressed to someone. */
    private val onWriteTo: (String) -> Unit = {},
) : ToolProvider {

    override val path: String = EMAIL_CONTACTS_PATH
    override val title: String = "Contacts"
    override val icon = ZillitIcons.Users
    override val defaultSize: DpSize = DpSize(680.dp, 640.dp)

    @Composable
    override fun Content(route: WorkspaceRoute, navigator: WindowNavigator) {
        val viewModel = remember { EmailContactsViewModel(ContactRepositoryImpl(apiClient, config)) }
        val state by viewModel.state.collectAsState()

        LaunchedEffect(viewModel) {
            viewModel.onEvent(EmailContactsEvent.Load)
            viewModel.effects.collect { effect ->
                when (effect) {
                    is EmailContactsEffect.WriteTo -> onWriteTo(effect.address)
                }
            }
        }

        EmailContactsScreen(state = state, onEvent = viewModel::onEvent)
    }
}

const val EMAIL_SETTINGS_PATH = "/email/settings"
const val EMAIL_CONTACTS_PATH = "/email/contacts"
