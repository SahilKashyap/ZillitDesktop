package com.zillit.desktop

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.ZillitLog
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.config.JvmConfigLoader
import com.zillit.desktop.core.datastore.PreferenceStore
import com.zillit.desktop.core.datastore.PreferenceStoreFactory
import com.zillit.desktop.core.datastore.PreferenceScope
import com.zillit.desktop.core.datastore.ZillitPreferences
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.HttpClientFactory
import com.zillit.desktop.core.network.OkHttpEngineProvider
import com.zillit.desktop.core.network.HeaderContext
import com.zillit.desktop.core.network.ReadScope
import com.zillit.desktop.core.network.HeaderCrypto
import com.zillit.desktop.core.badges.BadgeStore
import com.zillit.desktop.core.database.LabelCache
import com.zillit.desktop.core.database.ProjectCache
import com.zillit.desktop.core.database.ProjectListCache
import com.zillit.desktop.core.database.ScreenplayCache
import com.zillit.desktop.core.database.ZillitDatabase
import com.zillit.desktop.core.database.SyncDatabaseFactory
import com.zillit.desktop.core.database.ZillitDatabaseFactory
import com.zillit.desktop.core.database.sync.SyncDatabase
import com.zillit.desktop.core.sync.ConnectivityMonitor
import com.zillit.desktop.core.sync.DraftStore
import com.zillit.desktop.core.sync.OfflineSupport
import com.zillit.desktop.feature.chat.data.ChatSendHandler
import com.zillit.desktop.feature.purchaseorder.data.PoSyncHandler
import com.zillit.desktop.feature.timecard.data.TimecardSaveHandler
import com.zillit.desktop.feature.timecard.data.TimecardSubmitHandler
import com.zillit.desktop.core.sync.SqlDraftStore
import com.zillit.desktop.core.sync.SqlOutboxStore
import com.zillit.desktop.core.sync.SyncEngine
import com.zillit.desktop.core.sync.SyncHandlerRegistry
import com.zillit.desktop.core.sync.SyncScope
import io.ktor.client.request.head
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import com.zillit.desktop.core.localization.LabelStore
import com.zillit.desktop.core.localization.Labels
import com.zillit.desktop.core.localization.PresetLabelSource
import com.zillit.desktop.core.security.DatabaseKeyManager
import com.zillit.desktop.core.session.ProjectContextLoader
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.core.network.ZillitHeaderProvider
import com.zillit.desktop.core.network.headersFor
import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.units.UnitRepository
import com.zillit.desktop.feature.settings.account.AccountRepository
import com.zillit.desktop.feature.settings.account.AccountRepositoryImpl
import com.zillit.desktop.feature.settings.admin.data.AdminRepositoryImpl
import com.zillit.desktop.feature.settings.admin.domain.AdminRepository
import com.zillit.desktop.feature.settings.approvals.ApprovalsRepository
import com.zillit.desktop.feature.settings.approvals.CrewDepartment
import com.zillit.desktop.feature.settings.approvals.CrewPresets
import com.zillit.desktop.feature.settings.approvals.CrewRole
import com.zillit.desktop.feature.settings.approvals.ApprovalsRepositoryImpl
import com.zillit.desktop.core.units.UnitRepositoryImpl
import com.zillit.desktop.core.socket.SocketConfig
import com.zillit.desktop.core.socket.SocketConnectionState
import com.zillit.desktop.core.socket.SocketEventBus
import com.zillit.desktop.feature.cardexpenses.data.CardRepositoryImpl
import com.zillit.desktop.feature.dealmemo.data.DealMemoRepositoryImpl
import com.zillit.desktop.feature.dealmemo.domain.DealMemoRepository
import com.zillit.desktop.feature.documentdistribution.data.DocDistRepositoryImpl
import com.zillit.desktop.feature.documentdistribution.domain.DocDistRepository
import com.zillit.desktop.feature.accounthub.data.AccountHubRepositoryImpl
import com.zillit.desktop.feature.accounthub.domain.AccountHubRepository
import com.zillit.desktop.feature.drive.data.DriveRepositoryImpl
import com.zillit.desktop.feature.drive.domain.DriveRepository
import com.zillit.desktop.feature.payroll.data.PayrollRepositoryImpl
import com.zillit.desktop.feature.payroll.domain.PayrollRepository
import com.zillit.desktop.feature.purchaseorder.data.PurchaseOrderRepositoryImpl
import com.zillit.desktop.feature.purchaseorder.domain.PurchaseOrderRepository
import com.zillit.desktop.feature.timecard.data.TimecardRepositoryImpl
import com.zillit.desktop.feature.timecard.domain.TimecardRepository
import com.zillit.desktop.feature.cardexpenses.domain.CardRepository
import com.zillit.desktop.feature.cashexpenses.data.CashRepositoryImpl
import com.zillit.desktop.feature.cashexpenses.domain.CashRepository
import com.zillit.desktop.feature.calls.data.CallApi
import com.zillit.desktop.feature.calls.data.CallCoordinator
import com.zillit.desktop.feature.calls.data.CallStatusPlane
import com.zillit.desktop.feature.calls.data.FirestoreCallStatusPlane
import com.zillit.desktop.feature.calls.data.NoopCallStatusPlane
import com.zillit.desktop.feature.calls.domain.NoopCallEngine
import com.zillit.desktop.core.socket.SocketIoClient
import com.zillit.desktop.core.socket.ZillitSocketEvents
import com.zillit.desktop.feature.home.data.BadgeSourceImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import com.zillit.desktop.core.security.AesCbcCryptoEngine
import com.zillit.desktop.core.security.ConfiguredCryptoKeyProvider
import com.zillit.desktop.core.security.FallbackCryptoKeyProvider
import com.zillit.desktop.core.security.ApiKeySetup
import com.zillit.desktop.core.security.KeychainCryptoKeyProvider
import com.zillit.desktop.core.security.KeychainSecureStore
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import com.zillit.desktop.core.localization.labelRefreshTrigger
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import com.zillit.desktop.core.security.SecureStore
import com.zillit.desktop.feature.auth.data.AuthRepositoryImpl
import com.zillit.desktop.feature.auth.data.ProjectRepositoryImpl
import com.zillit.desktop.feature.auth.domain.AuthRepository
import com.zillit.desktop.core.common.ZillitLogging
import com.zillit.desktop.core.common.currentPlatform
import com.zillit.desktop.feature.auth.data.DeviceInfo
import com.zillit.desktop.feature.auth.data.PresetRepositoryImpl
import com.zillit.desktop.feature.auth.data.QrLoginRepositoryImpl
import com.zillit.desktop.feature.auth.data.generateDeviceId
import com.zillit.desktop.feature.auth.data.secureLoginCode
import com.zillit.desktop.feature.auth.domain.PresetRepository
import com.zillit.desktop.feature.home.data.HomeFeedRepositoryImpl
import com.zillit.desktop.core.database.ChatCache
import com.zillit.desktop.core.database.LocalCacheWiper
import com.zillit.desktop.feature.notifications.domain.NotificationsRepository
import com.zillit.desktop.feature.notifications.domain.NotificationDecoder
import com.zillit.desktop.feature.notifications.data.NotificationsRepositoryImpl
import com.zillit.desktop.core.database.EmailCache
import com.zillit.desktop.feature.email.data.AwsCredentials
import com.zillit.desktop.feature.email.data.BoxAttachmentUploader
import com.zillit.desktop.feature.email.data.BoxAuthSource
import com.zillit.desktop.feature.email.domain.BOX_ROOT_FOLDER
import com.zillit.desktop.feature.email.domain.BoxSettings
import com.zillit.desktop.feature.email.domain.RoutingAttachmentUploader
import com.zillit.desktop.feature.email.domain.storageKindOf
import com.zillit.desktop.feature.email.data.EmailRealtimeSource
import com.zillit.desktop.feature.email.data.S3AttachmentUploader
import com.zillit.desktop.feature.email.data.SuitableRegionSource
import com.zillit.desktop.feature.email.domain.StorageTargetSource
import com.zillit.desktop.feature.email.domain.AttachmentUploader
import com.zillit.desktop.feature.email.data.ContactRepositoryImpl
import com.zillit.desktop.feature.email.data.DraftRepositoryImpl
import com.zillit.desktop.feature.email.data.EmailRepositoryImpl
import com.zillit.desktop.feature.email.data.FolderRepositoryImpl
import com.zillit.desktop.feature.email.data.SignatureRepositoryImpl
import com.zillit.desktop.feature.email.domain.ContactRepository
import com.zillit.desktop.feature.email.domain.DraftRepository
import com.zillit.desktop.feature.email.domain.FolderRepository
import com.zillit.desktop.feature.email.domain.SignatureRepository
import com.zillit.desktop.feature.email.domain.EmailRepository
import com.zillit.desktop.feature.home.calendar.CalendarRepository
import com.zillit.desktop.feature.home.calendar.CalendarRepositoryImpl
import com.zillit.desktop.feature.home.data.HomeRealtimeSource
import com.zillit.desktop.feature.home.data.S3NoticeMediaSource
import com.zillit.desktop.feature.home.domain.NoticeMediaSource
import com.zillit.desktop.feature.chat.data.ChatRepository
import com.zillit.desktop.feature.chat.data.ChatRepositoryImpl
import com.zillit.desktop.feature.home.data.NoticeDecryptor
import com.zillit.desktop.feature.home.data.ToolsRepositoryImpl
import com.zillit.desktop.feature.home.domain.HomeFeedRepository
import com.zillit.desktop.feature.home.domain.ToolsRepository
import com.zillit.desktop.feature.auth.domain.ProjectRepository
import com.zillit.desktop.core.remoteconfig.RemoteConfigRepository
import com.zillit.desktop.core.remoteconfig.RemoteConfigRepositoryImpl
import com.zillit.desktop.feature.auth.domain.QrLoginRepository

