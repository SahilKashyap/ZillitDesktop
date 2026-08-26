package com.zillit.desktop.feature.auth.data

import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.ZillitService

/**
 * Auth and project endpoint paths.
 *
 * Built from [AppConfig] rather than hardcoded, because each backend service has
 * its own host per environment (~30 of them — see `ZillitService`). The Android
 * app assembles these as string constants against a single `BuildConfig.BASE_URL`
 * per flavor, which is why adding an environment there means editing 800 lines
 * of `buildConfigField`.
 *
 * Paths transcribed from `ApiUrl.kt`.
 */
internal class AuthEndpoints(private val config: AppConfig) {

    private val core get() = config.apiV2(ZillitService.Core)

    /** `POST` — sends a one-time code to an email address. */
    val requestOtp get() = "${core}device-otp"

    /** `POST` — exchanges the code for a confirm token. */
    val verifyOtp get() = "${core}device-otp/verify"

    /** `GET`/`POST` — device details and registration. */
    val device get() = "${core}device"

    /**
     * `POST {device_id}` — sign this device out. Android's `ApiUrl.LOGOUT`
     * (`"${PROJECT_DEVICE}unlink"`, `StartProjectVM.logout`), sent with the
     * project-user headers; the older `DELETE device` here was a guess the
     * server never acted on.
     */
    val unlinkDevice get() = "${core}device/unlink"

    /** `POST` — begin recovery using a registered email. */
    val recoveryByEmail get() = "${core}device/recovery"

    /** `POST` — complete recovery with a one-time code. */
    val recoveryByCode get() = "${core}device/recover-code"

    /** `GET` list · `POST` create. */
    val projects get() = "${core}project"

    /** `POST` — star or unstar (`PATH_PROJECT_FAV_AND_UNFAV`). */
    val favouriteProject get() = "${core}project/favourite-project"

    /** `GET` — production types and their sub-types. */
    val projectTypes get() = "${core}preset/project-types"

    /** `GET ?lang=` — languages a production can be created in. */
    val languages get() = "${core}preset/languages"

    /**
     * `PUT` — resolve a production code.
     *
     * Android's `PATH_JOIN_USER_BOTH_CODE`: "both" because it answers for a
     * production's shared code *and* for one issued to a single person. It
     * replaced `GET project/{code}`, which now answers 404 — that call is
     * still in the Android source, commented out directly above this one.
     */
    val joinProjectAsUser get() = "${core}user/join-project-as-user"

    /** `POST` request · `GET` pending list. */
    val joinProject get() = "${core}user/join-project"

    /**
     * `GET` — departments with their roles nested.
     *
     * `designations=true` is what avoids a second call per department change;
     * both other clients ask for it the same way.
     */
    val departments get() = "${core}departments?designations=true"

    /** `GET` — whether this device's join request has been approved. */
    val joinStatus get() = "${core}project/status"

    /** Units live on their own service. */
    fun units(projectId: String) =
        "${config.apiV2(ZillitService.Units)}unit?project_id=$projectId"
}
