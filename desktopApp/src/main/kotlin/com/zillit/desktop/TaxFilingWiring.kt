package com.zillit.desktop

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.email.data.DownloadsAttachmentStore
import com.zillit.desktop.feature.taxfiling.data.TaxFilingRepositoryImpl
import com.zillit.desktop.feature.taxfiling.domain.FraudSignalSource
import com.zillit.desktop.feature.taxfiling.domain.FraudSignals
import com.zillit.desktop.feature.taxfiling.domain.LedgerExportSink
import com.zillit.desktop.feature.taxfiling.ui.TaxFilingToolProvider
import com.zillit.desktop.feature.taxfiling.ui.TaxFilingViewModel
import java.awt.GraphicsEnvironment
import java.awt.Window
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * HMRC Making Tax Digital.
 *
 * The signals source is the only interesting part of this wiring: without one
 * the module refuses the two calls that reach HMRC, and with a wrong one it
 * would have the backend describe this machine to a tax authority falsely.
 */
internal fun AppGraph.Ready.buildTaxFiling() = TaxFilingViewModel(
    repository = TaxFilingRepositoryImpl(apiClient, config),
    signals = desktopFraudSignals(deviceId),
    obligationWindow = ::defaultObligationWindow,
    exportSink = ledgerExportSink(),
)

/**
 * The ledger export, into Downloads and then in front of the accountant.
 *
 * Opened after saving, unlike a mail attachment: this file is something this
 * application just produced from the production's own ledger, not something
 * a stranger sent.
 */
private fun ledgerExportSink() = LedgerExportSink { fileName, csv ->
    when (val saved = DownloadsAttachmentStore().save(fileName, csv.encodeToByteArray())) {
        is ZillitResult.Failure -> saved
        is ZillitResult.Success -> {
            openSavedFile(saved.data)
            ZillitResult.Success(Unit)
        }
    }
}

internal fun taxFilingProvider(viewModel: TaxFilingViewModel) =
    TaxFilingToolProvider(viewModel, openConsent = ::openInBrowser)

/**
 * What this machine can honestly tell HMRC about itself.
 *
 * ## Read this before filing against live HMRC
 *
 * Making Tax Digital asks the software to declare a **connection method**, and
 * the method decides which `Gov-Client-*` headers are required and which are
 * forbidden. The web sends a browser's signals and its backend declares
 * `WEB_APP_VIA_SERVER`. This is a desktop application, whose method is
 * `DESKTOP_APP_VIA_SERVER` — a different header set, wanting the machine's
 * local IPs and MAC addresses, and not wanting a browser's do-not-track or
 * window size at all.
 *
 * So this sends what a desktop can truthfully report and leaves the rest
 * empty; it does not dress a desktop up as a browser to fill a form. **The
 * backend's declared connection method has not been confirmed** — if it
 * declares the web's, HMRC is being told something untrue about how a legal
 * return reached it, and that is the backend's to fix, not this file's to
 * paper over. Until then the returns this files are ordinary MTD submissions
 * with an incomplete anti-fraud header set, which HMRC may reject; a rejection
 * is the honest failure, and inventing values is not the fix.
 *
 * A null [deviceId] — before this installation has registered with Zillit —
 * leaves the signals incomplete, which the view model treats as "cannot reach
 * the authority" and says so on screen.
 */
internal fun desktopFraudSignals(deviceId: () -> String?): FraudSignalSource = FraudSignalSource {
    FraudSignals(
        deviceId = deviceId().orEmpty(),
        timezone = utcOffset(),
        userAgent = desktopUserAgent(),
        // Browser-only in HMRC's spec, and a desktop has no such setting. Sent
        // as false rather than omitted because the field is not optional in
        // the payload; the backend decides whether the header goes out.
        doNotTrack = false,
        screens = screenDescription(),
        windowSize = windowDescription(),
    )
}

/** `UTC+01:00` — HMRC's own format, not an IANA zone name. */
private fun utcOffset(): String {
    val offset = ZoneId.systemDefault().rules.getOffset(java.time.Instant.now())
    return if (offset.totalSeconds == 0) "UTC+00:00" else "UTC${offset.id}"
}

private fun desktopUserAgent(): String {
    val version = System.getProperty("jpackage.app-version") ?: "dev"
    val os = System.getProperty("os.name").orEmpty()
    val release = System.getProperty("os.version").orEmpty()
    val arch = System.getProperty("os.arch").orEmpty()
    return "Zillit-Desktop/$version ($os $release; $arch)"
}

/**
 * Every screen, in HMRC's spelling.
 *
 * `width=…&height=…&scaling-factor=…&colour-depth=…`, comma separated. Headless
 * or locked-down JVMs report none, and an empty string is the truthful answer
 * there.
 */
private fun screenDescription(): String = runCatching {
    if (GraphicsEnvironment.isHeadless()) return@runCatching ""
    GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.joinToString(",") { device ->
        val mode = device.displayMode
        val scale = device.defaultConfiguration.defaultTransform.scaleX
        "width=${mode.width}&height=${mode.height}" +
            "&scaling-factor=${scale.toInt()}&colour-depth=${mode.bitDepth}"
    }
}.getOrDefault("")

/** The largest window this application has open — `width=…&height=…`. */
private fun windowDescription(): String = runCatching {
    if (GraphicsEnvironment.isHeadless()) return@runCatching ""
    val window: Window? = Window.getWindows()
        .filter { it.isShowing }
        .maxByOrNull { it.width.toLong() * it.height }
    window?.let { "width=${it.width}&height=${it.height}" }.orEmpty()
}.getOrDefault("")

/**
 * A year back to today.
 *
 * HMRC will not answer an open-ended obligation query, and a year covers four
 * quarters or twelve months — every period a return could still be owed for
 * without going digging.
 */
private fun defaultObligationWindow(): Pair<String, String> {
    val today = LocalDate.now(ZoneOffset.UTC)
    return today.minusYears(1).toString() to today.toString()
}