/**
 * Wires the app together.
 *
 * Hand-rolled rather than Koin for now: the graph is a dozen objects with no
 * cycles and no scoping beyond "one per process", and hand-wiring keeps
 * construction order explicit and traceable. Koin earns its place once feature
 * modules start contributing their own bindings (M4 onward).
 *
 * Deliberately fails to construct if configuration is missing — see [Unconfigured].
 */
/** What the `deviceInfo` header reports about this machine. */
private fun currentDeviceDescription(): com.zillit.desktop.core.network.DeviceDescription {
    val platform = currentPlatform()
    return com.zillit.desktop.core.network.DeviceDescription(
        // The Android client reports the live connection type. Desktop has no
        // cheap cross-platform equivalent and the backend only logs it, so a
        // constant is honest rather than fabricated.
        network = "unknown",
        osVersion = platform.name,
        deviceName = System.getProperty("os.name") ?: "Desktop",
        deviceType = "desktop",
    )
}

/**
 * The language the OS is set to, as a bare code — `en`, `fr`, `he`.
 *
 * What the label endpoints expect, and what every other client sends: iOS reads
 * `Locale.preferredLanguages.first`, Android splits the device locale on `_`.
 * A region-qualified value (`en_GB`) is not a language the backend has a
 * dictionary for.
 */
private fun osLanguage(): String = java.util.Locale.getDefault().language.ifBlank { "en" }

/**
 * Which language the app asks for, kept current as the preference changes.
 *
 * A flow rather than a value read at startup: changing language must refetch
 * the dictionaries, and a snapshot taken here would mean a restart to see the
 * change. The preference is empty until the user chooses one, which is what
 * makes "follow the OS" expressible at all (see [ZillitPreferences.Language]).
 */
private fun CoroutineScope.trackUiLanguage(preferences: PreferenceStore): StateFlow<String> {
    val language = MutableStateFlow(osLanguage())
    launch {
        preferences.observe(ZillitPreferences.Language).collect { chosen ->
            language.value = chosen.ifBlank { osLanguage() }
        }
    }
    return language
}

/** How this machine names itself in the other device's Linked Devices list. */
private fun currentDeviceInfo(): DeviceInfo {
    val platform = currentPlatform()
    return DeviceInfo(
        // Shown to the user when they approve the scan, so it has to be
        // recognisable — "Desktop" alone is useless with three machines.
        name = System.getProperty("user.name")?.let { "$it's ${platform.os.name}" } ?: "Zillit Desktop",
        type = "desktop",
        osVersion = platform.name,
    )
}

/**
 * Whether the developer asked for full request/response bodies in the log.
 *
 * Announced loudly when on: a log full of message content is not something to
 * leave running by accident, and the line is the only warning anyone gets.
 */
private fun logBodiesRequested(): Boolean {
    val requested = System.getProperty("zillit.http").equals("body", ignoreCase = true)
    if (requested) {
        ZillitLog.w("Startup") {
            "HTTP BODY LOGGING IS ON — request and response bodies are being written to " +
                "the log file. Do not leave this on, and do not share the log."
        }
    }
    return requested
}

/** The storage credentials from remote config, when both halves are present. */
private suspend fun awsKeyPair(remoteConfig: RemoteConfigRepository): Pair<String, String>? {
    val remote = remoteConfig.current()
    val access = remote?.awsAccessKey?.takeIf { it.isNotBlank() } ?: return null
    val secret = remote?.awsSecretKey?.takeIf { it.isNotBlank() } ?: return null
    return access to secret
}

/**
 * Reports a rejected socket handshake as an expired session.
 *
 * A handshake the server refuses to authenticate is the same news as a 401,
 * arriving on a different wire. Folded onto the one signal so there is a single
 * answer to what it means, rather than the socket growing its own idea of when
 * to sign someone out.
 *
 * Only [ZillitError.Unauthorized]. A socket that merely ran out of retries is
 * offline, and being offline is something a user on location rides out —
 * signing them out for it would be the app's worst behaviour at exactly the
 * wrong moment.
 */
