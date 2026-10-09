package com.zillit.desktop.core.config

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * The three calling engines, by the word calls and call logs already store.
 *
 * Which one a call ran on never changes with the configuration; only the
 * number a user sees for it does (see [CallLineNumbering]).
 */
object CallEngines {
    const val LIVEKIT = "livekit"
    const val AGORA = "agora"
    const val MEDIASOUP = "mediasoup"

    /** Every engine the desktop can place a call on. */
    val SUPPORTED: Set<String> = setOf(LIVEKIT, AGORA, MEDIASOUP)

    /**
     * The numbering when `calling_lines` does not list an engine: product's
     * renumbering of 2026-10, LiveKit first and mediasoup last.
     */
    private val BUILT_IN_NUMBER = mapOf(LIVEKIT to 1, AGORA to 2, MEDIASOUP to 3)

    /** An engine's built-in line number. */
    fun builtInNumber(engine: String): Int = BUILT_IN_NUMBER[normalise(engine)] ?: BUILT_IN_NUMBER.getValue(MEDIASOUP)

    /**
     * The engine a stored word names. A call with no line tag is an old
     * mediasoup call, from before the field existed.
     */
    fun normalise(raw: String?): String = raw?.trim()?.lowercase().orEmpty().ifEmpty { MEDIASOUP }
}

/** `All`, in any case: every production. */
private fun String.isAll(): Boolean = equals(CallingLinesConfig.ALL, ignoreCase = true)

/** One entry of `calling_lines`, as read. */
data class CallingLine(
    val engine: String,
    /** Position in the picker, and the number in "Line N". Always positive. */
    val order: Int,
    val enabled: Boolean = true,
    /** `All` (any case) for every production, else production ids; empty is none. */
    val projects: List<String> = listOf(CallingLinesConfig.ALL),
    /** Lower-cased platform names; null when the entry does not say, which is every platform. */
    val platforms: List<String>? = null,
) {
    /** Whether this line may be offered in [projectId] on [platform]. */
    fun isAvailable(projectId: String?, platform: String): Boolean =
        enabled &&
            projects.any { it.isAll() || (projectId != null && it == projectId) } &&
            (platforms == null || platform in platforms)
}

/**
 * Firebase Remote Config's `calling_lines`: which lines exist, their order and
 * their numbers, the same on every client.
 *
 * ```
 * {"lines": [{"engine": "livekit", "order": 1, "enabled": true,
 *             "projects": ["All"], "platforms": ["android", "ios", "web", "desktop"]}, …]}
 * ```
 *
 * A configured list is the whole truth: an engine it does not name is off.
 */
data class CallingLinesConfig(val lines: List<CallingLine>) {

    /** The entry for [engine], whether or not it is enabled. */
    fun lineFor(engine: String): CallingLine? = lines.firstOrNull { it.engine == CallEngines.normalise(engine) }

