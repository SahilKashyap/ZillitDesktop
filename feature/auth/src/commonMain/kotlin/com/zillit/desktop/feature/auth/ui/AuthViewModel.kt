package com.zillit.desktop.feature.auth.ui

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitLog
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.mvvm.ZillitViewModel
import com.zillit.desktop.feature.auth.domain.AuthRepository
import com.zillit.desktop.feature.auth.domain.DeviceStatus
import com.zillit.desktop.feature.auth.domain.Project
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.auth.domain.ProjectFilter
import com.zillit.desktop.feature.auth.domain.ProjectListStore
import com.zillit.desktop.feature.auth.domain.ProjectRepository
import com.zillit.desktop.feature.auth.domain.filterProjects
import com.zillit.desktop.feature.auth.domain.QrLoginRepository
import com.zillit.desktop.feature.auth.domain.QrLoginSession
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay

/**
 * Where the user is in the sign-in flow.
 *
 * A sealed step rather than a pile of booleans (`isLoading`, `showOtp`,
 * `hasProjects`), because those admit impossible combinations — the Android
 * registration flow spreads this across four Activities and a bundle of extras,
 * so "which screen am I on" is only answerable by tracing intents.
 */
sealed interface AuthStep {

    /** Collecting an email address. */
    data object Email : AuthStep

    /** Waiting for the code sent to [email]. */
    data class Otp(val email: String) : AuthStep

    /** Verified; choosing which production to open. */
    data object ProjectSelection : AuthStep

    /**
     * Showing a QR for an already signed-in phone or tablet to scan.
     *
     * The desktop displays; the phone scans. That matches the Android flow
     * (`StartProjectVM` displays, `AdminVM` scans) and suits desktop, where the
     * webcam faces the user rather than the page.
     *
     * This is the **landing** step, as on the web: `/device/login` shows the QR
     * immediately rather than asking for an email first.
     *
     * @param session null while the code is being fetched — the page frame is
     *   drawn straightaway so the instructions are readable during the round
     *   trip, rather than the whole screen being a spinner.
     * @param stale the poll window elapsed with no scan. The web keeps the QR
     *   on screen and overlays a reload control rather than discarding it, so a
     *   user who looked away has something to click instead of a dead end.
     */
    data class QrLogin(
        val session: QrLoginSession? = null,
        val stale: Boolean = false,
    ) : AuthStep

    /** Recovering a device the user has lost access to. */
    data object Recovery : AuthStep

    /** Signed in with a project chosen — the shell takes over. */
    data object Complete : AuthStep
}

/**
 * Whether this device has already proved who it is.
 *
 * The distinction matters for 401s. Signing in produces them routinely — a
 * wrong code, an unregistered address, a device the server has never seen — and
 * treating those as an expired session would throw away the email the user was
 * halfway through typing. Past this line there is nothing left to prove, so a
 * rejection can only mean the session is gone.
 */
val AuthStep.isEstablished: Boolean
    get() = this == AuthStep.Complete || this == AuthStep.ProjectSelection

data class AuthUiState(
    val step: AuthStep = AuthStep.QrLogin(),
    val email: String = "",
    val otp: String = "",
    val recoveryCode: String = "",
    val projectFilter: String = "",
    val projectCategory: ProjectFilter = ProjectFilter.All,
    val projects: List<Project> = emptyList(),
    /** Unread per project id — the listing cards' badges. */
    val projectUnread: Map<String, Int> = emptyMap(),
    /**
     * The production the user actually opened.
     *
     * Held explicitly rather than inferred from [projects]: the shell shows this
     * name in its top bar, and reading `projects.first()` there displays
     * whichever production happens to sort first — which is right exactly once,
     * by accident.
     */
    val activeProject: Project? = null,
    val isBusy: Boolean = false,
    val error: String? = null,
    /**
     * The list on screen is the last one saved on this device, because the
     * server could not be reached. Cleared the moment a refresh lands.
     */
    val isShowingSavedProjects: Boolean = false,
    val isCreatingProduction: Boolean = false,
    /** The join dialog is open; it owns its own state. */
    val isJoining: Boolean = false,
    /** Set after a code is sent, so the UI can say where it went. */
    val otpSentTo: String? = null,
) {
    val canSubmitEmail: Boolean get() = email.isNotBlank() && !isBusy
    val canSubmitOtp: Boolean get() = otp.length >= MIN_OTP_LENGTH && !isBusy

    /** Search + category + ordering, all in [filterProjects] so it is testable. */
    val visibleProjects: List<Project>
        get() = projects.filterProjects(projectFilter, projectCategory) { projectUnread[it.id] ?: 0 }

    companion object {
        const val MIN_OTP_LENGTH = 4
    }
}