/**
 * Tells the server who just connected.
 *
 * The handshake authenticates the socket; it does not put it in a room. The
 * server learns which user and production a connection belongs to from an
 * explicit `user:join`, and marks presence from `mark_online` — the pair iOS
 * sends from its own connect handler (`ChatSocketHelper.emitUserJoinEvent`,
 * then `emitForUserOnline`). Without them a socket connects, is acknowledged,
 * and then receives nothing: chat arrivals, call invites, presence and every
 * notification raised from them fail together, silently and with no error to
 * point at.
 *
 * Sent on every Connected rather than once. A reconnect is a new socket as far
 * as the server is concerned, with no memory of the rooms the old one joined,
 * and this app reconnects on its own schedule (see ReconnectPolicy).
 *
 * `user:join` carries an empty payload — identity comes from the handshake
 * headers, and iOS sends `[]` here too. `mark_online` carries the production,
 * matching iOS's `["project_id": pId]`.
 */
private fun CoroutineScope.announceSelfOnSocketConnect(
    socketClient: SocketIoClient,
    activeProject: MutableStateFlow<com.zillit.desktop.feature.auth.domain.Project?>,
) = launch {
    socketClient.connectionState.collect { state ->
        if (state !is SocketConnectionState.Connected) return@collect

        // No production open means nothing to join; the next open reconnects.
        val projectId = activeProject.value?.id ?: return@collect

        socketClient.emit(
            ZillitSocketEvents.Session.JoinUser,
            kotlinx.serialization.json.JsonArray(emptyList()),
            kotlinx.serialization.json.JsonArray.serializer(),
        )
        socketClient.emit(
            ZillitSocketEvents.Session.MarkOnline,
            kotlinx.serialization.json.buildJsonObject {
                put("project_id", kotlinx.serialization.json.JsonPrimitive(projectId))
            },
            kotlinx.serialization.json.JsonObject.serializer(),
        )
        ZillitLog.i("Socket") { "announced user:join + mark_online" }
    }
}

private fun CoroutineScope.reportSocketRejections(
    socketClient: SocketIoClient,
    sessionExpired: MutableSharedFlow<Unit>,
) = launch {
    socketClient.connectionState.collect { state ->
        val rejected = (state as? SocketConnectionState.Failed)?.error
        if (rejected is ZillitError.Unauthorized) sessionExpired.tryEmit(Unit)
    }
}

/**
 * Everything that has to happen when the open production changes.
 *
 * Extracted from the graph because the **order** is the substance: the request
 * headers must carry the new production before anything fires, and the socket
 * must be told to leave before the counts are cleared. Inline among two dozen
 * constructor calls, that ordering reads like incidental sequencing.
 */
@Suppress("LongParameterList") // Collaborators, not configuration; each is used once.
private suspend fun onProjectOpened(
    project: com.zillit.desktop.feature.auth.domain.Project?,
    preferences: PreferenceStore,
    headerContext: MutableStateFlow<HeaderContext>,
    activeProject: MutableStateFlow<com.zillit.desktop.feature.auth.domain.Project?>,
    remoteConfig: com.zillit.desktop.core.remoteconfig.RemoteConfigRepository,
    badges: BadgeStore,
    socket: SocketIoClient,
    socketUrl: String,
    headerProvider: ZillitHeaderProvider,
    projectContext: ProjectContextLoader?,
    scope: CoroutineScope,
) {
    // Point project-scoped preferences at the new production before anything
    // can read them, and make subsequent requests carry the new project.
    preferences.setActiveProject(project?.id)
    headerContext.update { it.copy(projectId = project?.id, userId = project?.userId) }
    activeProject.value = project

    if (project == null) {
        // Counts, credentials, cached rights and the connection all belong to a
        // production. Cached permissions surviving a switch would let one
        // production's rights answer questions asked inside another.
        badges.clear()
        remoteConfig.clear()
        projectContext?.clear(activeProject.value?.id)
        socket.disconnect()
        return
    }

    // Who am I, what is this production, who else is on it — cache first.
    // A production this device has opened before opens NOW: the cached
    // context is enough to stand the shell up, and the network's fresher
    // answers land behind it. Only a first open, with nothing cached, waits
    // — and then only for the essentials, fetched in parallel rather than
    // the one-after-another that used to be most of the wait.
    val warm = projectContext?.publishCached(project.id) == true
    if (warm) {
        scope.launch {
            projectContext.refresh(project.id)
            refreshRemoteConfig(remoteConfig)
        }
    } else {
        kotlinx.coroutines.coroutineScope {
            launch { projectContext?.refresh(project.id) }
            launch { refreshRemoteConfig(remoteConfig) }
        }
    }

    socket.connect(
        SocketConfig(
            url = socketUrl,
            authHeaders = {
                headerProvider.headersFor(RequestModule.SocketHandshake, bodyJson = null, projectId = null)
            },
        ),
    )
    // Counts never gate the open; the rail draws them when they land.
    scope.launch { badges.refresh() }
}

/**
 * Strictly after the header context is updated: this call carries project and
 * user in its `moduledata`, so firing it first would send the previous
 * production's context — or none at all on first sign-in.
 *
 * Not fatal. Maps and uploads degrade without these, but the rest of the app
 * works, and blocking a production open on a credential fetch is a worse trade.
 */
private suspend fun refreshRemoteConfig(
    remoteConfig: com.zillit.desktop.core.remoteconfig.RemoteConfigRepository,
) {
    when (val loaded = remoteConfig.refresh()) {
        is ZillitResult.Failure -> ZillitLog.w("Startup") {
            "third-party credentials unavailable: ${loaded.error.technical}"
        }
        is ZillitResult.Success -> Unit
    }
}

sealed interface AppGraph {

