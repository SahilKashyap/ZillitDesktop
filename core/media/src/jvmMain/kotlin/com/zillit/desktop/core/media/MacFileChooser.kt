package com.zillit.desktop.core.media

import com.zillit.desktop.core.common.ZillitLog
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * macOS's own Open panel, restricted to a kind.
 *
 * AWT's `FileDialog` accepts a `FilenameFilter` and then, on macOS, ignores
 * it: every file stays selectable and a "Photo" pick shows the whole disk.
 * `NSOpenPanel` restricts by content type, and AppleScript's `choose file of
 * type` is that panel — reached through `osascript`, which needs no helper
 * binary, no automation permission, and no signature. Cancel is an error the
 * script reports as `-128`; anything else that goes wrong hands the pick back
 * to AWT rather than losing it.
 */
internal object MacFileChooser {

    /** Null when the panel could not be shown at all; empty when cancelled. */
    fun choose(kind: PreviewKind, multiple: Boolean): List<File>? = runCatching {
        val arguments = chooseFileScript(kind, multiple).flatMap { listOf("-e", it) }
        val process = ProcessBuilder(listOf("osascript") + arguments).redirectErrorStream(false).start()
        val output = process.inputStream.bufferedReader().readText()
        val errors = process.errorStream.bufferedReader().readText()
        if (!process.waitFor(PANEL_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
            process.destroy()
            return null
        }
        when {
            process.exitValue() == 0 -> parseChosenPaths(output).map(::File)
            USER_CANCELLED in errors -> emptyList()
            else -> {
                ZillitLog.w(TAG) { "native chooser failed: ${errors.trim().take(LOG_LIMIT)}" }
                null
            }
        }
    }.getOrElse { error ->
        ZillitLog.w(TAG) { "native chooser unavailable: $error" }
        null
    }

    private const val TAG = "Attach"
    private const val USER_CANCELLED = "-128"
    private const val PANEL_TIMEOUT_MINUTES = 30L
    private const val LOG_LIMIT = 200
}

/**
 * The uniform type identifiers a kind admits — what the panel greys out
 * everything else against. Document is open, as on the phones.
 */
internal val PreviewKind.uniformTypes: List<String>?
    get() = when (this) {
        PreviewKind.Image -> listOf("public.image")
        PreviewKind.Video -> listOf("public.movie", "public.video")
        PreviewKind.Audio -> listOf("public.audio")
        PreviewKind.Document -> null
    }

/**
 * The AppleScript, one statement per line, that shows the panel and prints
 * one POSIX path per line. Single and multiple picks differ in what
 * `choose file` returns (an alias, or a list of them), so the single form is
 * wrapped in a list to share the loop.
 */
internal fun chooseFileScript(kind: PreviewKind, multiple: Boolean): List<String> {
    val types = kind.uniformTypes?.joinToString(", ") { "\"$it\"" }?.let { " of type {$it}" }.orEmpty()
    val prompt = " with prompt \"${kind.pickerTitle}\""
    val choose = "choose file$types$prompt" + if (multiple) " with multiple selections allowed" else ""
    return listOf(
        if (multiple) "set chosen to ($choose)" else "set chosen to {($choose)}",
        "set out to \"\"",
        "repeat with f in chosen",
        "set out to out & POSIX path of f & linefeed",
        "end repeat",
        "return out",
    )
}

/** The panel's answer: one path per line, blank lines dropped. */
internal fun parseChosenPaths(output: String): List<String> =
    output.lines().map(String::trim).filter { it.isNotEmpty() }

/**
 * Windows' filter is the dialog's file pattern (`*.jpg;*.png`) — the one
 * form AWT honours there, where the `FilenameFilter` is likewise ignored.
 */
internal fun PreviewKind.windowsFilePattern(): String? =
    extensions?.joinToString(";") { "*.$it" }
