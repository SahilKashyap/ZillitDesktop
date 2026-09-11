package com.zillit.desktop.feature.taxfiling.domain

/**
 * The anti-fraud signals HMRC requires with a filing.
 *
 * Making Tax Digital obliges the software to describe the machine the return
 * was filed from, and the backend turns these into its `Gov-Client-*` headers.
 * They ride the obligation sync and the submission — the two calls that reach
 * HMRC — and nothing else.
 *
 * ## The open question, and why it is not answered here
 *
 * The web collects a browser's signals and the backend declares the connection
 * method `WEB_APP_VIA_SERVER`. A desktop application is not that: HMRC's own
 * method for one is `DESKTOP_APP_VIA_SERVER`, which asks for different things
 * — the machine's local addresses and MAC addresses among them — and forbids
 * some of what a browser sends.
 *
 * Sending a browser's shape from here would have the backend declare a
 * connection method that is not true, to a tax authority, on a legal return.
 * So this carries only what a desktop can honestly report, and the fields it
 * cannot are left absent rather than invented. **The backend's connection
 * method must be confirmed before this is used against live HMRC** — see the
 * host's own note where these are collected.
 */
data class FraudSignals(
    /**
     * A stable identifier for this installation.
     *
     * Must survive restarts: HMRC reads a device that changes every session as
     * a different machine each time.
     */
    val deviceId: String = "",
    /** `UTC±HH:MM`, which is the format HMRC specifies. */
    val timezone: String = "",
    val userAgent: String = "",
    val doNotTrack: Boolean = false,
    /** `width=…&height=…&scaling-factor=…&colour-depth=…`, HMRC's spelling. */
    val screens: String = "",
    val windowSize: String = "",
) {
    val isComplete: Boolean get() = deviceId.isNotBlank() && timezone.isNotBlank()
}

/** Where the host supplies them. Absent leaves the two HMRC calls unavailable. */
fun interface FraudSignalSource {
    suspend fun collect(): FraudSignals
}