    /**
     * Everything wired and ready.
     */
    data class Ready(
        val config: AppConfig,
        val preferences: PreferenceStore,
        val secureStore: SecureStore,
        val authRepository: AuthRepository,
        val projectRepository: ProjectRepository,
        val qrLoginRepository: QrLoginRepository,
        val presetRepository: PresetRepository,
        /**
         * The translated label dictionaries.
         *
         * Held here as well as installed into `Labels` because the refresh has
         * to be driven from somewhere — the global answers lookups, it does not
         * fetch.
         */
        val labelStore: LabelStore,
        val toolsRepository: ToolsRepository,
        val homeFeedRepository: HomeFeedRepository,
        /**
         * The notice-body cipher, exposed so the Info and Confidential Info
         * boards can be built on the Home repository with a different segment.
         */
        val noticeDecryptor: NoticeDecryptor,
        val chatRepository: ChatRepository,
        /** The chat header's green-dot feed; null without Firebase configuration. */
        val chatPresence: com.zillit.desktop.feature.chat.data.DevicePresenceSource?,
        /** The notification list's source — see `NotificationsToolProvider`. */
        val notificationsRepository: NotificationsRepository,
        val homeRealtime: HomeRealtimeSource,
        val emailRealtime: EmailRealtimeSource,
        val calendarRepository: CalendarRepository,
        val emailRepository: EmailRepository,
        val draftRepository: DraftRepository,
        val contactRepository: ContactRepository,
        val signatureRepository: SignatureRepository,
        val folderRepository: FolderRepository,
        val attachmentUploader: AttachmentUploader,
        val projectContext: ProjectContextLoader?,
        val projectCache: ProjectCache?,
        /** The picker's last list, so productions show without a network. */
        val projectListCache: ProjectListCache?,
        /** Zillit Draft's scripts, per production; null when the database did not open. */
        val screenplayCache: ScreenplayCache?,
        val emailCache: EmailCache?,
        val unitRepository: UnitRepository,
        /** The admin's two approval queues — joining crew and profile changes. */
        val approvalsRepository: ApprovalsRepository,
        /** Everything else on the administration pages: crew, structure, tools. */
        val adminRepository: AdminRepository,
        /** The reader's own profile, recovery address, devices and membership. */
        val accountRepository: AccountRepository,
        /** Petty cash and out-of-pocket: floats, receipt batches, reconciliation. */
        val cashRepository: CashRepository,
        /** Production expense cards: cards, statements, receipts, approvals. */
        val cardRepository: CardRepository,
        /** Purchase orders: commitments raised before the spend happens. */
        val purchaseOrderRepository: PurchaseOrderRepository,
        /** Weekly timecards, from entry through to payroll. */
        val timecardRepository: TimecardRepository,
        /** Payroll runs: a week's approved timecards, checked and paid. */
        val payrollRepository: PayrollRepository,
        /** Deal memos: the terms every other finance tool reads from. */
        val dealMemoRepository: DealMemoRepository,
        /** The finance console's own configuration: setup, chart, vendors, approvals. */
        val accountHubRepository: AccountHubRepository,
        /** The distribution library: what the production issues, and to whom. */
        val docDistRepository: DocDistRepository,
        /** The production's shared file store. */
        val driveRepository: DriveRepository,
        /** A plain HTTP client for fetches outside the Zillit API — map tiles. */
        val httpClient: io.ktor.client.HttpClient,
        /** Reads notice media from the production's storage. */
        val noticeMedia: NoticeMediaSource,
        /** The production's storage region/bucket, cached after first ask. */
        val storageTarget: StorageTargetSource,
        val badgeStore: BadgeStore,
        /** The signed REST client — for host-level fetches with no feature home. */
        val apiClient: com.zillit.desktop.core.network.ApiClient,
        /**
         * Mints the encrypted per-request headers directly.
         *
         * For the rare host-level need that is not a REST call through
         * [apiClient] — the Budget Builder hands the embedded application a
         * `moduledata` blob so its own fetches carry the session's identity.
         */
        val headerProvider: com.zillit.desktop.core.network.RequestHeaderProvider,
        /** Per-screen drill-downs — a tool asking for its own tab counts. */
        val badgeDrilldown: com.zillit.desktop.core.badges.BadgeDrilldown,
        val socketEvents: SocketEventBus,
        /** The one call state machine; every surface reads it, none owns it. */
        val callCoordinator: CallCoordinator,
        /** The calling REST surface, shared with the coordinator. */
        val callApi: CallApi,
        /** The media stack behind it — the host embeds its video surface. */
        val callEngine: com.zillit.desktop.feature.calls.domain.CallEngine,
        val remoteConfigRepository: RemoteConfigRepository,
        val apiKeySetup: ApiKeySetup,
        /**
         * Fires when the server rejects a call as unauthenticated.
         *
         * Consumed by the shell, which decides whether it means the session
         * expired — during sign-in a 401 is just a wrong code.
         */
        val sessionExpired: SharedFlow<Unit>,
        /** Null until the user has entered API keys on this machine. */
        val hasApiKeys: Boolean,
        /** One answer to "are we online?", fed by every REST call and the socket. */
        val connectivity: ConnectivityMonitor,
        /**
         * The outbox: work done offline, sent when the network returns.
         * Null when the durable store could not be opened — the app then runs
         * online-only, exactly as before it existed.
         */
        val syncEngine: SyncEngine?,
        /** Drafts that survive a restart. Null under the same condition. */
        val draftStore: DraftStore?,
        /** The two above plus the online flag, for the view models that work offline. */
        val offlineSupport: OfflineSupport?,
    ) : AppGraph

    /**
     * No usable configuration on this machine.
     *
     * Surfaced as a first-class state rather than a crash: an unconfigured
     * install is a deployment problem the user can be told about, and a stack
     * trace on a black window tells them nothing. See `zillit.properties` in
     * `JvmConfigLoader`.
     */
    data class Unconfigured(val reason: String) : AppGraph