sealed interface AuthEvent {
    data object StartQrLogin : AuthEvent
    data object RefreshQrCode : AuthEvent
    data class EmailChanged(val value: String) : AuthEvent
    data class OtpChanged(val value: String) : AuthEvent
    data class RecoveryCodeChanged(val value: String) : AuthEvent
    data class ProjectFilterChanged(val value: String) : AuthEvent
    data class SelectProject(val project: Project) : AuthEvent
    data class ProjectCategoryChanged(val value: ProjectFilter) : AuthEvent
    data class ToggleFavourite(val project: Project) : AuthEvent

    /** The "Start a project" button. */
    data object StartNewProject : AuthEvent
    data object DismissCreateProduction : AuthEvent

    /** A production was created; the list needs to include it. */
    data object ReloadProjects : AuthEvent

    /** Leave the open production and return to the picker. */
    data object SwitchProject : AuthEvent

    /**
     * The server stopped accepting this device's credentials.
     *
     * Raised by the shell from any 401, not by a particular screen: the call
     * that discovers a dead session is whichever happened to fire next, and it
     * is usually one nobody was watching.
     */
    data object SessionExpired : AuthEvent
    data object SubmitEmail : AuthEvent
    data object SubmitOtp : AuthEvent
    data object ResendOtp : AuthEvent
    data object SubmitRecovery : AuthEvent
    data object StartRecovery : AuthEvent
    data object Back : AuthEvent
    data object DismissError : AuthEvent

    /** The "Visit Corporate Web Site" link. */
    data object OpenCorporateSite : AuthEvent

    /** Opens the join dialog. */
    data object StartJoin : AuthEvent
    data object DismissJoin : AuthEvent
}

sealed interface AuthEffect {
    /** Sign-in finished; the shell should take over. */
    data object Authenticated : AuthEffect
    data class Message(val text: String) : AuthEffect

    /**
     * Open [url] in the host browser.
     *
     * An effect rather than a direct call: the URL is a fixed constant here, and
     * routing it through the app module keeps "this feature can launch a
     * browser" from becoming a general capability of every feature module.
     */
    data class OpenUrl(val url: String) : AuthEffect
}

/**
 * Drives device sign-in and project selection (plan M3).
 *
 * Zillit has no password: a device proves itself by verifying an emailed code,
 * and the device identity carries authority from then on. So this is
 * "register or recover a device", not "log in".
 */