    companion object {
        const val KEY = "calling_lines"
        const val ALL = "All"

        private val json = Json { ignoreUnknownKeys = true }

        /**
         * The value read, or null when it does not configure anything — absent,
         * blank, not JSON, no `lines` array, or no entry that survives the rules:
         * engine lower-cased and trimmed and one of [supported]; a missing or
         * non-positive `order` skips the entry; of the rest, the first entry of
         * a repeated engine wins; `enabled` defaults to true, `projects` to
         * `["All"]`, and an absent `platforms` means every platform.
         */
        fun parse(raw: String?, supported: Set<String> = CallEngines.SUPPORTED): CallingLinesConfig? {
            val root = unwrap(raw) as? JsonObject ?: return null
            val entries = root["lines"] as? JsonArray ?: return null
            val seen = mutableSetOf<String>()
            val lines = entries.mapNotNull { element ->
                val entry = element as? JsonObject ?: return@mapNotNull null
                val engine = entry.text("engine")?.trim()?.lowercase()
                    ?.takeIf { it.isNotEmpty() && it in supported } ?: return@mapNotNull null
                val order = entry.int("order")?.takeIf { it > 0 } ?: return@mapNotNull null
                if (!seen.add(engine)) return@mapNotNull null
                CallingLine(
                    engine = engine,
                    order = order,
                    enabled = entry.bool("enabled") ?: true,
                    projects = entry.strings("projects")?.map(String::trim)?.filter(String::isNotEmpty)
                        ?: listOf(ALL),
                    platforms = entry.strings("platforms")?.map { it.trim().lowercase() }?.filter(String::isNotEmpty),
                )
            }
            return lines.takeIf { it.isNotEmpty() }?.let(::CallingLinesConfig)
        }

        /**
         * The console stores values as typed, and has been seen to store them
         * wrapped in literal quotes — so a JSON string holding JSON is read once more.
         */
        private fun unwrap(raw: String?): JsonElement? {
            val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val element = runCatching { json.parseToJsonElement(text) }.getOrNull() ?: return null
            val inner = (element as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return element
            return runCatching { json.parseToJsonElement(inner) }.getOrNull()
        }

        private fun JsonObject.present(key: String): JsonElement? = get(key)?.takeIf { it !is JsonNull }

        private fun JsonObject.text(key: String): String? = (present(key) as? JsonPrimitive)?.contentOrNull

        private fun JsonObject.int(key: String): Int? {
            val value = present(key) as? JsonPrimitive ?: return null
            return value.intOrNull ?: value.contentOrNull?.trim()?.toIntOrNull()
        }

        private fun JsonObject.bool(key: String): Boolean? {
            val value = present(key) as? JsonPrimitive ?: return null
            return value.booleanOrNull ?: value.contentOrNull?.trim()?.lowercase()?.toBooleanStrictOrNull()
        }

        /** A list of strings; a lone string is a list of one. Null when the key is absent. */
        private fun JsonObject.strings(key: String): List<String>? = when (val value = present(key)) {
            null -> null
            is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            is JsonPrimitive -> listOfNotNull(value.contentOrNull)
            else -> emptyList()
        }
    }
}

/**
 * The rules from before `calling_lines`, still what decides when it is not
 * configured: `ios_calling_enabled` with `ios_calling_line1_enabled` for
 * mediasoup, with `ios_calling_line2_enabled` for Agora, and
 * `line_three_enabled_in` for LiveKit.
 *
 * The desktop never read the first three, and no Firebase project publishes
 * them to the app id it fetches with — so an absent flag is on, which keeps
 * Agora and mediasoup offered exactly as they always were.
 */
data class LegacyCallingFlags(
    val callingEnabled: Boolean = true,
    val line1Enabled: Boolean = true,
    val line2Enabled: Boolean = true,
    /** `line_three_enabled_in`: production ids or `All`; empty hides LiveKit. */
    val lineThreeIn: List<String> = emptyList(),
) {
    fun isAvailable(engine: String, projectId: String?): Boolean = when (CallEngines.normalise(engine)) {
        CallEngines.MEDIASOUP -> callingEnabled && line1Enabled
        CallEngines.AGORA -> callingEnabled && line2Enabled
        CallEngines.LIVEKIT -> lineThreeIn.any { it.isAll() } || (projectId != null && projectId in lineThreeIn)
        else -> false
    }

    companion object {
        const val CALLING_ENABLED = "ios_calling_enabled"
        const val LINE1_ENABLED = "ios_calling_line1_enabled"
        const val LINE2_ENABLED = "ios_calling_line2_enabled"
        const val LINE_THREE_ENABLED_IN = "line_three_enabled_in"

        /** The flags out of one Remote Config fetch; null (never fetched) is every default. */
        fun from(entries: Map<String, String>?): LegacyCallingFlags = LegacyCallingFlags(
            callingEnabled = flag(entries?.get(CALLING_ENABLED)) ?: true,
            line1Enabled = flag(entries?.get(LINE1_ENABLED)) ?: true,
            line2Enabled = flag(entries?.get(LINE2_ENABLED)) ?: true,
            lineThreeIn = projectList(entries?.get(LINE_THREE_ENABLED_IN)),
        )

        /** A console boolean: `true`/`false`, possibly quoted; anything else is unsaid. */
        fun flag(raw: String?): Boolean? = raw?.trim()?.trim('"', '“', '”')?.lowercase()?.toBooleanStrictOrNull()

        /** `line_three_enabled_in`: a JSON array of ids or `All`, or the bare word; anything unreadable is nothing. */
        fun projectList(raw: String?): List<String> {
            val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return emptyList()
            val array = runCatching { Json.parseToJsonElement(text) as? JsonArray }.getOrNull()
                ?: return if (text.isAll()) listOf(CallingLinesConfig.ALL) else emptyList()
            return array.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }
        }
    }
}

/**
 * Which lines a production offers on one platform, and what each is called.
 *
 * [config] wins whenever it is set; [legacy] decides only when it is not.
 * [installed] is the engines this install can actually reach — LiveKit needs
 * its backend URLs — and narrows either rule without renumbering anything.
 */
class CallingLinesPolicy(
    val config: CallingLinesConfig?,
    private val legacy: LegacyCallingFlags = LegacyCallingFlags(),
    private val platform: String = DESKTOP,
    private val installed: Set<String> = CallEngines.SUPPORTED,
) {
    /** Whether a call may be started on [engine] in [projectId]. */
    fun isAvailable(engine: String, projectId: String?): Boolean {
        val wire = CallEngines.normalise(engine)
        if (wire !in installed) return false
        return config?.lineFor(wire)?.isAvailable(projectId, platform)
            ?: (config == null && legacy.isAvailable(wire, projectId))
    }

    /** The engines offered in [projectId], in picker order ("Line 1" first). */
    fun available(projectId: String?): List<String> =
        CallEngines.SUPPORTED.filter { isAvailable(it, projectId) }
            .sortedWith(compareBy({ numberOf(it) }, { CallEngines.builtInNumber(it) }))

    /** The N in "Line N" for [engine]; a listed engine keeps its number even while it is off. */
    fun numberOf(engine: String?): Int = numberOf(config, engine)

    companion object {
        const val DESKTOP = "desktop"

        fun numberOf(config: CallingLinesConfig?, engine: String?): Int {
            val wire = CallEngines.normalise(engine)
            return config?.lineFor(wire)?.order ?: CallEngines.builtInNumber(wire)
        }
    }
}

/**
 * The configuration line labels are drawn from, for the whole app.
 *
 * Labels are read in many places that have no route back to the host (a log
 * row, a detail subtitle, the in-call pill), and a line's number is the same
 * wherever it is shown — so it is held once, here, and set by the host
 * whenever a fetch changes it.
 */
object CallLineNumbering {
    private val current = MutableStateFlow<CallingLinesConfig?>(null)

    /** The configuration in force; null is the built-in numbering. */
    val config: StateFlow<CallingLinesConfig?> = current.asStateFlow()

    fun update(config: CallingLinesConfig?) {
        current.value = config
    }

    /** The N in "Line N" for a call on [engine]; an untagged call is mediasoup. */
    fun numberOf(engine: String?): Int = CallingLinesPolicy.numberOf(current.value, engine)
}