    companion object {
        // Linear construction of one graph; splitting it hides the order. The
        // branches are the optional caches (`database?.let`), nothing else.
        @Suppress("LongMethod", "CyclomaticComplexMethod")
        fun build(): AppGraph {
            // Before anything else: Napier drops every log until a backend is
            // registered, so any logging above this line goes nowhere.
            ZillitLogging.initialise(verbose = System.getProperty("zillit.env") != "prod")

            val preferences = PreferenceStoreFactory.create()
            val secureStore = KeychainSecureStore()

            val config = when (val loaded = JvmConfigLoader().load()) {
                is ZillitResult.Success -> loaded.data.also {
                    // Which environment is live is the first thing anyone
                    // debugging needs to know.
                    ZillitLog.i("Startup") { "Environment: ${it.environment.id} → ${it.baseUrl(ZillitService.Core)}" }
                }
                is ZillitResult.Failure -> return Unconfigured(
                    loaded.error.technical ?: loaded.error.userMessage,
                )
            }

            // The header context is mutable state: device id arrives at
            // registration and project/user change on every project switch, so
            // a snapshot taken here would be stale within one session.
            val headerContext = MutableStateFlow(HeaderContext(deviceId = ""))
            val keyProvider = KeychainCryptoKeyProvider(secureStore)

            // Config file first, keychain second. The Android client compiles
            // the same pair into BuildConfig from local.properties; this reads
            // the same key names from zillit.properties so one file serves both.
            val cryptoEngine = AesCbcCryptoEngine(
                config.headerKey?.let { material ->
                    ZillitLog.i("Startup") { "Header key: from zillit.properties" }
                    FallbackCryptoKeyProvider(
                        primary = ConfiguredCryptoKeyProvider(material.key, material.iv),
                        fallback = keyProvider,
                    )
                } ?: keyProvider,
            )

            val headerProvider = ZillitHeaderProvider(
                // `core:network` sees only the two operations it needs; it
                // must not be able to reach the keychain behind them.
                crypto = object : HeaderCrypto {
                    override fun encryptToHex(plaintext: String) =
                        cryptoEngine.encryptToHex(plaintext)

                    override fun bodyHash(bodyJson: String, encryptedModuleData: String) =
                        cryptoEngine.bodyHash(bodyJson, encryptedModuleData)
                },
                context = { headerContext.value },
                deviceDescription = currentDeviceDescription(),
                nowMillis = System::currentTimeMillis,
                // IANA zone name, e.g. `Europe/London` — the server uses it to
                // render dates in the user's local time.
                timeZoneId = { java.util.TimeZone.getDefault().id },
            )

            // Its own client, so an S3 upload does not inherit the API's
            // encrypted-header pipeline — S3 signs its own requests.
            val storageClient = HttpClientFactory.create(
                engineFactory = OkHttpEngineProvider(),
                verboseLogging = false,
            )

            // Replays nothing and drops rather than suspends: when a session
            // collapses every in-flight call 401s at once, and the tenth is not
            // more informative than the first.
            val sessionExpired = MutableSharedFlow<Unit>(
                extraBufferCapacity = 1,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )

            // Realtime. Connected on project selection, not at startup: the
            // handshake carries the device id, which does not exist until the
            // device is linked.
            val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val socketClient = SocketIoClient(scope = appScope)

            // The encrypted local cache — opened before the REST client so every
            // read can be kept for offline viewing.
            val database = openLocalDatabase(secureStore)
            val readCache = database?.let { SqlReadCache(it) }
            readCache?.let { cache -> appScope.launch { cache.prune(System.currentTimeMillis()) } }

            // Fed by every REST call below and by the socket coming up; probes
            // the API host while offline so an idle app still notices the
            // network returning.
            val connectivity = ConnectivityMonitor(
                scope = appScope,
                socketConnected = socketClient.connectionState.map { it.isConnected },
                probe = { storageClient.reaches(config.baseUrl(ZillitService.Core)) },
            ).also { it.start() }

            val apiClient = ApiClient(
                httpClient = HttpClientFactory.create(
                    engineFactory = OkHttpEngineProvider(),
                    verboseLogging = !config.environment.isProduction,
                    // `-Pzillit.http=body`. Gated on the environment as well as
                    // the flag, so setting it against production does nothing —
                    // a debug switch that can be turned on in prod by whoever
                    // reads about it is not a debug switch.
                    logBodies = logBodiesRequested() && !config.environment.isProduction,
                ),
                headerProvider = headerProvider,
                onUnauthorized = { sessionExpired.tryEmit(Unit) },
                onOutcome = connectivity::report,
                // Every production screen's reads are kept, keyed by who and
                // which production, and shown again when the server cannot be
                // reached — see ApiClient.remembered.
                readCache = readCache,
                readScope = {
                    val context = headerContext.value
                    ReadScope(userId = context.userId.orEmpty(), projectId = context.projectId.orEmpty())
                },
                nowMillis = System::currentTimeMillis,
            )

            // Before the repositories that reference it in their callbacks.
            val remoteConfigRepository = RemoteConfigRepositoryImpl(
                apiClient = apiClient,
                config = config,
                decrypt = cryptoEngine::decryptFromHex,
            )

            val socketEvents = SocketEventBus(socketClient)

            appScope.reportSocketRejections(socketClient, sessionExpired)

            val unitRepository = UnitRepositoryImpl(apiClient, config)

            val badgeStore = BadgeStore(BadgeSourceImpl(apiClient, config))
            val badgeDrilldown = com.zillit.desktop.feature.home.data.BadgeDrilldownImpl(apiClient, config)

            // The finance repositories, built here because the offline
            // handlers below send through them.
            val purchaseOrderRepository = PurchaseOrderRepositoryImpl(apiClient, config)
            // Timecards are served by the payroll host even though the tool
            // is its own surface — see TimecardRepositoryImpl.
            val timecardRepository = TimecardRepositoryImpl(apiClient, config)

            val syncDatabase = openSyncDatabase(secureStore)
            // What can be sent later: an operation of a kind nobody here
            // handles parks with a reason rather than running. Chat's handler
            // joins below, once its repository exists.
            val syncHandlers = SyncHandlerRegistry(
                listOf(
                    PoSyncHandler(purchaseOrderRepository),
                    TimecardSaveHandler(timecardRepository),
                    TimecardSubmitHandler(timecardRepository),
                ),
            )
            val syncEngine = syncDatabase?.let { durable ->
                SyncEngine(
                    store = SqlOutboxStore(durable),
                    handlers = syncHandlers,
                    online = connectivity.online,
                    currentScope = { headerContext.value.syncScope() },
                    scope = appScope,
                    nowMillis = System::currentTimeMillis,
                    newId = { java.util.UUID.randomUUID().toString() },
                ).also { engine ->
                    engine.start()
                    // A production switch changes whose operations may run.
                    headerContext.onEach { engine.wake() }.launchIn(appScope)
                    // The socket coming up is when queued messages can go —
                    // the REST side may have been "online" all along.
                    socketClient.connectionState.map { it.isConnected }.filter { it }
                        .onEach { engine.wake() }.launchIn(appScope)
                }
            }
            val draftStore = syncDatabase?.let { SqlDraftStore(it) }
            val offlineSupport = if (syncEngine != null && draftStore != null) {
                OfflineSupport(syncEngine, draftStore, connectivity.online) { headerContext.value.syncScope() }
            } else {
                null
            }

            val projectCache = database?.let {
                ProjectCache(it, nowMillis = System::currentTimeMillis)
            }
            // Device-scoped, unlike the project cache: survives a switch,
            // cleared on sign-out below.
            val screenplayCache = database?.let(::ScreenplayCache)
            val projectListCache = database?.let {
                ProjectListCache(it, nowMillis = System::currentTimeMillis)
            }

            // Mail shares the database with the project cache but is its own
            // store: written continuously as folders sync rather than once when
            // a production opens.
            // DMs keep their at-rest copy beside mail — cipher bodies inside
            // the already-encrypted database.
            val chatCache = database?.let(::ChatCache)
            val emailCache = database?.let {
                EmailCache(it, nowMillis = System::currentTimeMillis)
            }

            // Labels share the database but not its lifetime: they belong to a
            // language, so nothing here is cleared on project switch or
            // sign-out. See LabelCache.sq.
            val labelStore = LabelStore(
                source = PresetLabelSource(apiClient, config),
                cache = database?.let { LabelCache(it, nowMillis = System::currentTimeMillis) },
            )

            // Before the first repository that can map a DTO: an installed
            // reader that arrives late is a screen of raw keys that never
            // repaints. The dictionary itself is still empty at this point —
            // the refresh below fills it — but the lookup path is live, so
            // whatever renders first degrades to humanised keys rather than
            // reading through a null.
            Labels.install(labelStore.dictionary)

            val uiLanguage = appScope.trackUiLanguage(preferences)

            // Driven here rather than from a Compose effect: this is
            // app-lifetime work, and starting it at composition raced the
            // keychain restore that supplies the device id — see
            // `labelRefreshTrigger` for what that cost.
            appScope.launch {
                labelRefreshTrigger(
                    language = uiLanguage,
                    deviceId = headerContext.map { it.deviceId },
                ).collect { language -> labelStore.refresh(language) }
            }

            // Unconditional: the loader is the app's only source of profile and
            // crew, and it must survive the database not opening (see the
            // cache parameter's own doc). The cache being null costs warmth,
            // not people.
            val projectContext =
                ProjectContextLoader(apiClient = apiClient, config = config, cache = projectCache)

            val authRepository = AuthRepositoryImpl(
                apiClient = apiClient,
                secureStore = secureStore,
                config = config,
                onSignOut = {
                    // Sign-out clears user- and project-scoped preferences but
                    // leaves device settings (theme, window geometry) alone —
                    // those belong to the machine, not the account.
                    preferences.clear(PreferenceScope.User)
                    preferences.clear(PreferenceScope.Project)
                    headerContext.value = HeaderContext(deviceId = "")
                    keyProvider.invalidate()
                    remoteConfigRepository.clear()
                    badgeStore.clear()
                    projectListCache?.clear()
                    // …and every cached row of theirs — boards, threads, mail,
                    // the read cache, the outbox — so the next person to sign
                    // in here starts clean (Android wipes Realm on logout).
                    LocalCacheWiper(database, syncDatabase).wipe()
                    socketClient.disconnect()
                },
                onDeviceIdentified = { identity ->
                    headerContext.update { it.copy(deviceId = identity.deviceId) }
                },
            )

            // Personal productions have no mail, so nobody approved onto one
            // gets a mailbox. Read at decision time, not now: this graph
            // outlives a production switch.
            val approvalsRepository = ApprovalsRepositoryImpl(
                apiClient = apiClient,
                config = config,
                createsMailboxes = {
                    projectContext.context.value.project?.type
                        ?.equals(PERSONAL_PRODUCTION, ignoreCase = true) != true
                },
            )

            // Three of the administration routes carry the production id in
            // their body rather than the header, so the repository reads it at
            // call time — this graph outlives a production switch.
            val adminRepository = AdminRepositoryImpl(
                apiClient = apiClient,
                config = config,
                projectId = { projectContext.context.value.project?.projectId },
            )

            // Rights are per-production and per-person, so the admin flag is
            // read at call time from whichever production is open.
            val activeProject = MutableStateFlow<com.zillit.desktop.feature.auth.domain.Project?>(null)

            // Needs the production, so it waits for activeProject rather than
            // sitting with the other socket wiring above.
            appScope.announceSelfOnSocketConnect(socketClient, activeProject)
            val toolsRepository = ToolsRepositoryImpl(
                apiClient = apiClient,
                config = config,
                isAdmin = { activeProject.value?.isAdmin == true },
            )

            val projectRepository = ProjectRepositoryImpl(
                apiClient = apiClient,
                config = config,
                updateSession = authRepository::updateSession,
                onProjectChanged = { project, _ ->
                    onProjectOpened(
                        project = project,
                        preferences = preferences,
                        headerContext = headerContext,
                        activeProject = activeProject,
                        remoteConfig = remoteConfigRepository,
                        badges = badgeStore,
                        socket = socketClient,
                        socketUrl = config.baseUrl(ZillitService.Chat),
                        headerProvider = headerProvider,
                        projectContext = projectContext,
                        scope = appScope,
                    )
                },
            )

            // Notice bodies are AES-encrypted with the header key, in both
            // directions.
            val noticeDecryptor = object : NoticeDecryptor {
                override fun decryptFromHex(cipherHex: String) =
                    cryptoEngine.decryptFromHex(cipherHex)

                override fun encryptToHex(plaintext: String) =
                    cryptoEngine.encryptToHex(plaintext)
            }
            val homeFeedRepository = HomeFeedRepositoryImpl(
                apiClient = apiClient,
                config = config,
                decrypt = noticeDecryptor,
                isAdmin = { activeProject.value?.isAdmin == true },
                nowMillis = System::currentTimeMillis,
            )

            // Direct messages: history over REST on the chat host, live
            // traffic on the same socket, bodies AES-encrypted like notices.
            // The chat header's presence feed. The RTDB address follows the
            // Firebase project the same way both web configs do
            // (`<project>-default-rtdb.firebaseio.com`), so the existing pair
            // in zillit.properties is all the configuration it needs. Rides
            // the plain client: Google must never see the Zillit headers.
            val chatPresence = config.firebase?.let { fb ->
                com.zillit.desktop.feature.chat.data.DevicePresenceSource(
                    httpClient = storageClient,
                    databaseUrl = "https://${fb.projectId}-default-rtdb.firebaseio.com",
                )
            }

            val chatRepository = ChatRepositoryImpl(
                apiClient = apiClient,
                config = config,
                bus = socketEvents,
                myUserId = { projectContext?.context?.value?.profile?.userId },
                projectId = { activeProject.value?.id },
                encrypt = { plain -> (cryptoEngine.encryptToHex(plain) as? ZillitResult.Success)?.data },
                decrypt = { cipher -> (cryptoEngine.decryptFromHex(cipher) as? ZillitResult.Success)?.data },
                disk = chatCache,
            )
            // Messages written offline leave through the same send as live ones.
            syncHandlers.register(ChatSendHandler(chatRepository))

            // The notification list — the phones' bell page. Same cipher as
            // the boards: a row's body arrives encrypted like a notice's.
            val notificationsRepository = NotificationsRepositoryImpl(
                apiClient = apiClient,
                config = config,
                decoder = NotificationDecoder(
                    decrypt = { cipher -> (cryptoEngine.decryptFromHex(cipher) as? ZillitResult.Success)?.data },
                ),
            )

            val callEngine = buildCallEngine(config, appScope)
            // One instance, shared: the coordinator and the call-log list are
            // the same surface talking to the same production.
            val callApi = CallApi(apiClient, config)
            val callCoordinator = buildCallCoordinator(
                callEngine, callApi, config, socketEvents, appScope,
                // Firestore rides the plain client: it is not the Zillit API,
                // so the moduledata/bodyhash headers must never ride along.
                planeClient = storageClient,
                selfUserId = { projectContext?.context?.value?.profile?.userId },
                selfDeviceId = { headerContext.value.deviceId.takeIf(String::isNotBlank) },
                selfName = { projectContext?.context?.value?.profile?.fullName },
            )

            com.zillit.desktop.feature.calls.data.CallRinger(callCoordinator, appScope)

            // Home's socket traffic, decoded into events the board understands.
            val homeRealtime = HomeRealtimeSource(
                events = socketEvents,
                decryptBody = { hex ->
                    when (val result = cryptoEngine.decryptFromHex(hex)) {
                        is ZillitResult.Success -> result.data
                        is ZillitResult.Failure -> "[This message could not be decrypted]"
                    }
                },
                myUserId = { projectContext.context.value.profile?.userId },
            )

            val calendarRepository = CalendarRepositoryImpl(apiClient, config)
            val emailRepository = EmailRepositoryImpl(apiClient, config)

            val presetRepository = PresetRepositoryImpl(
                apiClient = apiClient,
                config = config,
                // The same language the label dictionaries were fetched in —
                // a production-type list localised differently from the labels
                // around it is worse than either alone.
                languageCode = { uiLanguage.value },
            )

            val qrLoginRepository = QrLoginRepositoryImpl(
                apiClient = apiClient,
                config = config,
                deviceInfo = currentDeviceInfo(),
                // Cryptographic randomness — the code is a credential; see
                // `secureLoginCode`.
                randomCode = ::secureLoginCode,
                nowMillis = System::currentTimeMillis,
                newDeviceId = { generateDeviceId() },
                onDeviceIdGenerated = { id ->
                    // Must land before the QR call goes out — the `moduledata`
                    // header on that call carries it.
                    headerContext.update { it.copy(deviceId = id) }
                },
                onScannerIdentified = { id ->
                    // `link-scanned` sends this in its header, not its body.
                    headerContext.update { it.copy(scannerDeviceId = id) }
                },
                onLinked = { identity ->
                    // Without this a QR-linked machine is asked to scan again on
                    // every launch — the id exists but is never written down.
                    authRepository.rememberDevice(identity)
                },
            )

            val apiKeySetup = ApiKeySetup(secureStore)

            // The setup screen is skipped entirely when the config file carries
            // the key — asking the user to type a value the app already has
            // would be a dead end they cannot resolve.
            @Suppress("ForbiddenMethodCall")
            val hasApiKeys = config.headerKey != null || runBlocking { apiKeySetup.isConfigured() }

            return Ready(
                sessionExpired = sessionExpired.asSharedFlow(),
                config = config,
                preferences = preferences,
                secureStore = secureStore,
                authRepository = authRepository,
                projectRepository = projectRepository,
                qrLoginRepository = qrLoginRepository,
                presetRepository = presetRepository,
                labelStore = labelStore,
                toolsRepository = toolsRepository,
                homeFeedRepository = homeFeedRepository,
                notificationsRepository = notificationsRepository,
                noticeDecryptor = noticeDecryptor,
                chatRepository = chatRepository,
                chatPresence = chatPresence,
                homeRealtime = homeRealtime,
                emailRealtime = EmailRealtimeSource(socketEvents),
                calendarRepository = calendarRepository,
                emailRepository = emailRepository,
                draftRepository = DraftRepositoryImpl(apiClient, config),
                contactRepository = ContactRepositoryImpl(apiClient, config),
                signatureRepository = SignatureRepositoryImpl(apiClient, config),
                folderRepository = FolderRepositoryImpl(apiClient, config),
                attachmentUploader = uploader(storageClient, apiClient, config, remoteConfigRepository, projectContext),
                noticeMedia = S3NoticeMediaSource(
                    httpClient = storageClient,
                    credentials = { awsKeyPair(remoteConfigRepository) },
                ),
                // The production's storage region and bucket — what profile
                // pictures (stored as bare keys) are fetched against.
                storageTarget = SuitableRegionSource(apiClient, config),

                projectContext = projectContext,
                projectCache = projectCache,
                projectListCache = projectListCache,
                screenplayCache = screenplayCache,
                emailCache = emailCache,
                unitRepository = unitRepository,
                approvalsRepository = approvalsRepository,
                adminRepository = adminRepository,
                // Marks its own row in the linked-devices list, so nobody signs
                // themselves out looking for a phone they lost.
                accountRepository = AccountRepositoryImpl(
                    apiClient = apiClient,
                    config = config,
                    thisDeviceId = { headerContext.value.deviceId.takeIf { it.isNotBlank() } },
                ),
                // Each on its own service host, both reached through the same
                // signed client — see ZillitService.
                cashRepository = CashRepositoryImpl(apiClient, config),
                cardRepository = CardRepositoryImpl(apiClient, config),
                purchaseOrderRepository = purchaseOrderRepository,
                timecardRepository = timecardRepository,
                payrollRepository = PayrollRepositoryImpl(apiClient, config),
                dealMemoRepository = DealMemoRepositoryImpl(apiClient, config),
                // Each on its own service host. Document Distribution also
                // reaches the *email* service for open status — the pixel log
                // lives with whoever sent the copy, not with doc-dist.
                // Three hosts behind one repository: the account-hub service,
                // `/vendors` (which is also the hub, despite the path), and the
                // core service for the shared currency and tax catalogues.
                accountHubRepository = AccountHubRepositoryImpl(apiClient, config),
                docDistRepository = DocDistRepositoryImpl(apiClient, config),
                driveRepository = DriveRepositoryImpl(
                    apiClient = apiClient,
                    config = config,
                    // The drive's activity log names its actor by id and
                    // nothing else. The crew list the session already holds is
                    // the only thing that can turn that into a person — the web
                    // resolves it the same way.
                    resolveUserName = { userId ->
                        projectContext.context.value.user(userId)?.fullName
                    },
                ),
                httpClient = storageClient,
                badgeStore = badgeStore,
                badgeDrilldown = badgeDrilldown,
                apiClient = apiClient,
                headerProvider = headerProvider,
                socketEvents = socketEvents,
                callCoordinator = callCoordinator,
                callApi = callApi,
                callEngine = callEngine,
                remoteConfigRepository = remoteConfigRepository,
                apiKeySetup = apiKeySetup,
                hasApiKeys = hasApiKeys,
                connectivity = connectivity,
                syncEngine = syncEngine,
                draftStore = draftStore,
                offlineSupport = offlineSupport,
            )
        }
    }
}

