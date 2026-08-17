package com.zillit.desktop.feature.auth.domain

import com.zillit.desktop.core.common.ZillitResult

/**
 * QR device linking — the desktop side of the flow the Android app already uses.
 *
 * ## Who shows and who scans
 *
 * The **new** device displays a QR; an **already signed-in** device scans it
 * from Settings → Linked Devices. That is how the Android client works today
 * (`StartProjectVM.saveQrCode` displays, `AdminVM.scanQrCode` scans), and it is
 * the right way round for desktop: a laptop's webcam faces the user, not the
 * page, so asking a desktop to scan a code would be awkward at best.
 *
 * Same backend contract, no server changes:
 *
 * ```
 * POST device/qrcode          {code, file_name}   → {_id, code}
 * POST device/qrcode/{id}     {code}              → {scanner_device_id?}   (poll)
 * POST device/link-scanned    {device info}       → device identity
 * ```
 */
interface QrLoginRepository {

    /**
     * Starts a session: generates a code, registers it, returns it for display.
     */
    suspend fun startSession(): ZillitResult<QrLoginSession>

    /**
     * Asks whether the code has been scanned yet.
     *
     * Polled, because the backend exposes no socket event for this. Returns the
     * **scanning** device's id, or null while nothing has scanned it — that id
     * is required by [completeLink]'s header, so returning a bare Boolean would
     * mean asking for it twice.
     */
    suspend fun pollScanned(session: QrLoginSession): ZillitResult<String?>

    /**
     * Completes linking once scanned, yielding this device's identity.
     *
     * [scannerDeviceId] comes from [pollScanned] and travels in the request
     * header, not the body (`MODELDATA.SCANNER_DEVICE_ID`).
     */
    suspend fun completeLink(
        session: QrLoginSession,
        scannerDeviceId: String,
    ): ZillitResult<DeviceIdentity>
}

/**
 * A pending QR sign-in.
 *
 * [code] is a **credential**: anyone who photographs it during its lifetime can
 * link their own device. It must never be logged, put in a URL, or sent
 * anywhere but the Zillit backend — which is why the desktop encodes the QR
 * locally rather than calling a third-party image service the way the Android
 * client does.
 */
data class QrLoginSession(
    val id: String,
    val code: String,
    /** Milliseconds since epoch after which the code must be regenerated. */
    val expiresAtMillis: Long,
) {
    fun isExpired(nowMillis: Long): Boolean = nowMillis >= expiresAtMillis

    fun secondsRemaining(nowMillis: Long): Int =
        ((expiresAtMillis - nowMillis) / MILLIS_PER_SECOND).toInt().coerceAtLeast(0)

    override fun toString(): String = "QrLoginSession(id=$id, code=***, expiresAt=$expiresAtMillis)"

    companion object {
        const val MILLIS_PER_SECOND = 1_000L

        /**
         * How long the client keeps polling a code before offering a reload.
         *
         * 30 seconds, matching the web client's
         * `setTimeout(..., 30000)` → `setShowRetry(true)`.
         *
         * This is a **client-side** window, not a server-enforced expiry: the
         * backend is not known to invalidate the code, so a photographed screen
         * may stay usable for longer. Treating the code as a standing credential
         * until the server says otherwise is the safe reading — see
         * `OUT_OF_SCOPE.md`.
         */
        const val LIFETIME_MILLIS = 30_000L
    }
}
