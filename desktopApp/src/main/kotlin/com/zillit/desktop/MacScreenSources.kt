package com.zillit.desktop

import com.zillit.desktop.core.common.ZillitLog
import com.zillit.desktop.feature.calls.domain.ScreenSources
import com.zillit.desktop.feature.calls.domain.ShareSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File

/**
 * The share picker's source list, from the bundled `zillit-capture` helper.
 *
 * A separate process because ScreenCaptureKit is the only API left that can
 * enumerate windows with pictures — `CGWindowListCreateImage`, which JNA could
 * have reached from here, is gone from the macOS 26 SDK — and because a binary
 * inside `Contents/MacOS/` inherits the app's bundle identity, so the Screen
 * Recording permission it needs is Zillit's rather than a nameless helper's.
 *
 * The helper prints its list first and its pictures afterwards, one JSON
 * object per line, so this emits a complete list immediately and then re-emits
 * it as each preview lands. Nothing waits for the slowest capture.
 *
 * Null in a `./gradlew :desktopApp:run` session — the helper only exists in a
 * packaged build — and the caller treats that as "no picker", sharing the
 * whole screen the way it did before there was one.
 */
class MacScreenSources(private val helper: File) : ScreenSources {

    override fun list(): Flow<List<ShareSource>> = flow {
        val process = runCatching {
            ProcessBuilder(helper.absolutePath, "--max-thumbs", MAX_PREVIEWS.toString())
                .redirectErrorStream(false)
                .start()
        }.getOrElse { thrown ->
            ZillitLog.w(TAG) { "could not run the source helper: ${thrown.message}" }
            emit(emptyList())
            return@flow
        }

        try {
            var sources = emptyList<ShareSource>()
            process.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    val frame = parse(line) ?: continue
                    when (frame) {
                        is Frame.Listed -> {
                            sources = frame.sources
                            emit(sources)
                        }
                        is Frame.Preview -> {
                            // Re-emitted whole rather than as a patch: the list
                            // is a couple of dozen small rows, and a picker
                            // that rebuilds them is simpler than one that
                            // reconciles them.
                            sources = sources.map {
                                if (it.id == frame.id) it.copy(previewPng = frame.png) else it
                            }
                            emit(sources)
                        }
                    }
                }
            }
            // stderr is drained only now, after stdout is closed: the helper
            // writes little there and reading both concurrently would need a
            // thread apiece for a diagnostic nobody reads twice.
            val complaint = runCatching { process.errorStream.bufferedReader().readText() }
                .getOrDefault("")
                .trim()
            if (complaint.isNotEmpty()) ZillitLog.w(TAG) { complaint }
            if (sources.isEmpty()) emit(emptyList())
        } finally {
            process.destroy()
        }
    }.flowOn(Dispatchers.IO)

    private sealed interface Frame {
        data class Listed(val sources: List<ShareSource>) : Frame
        data class Preview(val id: String, val png: String) : Frame
    }

    private fun parse(line: String): Frame? {
        val obj = runCatching { json.parseToJsonElement(line) as? JsonObject }.getOrNull() ?: return null
        return when (obj.str("type")) {
            "sources" -> Frame.Listed(
                obj.rows("displays").map { row ->
                    ShareSource(
                        id = row.str("id").orEmpty(),
                        name = row.str("name").orEmpty(),
                        isScreen = true,
                    )
                } + obj.rows("windows").map { row ->
                    ShareSource(
                        id = row.str("id").orEmpty(),
                        name = row.str("title").orEmpty(),
                        app = row.str("app").orEmpty(),
                        isScreen = false,
                    )
                },
            )
            "thumb" -> Frame.Preview(
                id = obj.str("id").orEmpty(),
                png = obj.str("png").orEmpty(),
            )
            else -> null
        }?.takeUnless { it is Frame.Listed && it.sources.any { source -> source.id.isBlank() } }
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.rows(key: String): List<JsonObject> =
        (this[key] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }

    private companion object {
        const val TAG = "ScreenSources"

        /**
         * How many previews to ask for.
         *
         * Every one is a real capture, so the cost is per picture rather than
         * per row. Enough to fill the picker's first couple of screens; the
         * rest still list and still share, they just show their name instead
         * of a picture.
         */
        const val MAX_PREVIEWS = 24

        val json = Json { ignoreUnknownKeys = true }
    }
}

/**
 * The bundled `zillit-capture`, or null when this is not a packaged macOS
 * build. Located exactly as the notification helper is — see
 * [TrayNotifier.Companion.macNotifyHelper] for why both paths are tried.
 */
internal fun macCaptureHelper(): File? = runCatching {
    val fromLauncher = System.getProperty("jpackage.app-path")?.let { File(it).parentFile }
    val fromRuntime = System.getProperty("java.home")
        ?.let { File(it).parentFile?.parentFile?.parentFile?.resolve("MacOS") }
    listOfNotNull(fromLauncher, fromRuntime)
        .map { it.resolve("zillit-capture") }
        .firstOrNull { it.canExecute() }
}.getOrNull()
