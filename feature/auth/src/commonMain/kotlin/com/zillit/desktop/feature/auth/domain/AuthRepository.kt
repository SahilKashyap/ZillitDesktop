package com.zillit.desktop.feature.auth.domain

import com.zillit.desktop.core.common.ZillitResult
import kotlinx.coroutines.flow.Flow

/**
 * Device registration and recovery.
 *
 * Endpoints (`ApiUrl.kt`):
 *  - `POST api/v2/device-otp` — send a code to an email
 *  - `POST api/v2/device-otp/verify` — exchange the code for a confirm token
 *  - `GET  api/v2/device` — device details
 *  - `POST api/v2/device/recovery` — recover via a registered email
 *  - `POST api/v2/device/recover-code` — recover via a one-time code
 */
interface AuthRepository {

    /** Emits the current session, or null when signed out. */
    val session: Flow<AuthSession?>

    /**
     * Sends a one-time code to [email].
     *
     * Returns success even for an unregistered address. The server behaves this
     * way on purpose and the client must not "helpfully" distinguish them: a
     * different response for a known address turns this endpoint into an
     * enumeration oracle for who works on which production.
     */
    suspend fun requestOtp(email: String, language: String = "en"): ZillitResult<kotlin.Unit>

    /**
     * Exchanges [otp] for a confirm code, completing device verification.
     */
    suspend fun verifyOtp(email: String, otp: String): ZillitResult<String>

    /** Registers this device against a verified email, yielding a session. */
    suspend fun registerDevice(email: String, confirmCode: String): ZillitResult<DeviceIdentity>

    /**
     * Asks the server whether this device is still registered.
     *
     * The check a 401 gets put through before it is believed. There is no token
     * to refresh in this API — authority rides on the device registration — so
     * the nearest thing to renewing a session is confirming it still exists.
     */
    suspend fun confirmDevice(): DeviceStatus

    /** Starts recovery for a device the user has lost access to. */
    suspend fun requestRecovery(email: String): ZillitResult<kotlin.Unit>

    suspend fun recoverWithCode(code: String): ZillitResult<DeviceIdentity>

    /**
     * Clears the session and every local trace of it.
     *
     * Wipes keychain entries and the encrypted database, in that order — so an
     * interrupted sign-out still leaves an unreadable database rather than a
     * readable one (plan §8.5).
     */
    suspend fun signOut(): ZillitResult<kotlin.Unit>

    /**
     * Restores a previously linked device from the keychain.
     *
     * Returns null when this machine has never been linked. A non-null result
     * means the device id is known — **not** that the server still honours it;
     * the first authenticated call is what proves that.
     */
    suspend fun restoreDevice(): ZillitResult<DeviceIdentity?>

    /**
     * Records a device linked by some route other than email registration.
     *
     * QR linking produces an identity too, and without this it was never
     * written to the keychain — so a QR-linked machine asked for a fresh scan
     * on every launch while the email path resumed cleanly.
     */
    suspend fun rememberDevice(identity: DeviceIdentity): ZillitResult<kotlin.Unit>
}

/**
 * Projects and units.
 *
 * Endpoints: `GET/POST api/v2/project`, `POST api/v2/user/join-project`,
 * `GET api/v2/project/status`.
 */
/** Reference lists for the create-production form. */
interface PresetRepository {
    suspend fun productionTypes(): ZillitResult<List<ProductionType>>
    suspend fun languages(): ZillitResult<List<ProductionLanguage>>
}

interface ProjectRepository {

    /**
     * Creates a production.
     *
     * [confirmCode] comes from verifying the entered email with a one-time code
     * — the backend will not create a production for an address nobody has
     * proved they can read.
     *
     * Returns the created [Project]; its `code` is what crew use to join, and
     * it is the one thing the user must be shown afterwards.
     */
    suspend fun create(
        draft: NewProductionDraft,
        selectedType: ProductionType?,
        confirmCode: String,
    ): ZillitResult<Project>

    suspend fun listProjects(): ZillitResult<List<Project>>

    suspend fun findByCode(code: String): ZillitResult<Project>

    /**
     * Asks to join [projectId], sending the details a production needs.
     *
     * The project is named explicitly rather than taken from the open one: the
     * user is asking to enter a production they are not in, so there is nothing
     * open to read it from.
     */
    suspend fun requestJoin(projectId: String, draft: JoinDraft): ZillitResult<JoinStatus>

    /** Departments on [projectId], with their roles nested. */
    suspend fun departments(projectId: String): ZillitResult<List<Department>>

    suspend fun joinStatus(projectId: String): ZillitResult<JoinStatus>

    suspend fun listUnits(projectId: String): ZillitResult<List<Unit>>

    /**
     * Switches the active project.
     *
     * Far more than a field update: it invalidates project-scoped caches, resets
     * project-scoped preferences, and closes every project-scoped workspace
     * window. Leaving one production's call sheet open while another is active
     * is the kind of mistake that puts the wrong information in front of a crew.
     */
    /**
     * Stars or unstars a production (`project/favourite-project`).
     *
     * Returns Unit: the caller already knows the value it asked for, and the
     * endpoint echoes nothing worth reading.
     */
    // `kotlin.Unit` spelled out: this package declares its own `Unit` — a
    // production unit — which shadows it.
    suspend fun setFavourite(projectId: String, favourite: Boolean): ZillitResult<kotlin.Unit>

    /**
     * Leaves the active production, returning to the picker.
     *
     * The inverse of [selectProject]: it clears the project from the session and
     * from the request headers, so nothing that fires afterwards carries a
     * production the user is no longer in.
     */
    suspend fun leaveProject(): ZillitResult<kotlin.Unit>

    suspend fun selectProject(project: Project, unit: Unit?): ZillitResult<kotlin.Unit>
}