class AuthViewModel(
    private val authRepository: AuthRepository,
    private val projectRepository: ProjectRepository,
    private val qrLoginRepository: QrLoginRepository? = null,
    private val nowMillis: () -> Long = { 0L },
    /**
     * Unread per project, from the notification service's device scope
     * (`GET device/unread`) — the one badge question that can be asked
     * before a production is open. Hosts wire it; empty keeps cards bare.
     */
    private val projectUnread: suspend () -> Map<String, Int> = { emptyMap() },
    /** The last list this device was given; null when there is no local cache. */
    private val projectListStore: ProjectListStore? = null,
    /** Whether the API can be reached right now. Hosts wire the connectivity monitor; true keeps every open live. */
    private val isOnline: () -> Boolean = { true },
    /** Whether a production was visited before on this computer, so it can open from what was saved. */
    private val hasOfflineData: (projectId: String) -> Boolean = { true },
) : ZillitViewModel<AuthUiState, AuthEvent, AuthEffect>(AuthUiState()) {

    /**
     * The poll/expiry loop for the visible QR session.
     *
     * Held so it can be cancelled: leaving it running after the user navigates
     * away would keep asking the server about a code nobody is looking at.
     */
    private var qrJob: Job? = null

    /** True while a rejection is being checked, so a stampede asks once. */
    private var isConfirmingDevice = false

    init {
        resumeOrSignIn()
        // Signing out anywhere — Settings, the rail's Logout — clears the
        // repository's session; this is the one place that turns that into a
        // screen change. Watched rather than wired from each button so a new
        // sign-out entry point cannot forget to leave the shell. Only a
        // session that was established counts: the QR screen has none yet.
        launch {
            // A session that *was* and now is not — the flow's opening null,
            // before any device is restored, is not a sign-out.
            var hadSession = false
            authRepository.session.collect { session ->
                if (session != null) {
                    hadSession = true
                } else if (hadSession && currentState.step.isEstablished) {
                    // Nothing of the last person left on the screen: state
                    // reset, production left, a fresh QR — no "expired" notice,
                    // they chose this. (Android wipes SharedPref and Realm on
                    // logout — `LocalDataEraser.kt` — and re-enters from the start.)
                    signOutToQrLogin(notice = null)
                }
            }
        }
    }

    /**
     * Skips the QR when this machine is already linked.
     *
     * The device id is written to the keychain at link time. Asking for a scan
     * on every launch while holding it is a papercut the web does not have —
     * there, a browser session persists.
     *
     * A stored id is not proof the server still honours it, so this goes
     * straight to loading productions: if that call fails, [loadProjects]
     * surfaces the error and the user can fall back to signing in again.
     */
    private fun resumeOrSignIn() {
        launchResult(
            block = { authRepository.restoreDevice() },
            onSuccess = { identity ->
                if (identity == null) {
                    startQrLogin()
                } else {
                    setState { copy(step = AuthStep.ProjectSelection, isBusy = true) }
                    loadProjects()
                }
            },
            // A keychain that will not open is not a reason to be stuck: fall
            // back to the flow that does not need it.
            onError = { startQrLogin() },
        )
    }

    // Exhaustive dispatch over a sealed event set — the branch count is the
    // point of the pattern, not a complexity problem (see WorkspaceViewModel).
    @Suppress("CyclomaticComplexMethod")
    override fun onEvent(event: AuthEvent) {
        when (event) {
            is AuthEvent.EmailChanged -> setState { copy(email = event.value, error = null) }
            is AuthEvent.OtpChanged -> setState { copy(otp = event.value.filter(Char::isDigit), error = null) }
            is AuthEvent.RecoveryCodeChanged -> setState { copy(recoveryCode = event.value, error = null) }
            is AuthEvent.ProjectFilterChanged -> setState { copy(projectFilter = event.value) }
            is AuthEvent.ProjectCategoryChanged -> setState { copy(projectCategory = event.value) }
            is AuthEvent.SelectProject -> selectProject(event.project)
            is AuthEvent.ToggleFavourite -> toggleFavourite(event.project)
            AuthEvent.StartNewProject -> setState { copy(isCreatingProduction = true, error = null) }
            AuthEvent.DismissCreateProduction -> setState { copy(isCreatingProduction = false) }
            AuthEvent.ReloadProjects -> loadProjects()
            AuthEvent.SwitchProject -> switchProject()
            AuthEvent.SessionExpired -> sessionExpired()
            AuthEvent.StartJoin -> setState { copy(isJoining = true, error = null) }
            AuthEvent.DismissJoin -> setState { copy(isJoining = false) }
            AuthEvent.SubmitEmail -> requestOtp()
            AuthEvent.SubmitOtp -> verifyOtp()
            AuthEvent.ResendOtp -> requestOtp(resend = true)
            AuthEvent.StartQrLogin -> startQrLogin()
            AuthEvent.RefreshQrCode -> startQrLogin()
            AuthEvent.StartRecovery -> {
                cancelQrLoop()
                setState { copy(step = AuthStep.Recovery, error = null) }
            }
            AuthEvent.SubmitRecovery -> recover()
            AuthEvent.Back -> goBack()
            AuthEvent.DismissError -> setState { copy(error = null) }
            AuthEvent.OpenCorporateSite -> sendEffect(AuthEffect.OpenUrl(CORPORATE_URL))
        }
    }

    private fun requestOtp(resend: Boolean = false) {
        val email = currentState.email.trim()
        setState { copy(isBusy = true, error = null) }

        launchResult(
            block = { authRepository.requestOtp(email) },
            onSuccess = {
                setState { copy(isBusy = false, step = AuthStep.Otp(email), otpSentTo = email, otp = "") }
                if (resend) sendEffect(AuthEffect.Message("A new code is on its way to $email"))
            },
            onError = { fail(it) },
        )
    }

    /**
     * Verifies the code and immediately registers the device.
     *
     * Two calls behind one user action on purpose: the confirm code returned by
     * `verify` is single-use and short-lived, so surfacing an intermediate
     * "verified, now press register" step would let it expire while the user
     * looks at a button.
     */
    private fun verifyOtp() {
        val email = (currentState.step as? AuthStep.Otp)?.email ?: currentState.email.trim()
        val otp = currentState.otp
        setState { copy(isBusy = true, error = null) }

        launchResult(
            block = { authRepository.verifyOtp(email, otp) },
            onSuccess = { confirmCode -> registerDevice(email, confirmCode) },
            onError = { fail(it) },
        )
    }

    private fun registerDevice(email: String, confirmCode: String) {
        launchResult(
            block = { authRepository.registerDevice(email, confirmCode) },
            onSuccess = { loadProjects() },
            onError = { fail(it) },
        )
    }

    private fun recover() {
        val code = currentState.recoveryCode.trim()
        setState { copy(isBusy = true, error = null) }

        launchResult(
            block = { authRepository.recoverWithCode(code) },
            onSuccess = { loadProjects() },
            onError = { fail(it) },
        )
    }

    /**
     * Offline-first: whatever this device was last given goes on screen at
     * once, the server's answer replaces it, and if the server cannot be
     * reached the saved list stays — with a note saying so — rather than
     * an empty picker telling someone they are on no production.
     */
    private fun loadProjects() {
        val saved = projectListStore?.load().orEmpty()
        if (saved.isNotEmpty() && currentState.projects.isEmpty()) {
            setState { copy(step = AuthStep.ProjectSelection, projects = saved, isShowingSavedProjects = true) }
        }
        launchResult(
            block = { projectRepository.listProjects() },
            onSuccess = { projects ->
                projectListStore?.save(projects)
                setState {
                    copy(
                        isBusy = false,
                        step = AuthStep.ProjectSelection,
                        projects = projects,
                        isShowingSavedProjects = false,
                        error = null,
                    )
                }
                // Which production has news — asked alongside the list, not
                // before it: cards render immediately and the counts join.
                launch {
                    val counts = projectUnread()
                    setState { copy(projectUnread = counts) }
                    // Names beside the numbers, for a comparison with a phone.
                    ZillitLog.d(TAG) {
                        "picker badges: " + currentState.projects
                            .mapNotNull { p -> counts[p.id]?.takeIf { it > 0 }?.let { "${p.name}(${p.id})=$it" } }
                    }
                }
                // A device attached to exactly one production has nothing to
                // choose; making the user pick from a list of one is friction.
                projects.singleOrNull()?.let(::selectProject)
            },
            onError = { error ->
                // A saved list beats an error banner over an empty grid: the
                // productions are still real, only the refresh failed.
                if (currentState.projects.isNotEmpty() && currentState.isShowingSavedProjects) {
                    ZillitLog.w(TAG) { "project list refresh failed, keeping the saved list: ${error.technical}" }
                    setState { copy(isBusy = false) }
                } else {
                    fail(error)
                }
            },
        )
    }

    private fun selectProject(project: Project) {
        // Guarded, because opening fans out to config, tools, badges **and a
        // socket connection**. A second click while the first is in flight
        // opened a second connection — two sockets means every realtime event
        // arrives twice.
        if (currentState.isBusy || currentState.activeProject?.id == project.id) {
            ZillitLog.d(TAG) { "ignoring a repeat open of ${project.id}" }
            return
        }

        // A production still awaiting approval grants no access to anything
        // inside it: the backend answers every project-scoped call with 403
        // `libs_access_denied`, starting with the Home tab strip. The list
        // already makes these rows unclickable, so this guards the ways in that
        // do not go through a click — a restored session, a later caller — and
        // keeps the rule in the layer that knows what pending means rather than
        // only in the one that draws it.
        if (project.isPending) {
            ZillitLog.i(TAG) { "not opening ${project.id}: membership is still pending" }
            setState { copy(isBusy = false, error = PENDING_APPROVAL) }
            return
        }

        // With no network, only a production this computer has seen before can
        // open — everything inside it would be drawn from what was saved. Said
        // at the click, not after a screen full of errors.
        if (!isOnline() && !hasOfflineData(project.id)) {
            ZillitLog.i(TAG) { "offline and nothing saved for ${project.id}; not opening" }
            setState { copy(isBusy = false, error = NO_OFFLINE_DATA) }
            return
        }

        // If none of the downstream calls appear in the log, this line is what
        // tells you whether the click ever arrived.
        ZillitLog.i(TAG) { "opening project ${project.id}" }
        setState { copy(isBusy = true, error = null) }

        launchResult(
            block = { projectRepository.selectProject(project, unit = null) },
            onSuccess = {
                setState { copy(isBusy = false, step = AuthStep.Complete, activeProject = project) }
                sendEffect(AuthEffect.Authenticated)
            },
            onError = { fail(it) },
        )
    }

    /**
     * Returns to the picker, and refreshes it on the way.
     *
     * The list is reloaded rather than reused: the user has been inside a
     * production for a while, and unread counts, pending approvals and newly
     * shared productions will all have moved on.
     */
    private fun switchProject() {
        setState { copy(isBusy = true, error = null, step = AuthStep.ProjectSelection, activeProject = null) }

        launchResult(
            block = { projectRepository.leaveProject() },
            onSuccess = { loadProjects() },
            onError = { fail(it) },
        )
    }

    /**
     * Checks whether a rejection really means the session is over.
     *
     * ## Why this is not simply "sign out"
     *
     * This API has no token to refresh — authority rides on the device
     * registration, and the encrypted header is rebuilt for every call. So the
     * nearest thing to renewing a session is asking whether it still exists,
     * and that question is worth asking: a single 401 can be a server having a
     * bad minute, a race while a production is being switched, or one endpoint
     * misbehaving. Believing the first one costs the user a QR scan and
     * whatever they were in the middle of.
     *
     * Only a *confirmed* revocation signs them out. An unreachable server
     * leaves the session alone — being unable to ask is not an answer, and a
     * unit on location with no signal must not be logged out for it.
     *
     * One check, however many calls failed: when a session dies every in-flight
     * request fails together, and each raises this.
     */
    private fun sessionExpired() {
        if (!currentState.step.isEstablished || isConfirmingDevice) return
        isConfirmingDevice = true

        launch {
            val status = authRepository.confirmDevice()
            isConfirmingDevice = false

            when (status) {
                DeviceStatus.Revoked -> signOutToQrLogin()
                DeviceStatus.Valid -> ZillitLog.i(TAG) {
                    "a call was rejected but the device is still registered; keeping the session"
                }
                DeviceStatus.Unknown -> ZillitLog.i(TAG) {
                    "a call was rejected and the server could not be asked why; keeping the session"
                }
            }
        }
    }

    /**
     * Tears the session down and returns to the QR screen.
     *
     * [ProjectRepository.leaveProject] does the work — badges, cached rights,
     * third-party credentials and the socket all belong to a production, and
     * leaving them behind would let the next sign-in read the previous one's
     * answers. The keychain is deliberately *not* cleared: the device id is
     * already useless if it was revoked, and wiping a user's local cache is not
     * something to do as a side effect of one response.
     */
    private fun signOutToQrLogin(notice: String? = SESSION_EXPIRED_MESSAGE) {
        ZillitLog.i(TAG) {
            if (notice == null) "signed out; returning to sign-in" else "device revoked; returning to sign-in"
        }

        qrJob?.cancel()
        setState { AuthUiState() }

        launch {
            projectRepository.leaveProject()
            startQrLogin(notice = notice)
        }
    }

    /**
     * Opens a QR session and drives it until it is scanned or expires.
     *
     * The backend exposes no socket event for scanning, so this polls. The
     * cadence is a compromise: fast enough that the desktop reacts while the
     * user is still holding their phone up, slow enough not to hammer the
     * endpoint for the whole two-minute lifetime.
     */
    private fun startQrLogin(notice: String? = null) {
        val repository = qrLoginRepository ?: run {
            setState { copy(error = "QR sign-in is not available.") }
            return
        }

        cancelQrLoop()
        // Clears whatever went wrong last time, unless the caller is arriving
        // here *because* something did and wants to say so.
        setState { copy(isBusy = true, error = notice) }

        qrJob = launch {
            when (val started = repository.startSession()) {
                is ZillitResult.Failure -> fail(started.error)
                is ZillitResult.Success -> {
                    val session = started.data
                    setState { copy(isBusy = false, step = AuthStep.QrLogin(session)) }
                    awaitScan(repository, session)
                }
            }
        }
    }

    private suspend fun awaitScan(repository: QrLoginRepository, session: QrLoginSession) {
        while (!session.isExpired(nowMillis())) {
            delay(QR_POLL_INTERVAL_MILLIS)

            // The user may have navigated away between polls.
            if ((currentState.step as? AuthStep.QrLogin)?.session?.id != session.id) return

            when (val polled = repository.pollScanned(session)) {
                // A failed poll is not fatal — a dropped packet must not kill a
                // session the user is mid-way through. Keep polling; the window
                // is the backstop.
                is ZillitResult.Failure -> continue
                // Non-null means a device scanned it, and carries that
                // device's id — which the link call needs in its header.
                is ZillitResult.Success -> polled.data?.let { scannerDeviceId ->
                    completeQrLink(repository, session, scannerDeviceId)
                    return
                }
            }
        }

        // The code stays on screen behind a reload control rather than being
        // cleared. Matching the web: `setShowRetry(true)` after the window,
        // with the QR left in place.
        setState { copy(step = AuthStep.QrLogin(session, stale = true)) }
    }

    private suspend fun completeQrLink(
        repository: QrLoginRepository,
        session: QrLoginSession,
        scannerDeviceId: String,
    ) {
        setState { copy(isBusy = true) }
        when (val linked = repository.completeLink(session, scannerDeviceId)) {
            is ZillitResult.Failure -> fail(linked.error)
            is ZillitResult.Success -> loadProjects()
        }
    }

    /**
     * Stars or unstars a production.
     *
     * The row updates before the request returns and reverts if it fails. A star
     * that waits on a round trip feels broken, and this is the one action on the
     * screen where the correct value is already known locally.
     */
    private fun toggleFavourite(project: Project) {
        val wanted = !project.isFavourite
        setState { copy(projects = projects.withFavourite(project.id, wanted)) }

        launchResult(
            block = { projectRepository.setFavourite(project.id, wanted) },
            onSuccess = { },
            onError = { error ->
                setState {
                    copy(
                        projects = projects.withFavourite(project.id, project.isFavourite),
                        error = error.localised(),
                    )
                }
            },
        )
    }

    private fun cancelQrLoop() {
        qrJob?.cancel()
        qrJob = null
    }

    override fun onCleared() {
        cancelQrLoop()
        super.onCleared()
    }

    private fun goBack() = setState {
        when (step) {
            is AuthStep.Otp -> copy(step = AuthStep.Email, otp = "", error = null)
            is AuthStep.QrLogin -> {
                cancelQrLoop()
                copy(step = AuthStep.Email, error = null)
            }
            AuthStep.Recovery -> copy(step = AuthStep.Email, recoveryCode = "", error = null)
            else -> this
        }
    }

    /**
     * Shows the error's user-facing message, never its technical detail.
     *
     * `ZillitError.userMessage` is written for humans; `technical` carries
     * exception names and server internals that belong only in logs (plan §8.4).
     */
    private fun fail(error: ZillitError) = setState {
        ZillitLog.w(TAG) { "auth step failed: ${error.technical ?: error.localised()}" }
        // A collapsed session fails several calls at once. The first has already
        // explained it in better words than the server's "Unauthorized", so the
        // stragglers must not talk over it.
        val alreadyExplained = error is ZillitError.Unauthorized && this.error != null
        copy(isBusy = false, error = if (alreadyExplained) this.error else error.localised())
    }

    private companion object {
        const val TAG = "Auth"

        /** Says what happened and what to do, without blaming the user. */
        const val SESSION_EXPIRED_MESSAGE = "Your session has ended. Scan the code to sign in again."
        const val NO_OFFLINE_DATA = "No offline data available for this project. Connect to the internet to open it."
        const val PENDING_APPROVAL =
            "This project is still awaiting approval. You can open it once a coordinator accepts your request."
    }
}

/**
 * How often to ask the server whether the code has been scanned.
 *
 * Three seconds, matching the web client's `setInterval(..., 3000)`. Left the
 * same on purpose: the poll rate is visible to the backend as load, and two
 * clients hitting the same endpoint at different rates is a difference someone
 * would eventually have to explain.
 */
private const val QR_POLL_INTERVAL_MILLIS = 3_000L

/** The link in the sign-in page footer, as on the web. */
private const val CORPORATE_URL = "https://corporate.zillit.com"

/** Replaces one production's favourite flag, leaving the rest untouched. */
private fun List<Project>.withFavourite(projectId: String, favourite: Boolean): List<Project> =
    map { if (it.id == projectId) it.copy(isFavourite = favourite) else it }