/**
 * Opens the encrypted local cache, or returns null.
 *
 * The database key lives in the OS keychain and a machine that has never signed
 * in has none, so failing to open is ordinary rather than exceptional — it must
 * not stop the app reaching the sign-in screen. Callers degrade to online-only.
 */
@Suppress("ForbiddenMethodCall")
private fun openLocalDatabase(secureStore: SecureStore): ZillitDatabase? = runBlocking {
    when (val opened = ZillitDatabaseFactory(keyManager = DatabaseKeyManager(secureStore)).open()) {
        is ZillitResult.Success -> opened.data
        is ZillitResult.Failure -> {
            ZillitLog.w("Startup") {
                "local cache unavailable, running online-only: ${opened.error.technical}"
            }
            null
        }
    }
}

/**
 * Opens the durable store — outbox and drafts — or returns null.
 *
 * Same footing as the cache: no key on a never-signed-in machine is ordinary.
 * Unlike the cache, a file this build cannot migrate is left alone and the
 * app runs without offline support until a build that can read it.
 */
@Suppress("ForbiddenMethodCall")
private fun openSyncDatabase(secureStore: SecureStore): SyncDatabase? = runBlocking {
    when (val opened = SyncDatabaseFactory(keyManager = DatabaseKeyManager(secureStore)).open()) {
        is ZillitResult.Success -> opened.data
        is ZillitResult.Failure -> {
            ZillitLog.w("Startup") {
                "durable store unavailable, offline changes disabled: ${opened.error.technical}"
            }
            null
        }
    }
}

