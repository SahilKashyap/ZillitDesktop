package com.zillit.desktop.feature.scriptsupervision.domain

/**
 * The quick log's rules — the web's `takeLogs/quickLogOptions.js` and the
 * parts of `shared.js` and `QuickLogForm.jsx` that are arithmetic rather than
 * layout.
 *
 * The quick log is the one screen in this module that is used WHILE A TAKE IS
 * RUNNING, so almost everything here exists to save keystrokes: the slate
 * steps itself, the camera settings carry from the last take, and a duration
 * is typed as digits with the colon putting itself in. Every one of those is a
 * rule a reader cannot check by eye, which is why they are here and tested.
 */
object QuickLog {

    /**
     * The flags the quick log offers as one-tap toggles.
     *
     * A subset of [ScriptSupervision.TAKE_FLAGS] and in a different order:
     * these are the five a script supervisor hits mid-take, biggest first.
     * `is_end_board` is here and not in the take list, because it is a
     * property of the SLATE rather than of the take.
     */
    val FLAGS: List<String> = listOf(
        "is_circled",
        "is_directors_select",
        "is_ng",
        "is_mos",
        "is_end_board",
    )

    /** Typed as text in a suggestion field, stored as a number. */
    val NUMERIC: List<String> = listOf("lens_mm", "fps", "shutter_angle", "iso_ei")

    /**
     * What carries into the next take from the scene's last one.
     *
     * The slate and the camera's settings: the things that do NOT change
     * between takes of the same shot, so re-typing them would be the bulk of
     * the work.
     */
    val CARRIED: List<String> = listOf(
        "slate_number",
        "camera_roll",
        "sound_roll",
        "lens_mm",
        "fps",
        "shutter_angle",
        "iso_ei",
        "color_temp",
        "nd_filter",
        "t_stop",
    )

    /** Cleared after every take: what was true of that take alone. */
    val PER_TAKE: List<String> = listOf(
        "duration_display",
        "ng_reason",
        "notes",
        "director_notes",
        "has_dialogue_change",
        "dialogue_character",
        "script_line",
        "actual_line",
        "has_continuity_issue",
        "continuity_description",
        "continuity_priority",
        "has_sound_issue",
        "sound_quality",
        "sound_noise_issues",
        "sound_adr_needed",
    )

    /** Cleared when a NEW SLATE starts: what describes the shot, not the take. */
    val PER_SLATE: List<String> = listOf(
        "shot_description",
        "dialogue_coverage",
        "action_description",
        "first_shot_time",
    )

    /**
     * The service's camera-detail shot sizes. Any other value is refused, so
     * these are its codes exactly, in its order.
     */
    val SHOT_SIZES: List<String> = listOf(
        "EWS",
        "WS",
        "MWS",
        "MS",
        "MCU",
        "CU",
        "BCU",
        "ECU",
        "OTS",
        "POV",
        "2-Shot",
        "Group",
        "Insert",
        "Cutaway",
        "Establishing",
        "Aerial",
    )

    /**
     * The module text key for a shot size.
     *
     * `2-Shot` is the one code that is not a usable key, so the web spells it
     * out; everything else lower-cases.
     */
    fun shotSizeKey(code: String): String =
        "shot_size_" + if (code == "2-Shot") "two_shot" else code.lowercase()

    val MOVEMENTS: List<String> = listOf(
        "Static",
        "Pan",
        "Tilt",
        "Dolly",
        "Truck",
        "Crane",
        "Steadicam",
        "Handheld",
        "Slider",
        "Drone",
        "Push In",
        "Pull Out",
        "Zoom",
        "Roving",
        "Tracking",
        "Whip Pan",
    )

    val ANGLES: List<String> = listOf(
        "Eye Level",
        "High Angle",
        "Low Angle",
        "Dutch",
        "Bird's Eye",
        "Worm's Eye",
        "Frontal",
        "3/4 Left",
        "3/4 Right",
        "Profile",
    )

    /** Which way the action travels across frame. The arrows are the stored values. */
    val SCREEN_DIRECTIONS: List<String> = listOf("L→R", "R→L", "Toward Camera", "Away From Camera", "N/A")

    /** The lens lengths the suggestion field offers, in millimetres. */
    val LENSES: List<Int> = listOf(
        12, 14, 16, 18, 21, 24, 25, 27, 28, 32, 35, 40, 45, 50, 55, 65, 75, 85, 100, 135, 150, 200, 300,
    )

    val FRAME_RATES: List<String> = listOf(
        "23.976", "24", "25", "29.97", "30", "48", "50", "59.94", "60", "96", "120", "240",
    )

    val SHUTTERS: List<String> = listOf(
        "45", "72", "86.4", "90", "120", "144", "150", "172.8", "180", "270", "360",
    )

    val EXPOSURE_INDEXES: List<Int> = listOf(
        100, 160, 200, 250, 320, 400, 500, 640, 800, 1000,
        1250, 1600, 2000, 2500, 3200, 4000, 5000, 6400, 8000, 12800,
    )