/** Whose queued work may run: the signed-in person, in the open production. */
private fun HeaderContext.syncScope(): SyncScope? {
    val user = userId?.takeIf { it.isNotBlank() } ?: return null
    val project = projectId?.takeIf { it.isNotBlank() } ?: return null
    return SyncScope(userId = user, projectId = project)
}

/**
 * Whether [url]'s host answers at all. Any HTTP status is a yes — a 404 from
 * the right server is proof of a network; only a transport failure is a no.
 */
private suspend fun io.ktor.client.HttpClient.reaches(url: String): Boolean =
    runCatching { head(url); true }.getOrDefault(false)

/**
 * Attachment uploads, straight to S3.
 *
 * The credentials come from the `configuration` endpoint — long-lived AWS keys
 * handed to every client, which is the existing architecture rather than a good
 * one. See `AwsV4Signer`.
 */
private fun uploader(
    storageClient: io.ktor.client.HttpClient,
    apiClient: ApiClient,
    config: AppConfig,
    remoteConfig: RemoteConfigRepository,
    projectContext: ProjectContextLoader?,
): AttachmentUploader {
    val project = { projectContext?.context?.value?.project }

    return RoutingAttachmentUploader(
        // Read per upload, not captured: which storage a production uses is not
        // known until it is open, and it changes when the user switches.
        kind = { storageKindOf(project()?.storageType) },
        aws = S3AttachmentUploader(
            httpClient = storageClient,
            credentials = {
                awsKeyPair(remoteConfig)?.let { (access, secret) -> AwsCredentials(access, secret) }
            },
            storage = SuitableRegionSource(apiClient, config),
        ),
        box = BoxAttachmentUploader(
            httpClient = storageClient,
            settings = {
                project()?.enterpriseClientId?.let { enterprise ->
                    BoxSettings(
                        enterpriseClientId = enterprise,
                        // The production's own folder when the server named
                        // one; Box's root otherwise. See `BoxSettings`.
                        folderId = project()?.storageFolders?.get(EMAIL_BOX_FOLDER)
                            ?: BOX_ROOT_FOLDER,
                    )
                }
            },
            tokens = BoxAuthSource(apiClient, config),
        ),
    )
}

/**
 * Which of the production's Box folders attachments go in.
 *
 * `chat`, because it is the closest thing the server's folder list has to
 * outgoing messages and the web names none at all for email — it sends an empty
 * parent id, which Box does not document as valid. Falls back to the root.
 */
private const val EMAIL_BOX_FOLDER = "chat"

/**
 * Calling: signalling is complete; media sits behind [NoopCallEngine] until
 * the embedded-Chromium Agora host lands. Identity is resolved per read
 * because a call can outlive a project switch and ring before the profile
 * hydrates.
 */
@Suppress("LongParameterList")
private fun buildCallCoordinator(
    engine: com.zillit.desktop.feature.calls.domain.CallEngine,
    callApi: CallApi,
    config: AppConfig,
    socketEvents: SocketEventBus,
    appScope: kotlinx.coroutines.CoroutineScope,
    planeClient: io.ktor.client.HttpClient,
    selfUserId: () -> String?,
    selfDeviceId: () -> String?,
    selfName: () -> String?,
): CallCoordinator = CallCoordinator(
    api = callApi,
    bus = socketEvents,
    engine = engine,
    scope = appScope,
    selfUserId = selfUserId,
    selfDeviceId = selfDeviceId,
    plane = buildStatusPlane(config, planeClient, selfDeviceId),
    selfName = selfName,
).also { it.start() }

/**
 * The media stack: embedded Chromium running the Agora Web SDK when the file
 * carries an app id; the no-op engine (signalling-only calling) otherwise.
 */
private fun buildCallEngine(
    config: AppConfig,
    appScope: kotlinx.coroutines.CoroutineScope,
): com.zillit.desktop.feature.calls.domain.CallEngine {
    val appId = config.agoraAppId
    if (appId == null) {
        ZillitLog.i("Calls") { "media engine off — no AGORA_APP_ID for this env" }
        return NoopCallEngine()
    }
    ZillitLog.i("Calls") { "media engine on (KCEF + Agora Web SDK)" }
    // Handed to the shutdown path so its parking window cannot outlive the app.
    return KcefCallEngine(appId, appScope).also(Shutdown::engine)
}

/** The Firestore mirror when the file names a project; socket-only otherwise. */
private fun buildStatusPlane(
    config: AppConfig,
    httpClient: io.ktor.client.HttpClient,
    selfDeviceId: () -> String?,
): CallStatusPlane {
    val firebase = config.firebase
    if (firebase == null) {
        // Named loudly: a silent Noop looks identical to a broken mirror,
        // and "why doesn't my phone stop ringing" starts at this line.
        ZillitLog.i("Calls") { "Firestore status plane off — no FIREBASE keys for this env" }
        return NoopCallStatusPlane()
    }
    ZillitLog.i("Calls") { "Firestore status plane on (project=${firebase.projectId})" }
    return FirestoreCallStatusPlane(
        httpClient = httpClient,
        projectId = firebase.projectId,
        apiKey = firebase.apiKey,
        selfDeviceId = selfDeviceId,
    )
}

/**
 * The departments and units the approval review form offers.
 *
 * Two services, two calls, and either can fail on its own — a units outage must
 * not empty the department picker, because department is the field an admin
 * actually comes here to fix. Fails only when neither answered; the form then
 * says so and still approves what the person asked for.
 *
 * Lives here rather than in `feature:settings` because it spans two other
 * modules' repositories, and the graph is where modules are allowed to meet.
 */
internal suspend fun AppGraph.Ready.crewPresets(): ZillitResult<CrewPresets> {
    val projectId = projectContext?.context?.value?.project?.projectId
        ?: return ZillitResult.Failure(ZillitError.Validation("No production is open."))

    val departments = projectRepository.departments(projectId)
    val units = unitRepository.joinUnits(projectId)

    if (departments is ZillitResult.Failure && units is ZillitResult.Failure) {
        return ZillitResult.Failure(departments.error)
    }

    return ZillitResult.Success(
        CrewPresets(
            departments = departments.getOrNull().orEmpty().map { department ->
                CrewDepartment(
                    id = department.id,
                    name = department.name,
                    // The server nests them under `designations`; this module's
                    // word for the same thing is "role".
                    roles = department.designations.map { CrewRole(it.id, it.name) },
                )
            },
            units = units.getOrNull().orEmpty(),
        ),
    )
}

/** `project_type` for a personal production, which runs no mail. */
private const val PERSONAL_PRODUCTION = "personal"