    val COLOR_TEMPS: List<String> = listOf(
        "2700K", "3000K", "3200K", "3400K", "3600K", "4000K", "4300K",
        "4500K", "5000K", "5200K", "5600K", "6000K", "6500K", "7500K",
    )

    /** Each ND filter and the stops of light it takes; 0 is clear glass. */
    val ND_FILTERS: List<Pair<String, Int>> = listOf(
        "0" to 0,
        "0.3" to 1,
        "0.6" to 2,
        "0.9" to 3,
        "1.2" to 4,
        "1.5" to 5,
        "1.8" to 6,
        "2.1" to 7,
    )

    val T_STOPS: List<String> = listOf(
        "T1.3", "T1.4", "T1.5", "T2", "T2.8", "T4", "T5.6", "T8", "T11", "T16", "T22",
    )

    /**
     * Why a take was no good.
     *
     * These are PHRASES rather than codes, so they are offered in the viewer's
     * language and the chosen TEXT is what the service stores — a note a human
     * reads back, not an enum.
     */
    val NG_REASONS: List<String> = listOf(
        "boom_in_shot",
        "flubbed_line",
        "broke_character",
        "camera_issue",
        "focus_soft",
        "extra_in_frame",
        "crew_visible",
        "sound_issue",
        "continuity_error",
        "cut_early",
        "false_start",
        "timing_off",
        "props_wrong",
        "wardrobe_issue",
        "weather_change",
        "traffic_noise",
        "aircraft",
    )

    val CONTINUITY_PRIORITIES: List<String> = listOf("low", "medium", "high")

    val SOUND_QUALITIES: List<String> = listOf("excellent", "good", "usable", "noisy")

    /**
     * The next slate after [slate]: "25" → "26", "25A" → "25B".
     *
     * Null for anything else, and the web is deliberate about that — a slate
     * the production numbers its own way is left for the user to type rather
     * than guessed at. 'Z' is excluded for the same reason the web excludes
     * it (`[A-Y]`): there is no letter after it to step to.
     */
    fun nextSlate(slate: String?): String? {
        val hit = SLATE.matchEntire(slate.orEmpty().trim()) ?: return null
        val (digits, letter) = hit.destructured
        if (letter.isEmpty()) return (digits.toLongOrNull()?.plus(1))?.toString()
        return digits + (letter.single().uppercaseChar() + 1)
    }

    /**
     * The fields a new take starts with, taken from the scene's last take.
     *
     * A value the service stores as 0 or "" means nobody entered it, so it is
     * left out rather than carried as a zero — which would read as a real lens
     * length of 0mm.
     */
    fun carriedFrom(take: Rec?): Map<String, String> {
        if (take == null) return emptyMap()
        return CARRIED.mapNotNull { key ->
            val value = take.str(key).takeIf { it.isNotBlank() && it != "0" } ?: return@mapNotNull null
            key to value
        }.toMap()
    }

    /**
     * A duration as it is typed on set: digits only, and the colon puts itself
     * in — "152" reads as "1:52".
     *
     * Four digits at most, so the longest take this field takes is 99:99. The
     * web does the same in a `getValueFromEvent`.
     */
    fun maskClock(typed: String): String {
        val digits = typed.filter { it.isDigit() }.take(CLOCK_DIGITS)
        if (digits.length <= MINUTE_DIGITS) return digits
        return digits.dropLast(MINUTE_DIGITS) + ":" + digits.takeLast(MINUTE_DIGITS)
    }

    /** "M:SS" as the service's `duration_seconds`; 0 is "not set". */
    fun toSeconds(text: String): Int {
        if (text.isBlank()) return 0
        val parts = if (':' in text) text.split(':') else listOf("0", text)
        val minutes = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: 0
        val seconds = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 0
        return minutes * SECONDS_PER_MINUTE + seconds
    }

    /** `duration_seconds` as "M:SS"; 0 reads blank, because 0 means "not set". */
    fun fromSeconds(total: Int): String {
        if (total <= 0) return ""
        val seconds = total % SECONDS_PER_MINUTE
        return "${total / SECONDS_PER_MINUTE}:${seconds.toString().padStart(MINUTE_DIGITS, '0')}"
    }

    /**
     * The lines of a scene's script worth offering as "Script line".
     *
     * Headings and cues are dropped: a line that is ALL CAPS is a slug line, a
     * character cue or a transition, never dialogue. The web's own test is a
     * regex over caps, punctuation and digits, kept here as the same test.
     */
    fun scriptLines(scene: Rec?): List<String> {
        val lines = scene?.strings("script_text").orEmpty()
        return lines.map { it.trim() }
            .filter { it.isNotEmpty() && !ALL_CAPS.matches(it) }
            .distinct()
    }

    private val SLATE = Regex("^(\\d+)([A-Ya-y]?)$")
    private val ALL_CAPS = Regex("^[A-Z][A-Z\\s\\-'.#0-9()]+$")

    private const val CLOCK_DIGITS = 4
    private const val MINUTE_DIGITS = 2
    private const val SECONDS_PER_MINUTE = 60
}
