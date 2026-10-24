package com.zillit.desktop.feature.scriptsupervision.domain

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlin.math.abs

/**
 * Logging through a template — the web's `screens/reports/templateForm.js`
 * and the layout rules inside its `TemplateSection`.
 *
 * All of it is arithmetic over the template's own definition, which is why it
 * is here and tested: a slate that increments wrongly or a field that carries
 * over when it should have cleared puts bad data on a shooting day, and
 * nobody notices until the cutting room does.
 */
object TemplateForm {

    /** Take flags the log shows together as one row of toggles. */
    val TAKE_FLAG_KEYS: Set<String> = setOf(
        "is_circled",
        "is_directors_select",
        "is_ng",
        "is_printed",
        "is_mos",
        "is_end_board",
        "is_wild_track",
    )

    /**
     * Take fields that start empty again for the next take.
     *
     * ⚠ Everything NOT in here carries over on purpose — the lens, the
     * camera roll, the shot description stay as they were, because a second
     * take of the same setup is the same setup. Adding a field here that
     * should carry, or leaving one out that should clear, both put wrong
     * data in a log nobody re-reads.
     */
    val PER_TAKE_RESET_KEYS: Set<String> = setOf(
        "is_printed",
        "is_circled",
        "is_directors_select",
        "is_ng",
        "ng_reason",
        "is_mos",
        "is_wild_track",
        "is_end_board",
        "is_tail_slate",
        "duration_display",
        "notes",
        "director_notes",
    )

    /** Field types that take a whole row to themselves. */
    val WIDE_TYPES: Set<String> = setOf("textarea", "multiselect", "image")

    /** The take-flag toggles' colours when on, as the standalone has them. */
    val TAKE_FLAG_COLORS: Map<String, String> = mapOf(
        "is_circled" to "#059669",
        "is_directors_select" to "#d97706",
        "is_ng" to "#dc2626",
        "is_printed" to "#2563eb",
        "is_mos" to "#7c3aed",
        "is_end_board" to "#6b7280",
        "is_wild_track" to "#0891b2",
    )

    /**
     * The next slate: `"25"` → `"26"`, `"25A"` → `"25B"`.
     *
     * ⚠ A slate that is neither of those shapes is returned UNCHANGED rather
     * than mangled — a production that slates `"14-2"` or `"A14"` keeps its
     * own scheme, and guessing at it would renumber a shooting day's takes.
     * Nothing at all becomes `"1"`, which is where a slate starts.
     */
    fun incrementSlate(slate: String): String {
        if (slate.isBlank()) return "1"
        val match = SLATE.matchEntire(slate.trim())
            ?: return slate.trim().toIntOrNull()?.let { (it + 1).toString() } ?: slate
        val (digits, letter) = match.destructured
        return if (letter.isEmpty()) {
            (digits.toLong() + 1).toString()
        } else {
            digits + (letter[0] + 1)
        }
    }

    /**
     * Digits typed into a time or duration field, with the colon put in.
     *
     * Four digits at most, and the colon always two from the right, so typing
     * `130` reads `1:30` and `0130` reads `01:30`. Anything that is not a
     * digit is dropped as it is typed.
     */
    fun maskTime(value: String): String {
        val raw = value.filter { it.isDigit() }.take(MASK_DIGITS)
        return if (raw.length <= 2) raw else "${raw.dropLast(2)}:${raw.takeLast(2)}"
    }

    /**
     * Whether a field holds a duration and so takes the mask.
     *
     * ⚠ A label saying just "time" (a time of day) or "timecode"
     * (HH:MM:SS:FF) is deliberately NOT time-like: four digits with a colon
     * two from the right would mangle both.
     */
    fun isTimeLike(field: TemplateField): Boolean =
        field.type == "time" ||
            (
                field.type == "text" &&
                    (field.key == "duration_display" || DURATION_LABEL.containsMatchIn(field.label))
                )

    /**
     * Whether a field's "show when" rules hold for its section's values.
     *
     * No rules means always shown, and an operator the service invents later
     * also means shown — a field nobody can see is worse than one shown too
     * often.
     */
    fun conditionsHold(conditions: List<Condition>, values: Map<String, String>): Boolean =
        conditions.all { rule ->
            val actual = values[rule.field]
            when (rule.operator) {
                "equals" -> actual.orEmpty() == rule.value
                "not_equals" -> actual.orEmpty() != rule.value
                "is_set" -> !isEmpty(actual)
                "is_not_set" -> isEmpty(actual)
                else -> true
            }
        }

    /** A calculated field's value from its source fields' values. */
    fun computeFormula(formulaType: String, values: List<String>): String = when (formulaType) {
        "duration_diff" -> {
            val from = values.getOrNull(0).orEmpty()
            val to = values.getOrNull(1).orEmpty()
            if (from.isBlank() || to.isBlank()) {
                ""
            } else {
                val diff = abs(seconds(to) - seconds(from))
                "${diff / SECONDS_PER_MINUTE}:${(diff % SECONDS_PER_MINUTE).toString().padStart(2, '0')}"
            }
        }
        "concat" -> values.filter { it.isNotBlank() }.joinToString(" ")
        "count" -> values.count { !isEmpty(it) }.toString()
        else -> ""
    }

    /**
     * The template's own default values, by section and field.
     *
     * Only fields that HAVE a default appear, so a form built from this is
     * empty where the template says nothing — rather than full of blanks that
     * would then be saved as deliberate emptiness.
     */
    fun templateDefaults(sections: List<TemplateSection>): Map<String, Map<String, String>> {
        val values = mutableMapOf<String, MutableMap<String, String>>()
        sections.forEach { section ->
            section.fields.forEach { field ->
                val text = field.defaultValue?.let(::asText).orEmpty()
                if (text.isNotBlank()) {
                    values.getOrPut(section.key) { mutableMapOf() }[field.key] = text
                }
            }
        }
        return values
    }

    /**
     * The fields a take carries into the NEXT take, and the ones it clears.
     *
     * Returns the cleared values to write over the form: a flag goes back to
     * off and a text field to empty, while everything else is left exactly as
     * it is. The web does the same thing field by field after each save.
     */
    fun resetAfterTake(takeSection: TemplateSection?): Map<String, String> =
        takeSection?.fields.orEmpty()
            .filter { it.key in PER_TAKE_RESET_KEYS }
            .filter { it.type in RESETTABLE_TYPES }
            .associate { field ->
                field.key to if (field.type == "switch" || field.type == "checkbox") "false" else ""
            }

    /**
     * A section's fields broken into the runs the log draws.
     *
     * A divider starts a new run and titles it; a stretch of TAKE FLAGS
     * becomes one run of toggles rather than a row of switches. Everything
     * else gathers into runs of ordinary fields that wrap side by side.
     *
     * ⚠ A divider immediately followed by flags takes the flags into ITS
     * run rather than starting another — which is how the take section's
     * "Status" divider comes to title its flags instead of sitting above an
     * empty run. The web does this with a look-back at the run it just
     * pushed, and so does this.
     */
    fun fieldRuns(section: TemplateSection): List<FieldRun> {
        val runs = mutableListOf<FieldRun>()
        section.fields.forEach { field ->
            if (field.type == "divider") {
                runs.add(FieldRun(divider = field, flags = false, fields = mutableListOf()))
                return@forEach
            }
            val flag = isTakeFlag(section, field)
            val last = runs.lastOrNull()
            if (last == null || last.flags != flag) {
                if (last != null && last.divider != null && last.fields.isEmpty()) {
                    runs[runs.lastIndex] = last.copy(flags = flag)
                } else {
                    runs.add(FieldRun(divider = null, flags = flag, fields = mutableListOf()))
                }
            }
            runs.last().fields.add(field)
        }
        return runs
    }

    /** Whether this field is one of the take section's flag toggles. */
    fun isTakeFlag(section: TemplateSection, field: TemplateField): Boolean =
        section.key == "take" &&
            (field.type == "switch" || field.type == "checkbox") &&
            field.key in TAKE_FLAG_KEYS

    /**
     * Why this value will not do, or null when it will.
     *
     * The rules a field's own settings ask for, in the web's order, so the
     * first complaint a reader sees is the same one in both clients.
     */
    fun validate(field: TemplateField, value: String, say: (String, String) -> String): String? {
        if (field.required && isEmpty(value)) return say("field_required", field.label)
        val rules = field.validation ?: return null
        if (value.isBlank()) return null
        return numberProblem(rules, value, say)
            ?: lengthProblem(rules, value, say)
            ?: patternProblem(rules, value, say)
    }

    /** Outside the range the field's settings allow. */
    private fun numberProblem(rules: Validation, value: String, say: (String, String) -> String): String? {
        val number = value.toDoubleOrNull() ?: return null
        rules.min?.let { if (number < it) return say("rule_min", trim(it)) }
        rules.max?.let { if (number > it) return say("rule_max", trim(it)) }
        return null
    }

    /** Too short or too long. */
    private fun lengthProblem(rules: Validation, value: String, say: (String, String) -> String): String? {
        rules.minLength?.let { if (value.length < it) return say("rule_min_length", it.toString()) }
        rules.maxLength?.let { if (value.length > it) return say("rule_max_length", it.toString()) }
        return null
    }

    /**
     * Not the shape the field asks for.
     *
     * ⚠ A pattern that is not a valid expression checks NOTHING, as on the
     * web — it must not refuse every value instead, which would make the
     * field impossible to fill.
     */
    private fun patternProblem(rules: Validation, value: String, say: (String, String) -> String): String? {
        if (rules.pattern.isBlank()) return null
        val regex = runCatching { Regex(rules.pattern) }.getOrNull() ?: return null
        if (regex.containsMatchIn(value)) return null
        return rules.patternLabel.ifBlank { say("rule_pattern", "") }
    }

    /** A whole number prints without its decimal point. */
    private fun trim(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

    private fun isEmpty(value: String?): Boolean = value.isNullOrEmpty() || value == "false"

    /** `"1:30"` as 90 seconds; anything unparseable as 0. */
    private fun seconds(time: String): Int {
        val parts = time.split(':')
        val minutes = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: 0
        val rest = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 0
        return minutes * SECONDS_PER_MINUTE + rest
    }

    /** A stored default as the text a form field holds. */
    private fun asText(value: kotlinx.serialization.json.JsonElement): String = when {
        value is JsonNull -> ""
        value is JsonPrimitive -> value.booleanOrNull?.toString() ?: value.contentOrNull.orEmpty()
        else -> ""
    }

    private val SLATE = Regex("^(\\d+)([A-Z]?)$")
    private val DURATION_LABEL = Regex("\\b(mm:ss|h:mm|hh:mm|duration)\\b", RegexOption.IGNORE_CASE)
    private val RESETTABLE_TYPES = setOf("switch", "checkbox", "text", "textarea")
    private const val MASK_DIGITS = 4
    private const val SECONDS_PER_MINUTE = 60
}

/**
 * One run of a section's fields: an optional divider that titles it, and
 * either a row of take-flag toggles or ordinary fields.
 */
data class FieldRun(
    val divider: TemplateField?,
    val flags: Boolean,
    val fields: MutableList<TemplateField>,
)

/** One section's values in a log being filled in, by field key. */
typealias SectionValues = Map<String, String>

/**
 * A log's values as the service stores them: `{ section: { field: value } }`.
 *
 * ⚠ **Typed, not stringly.** A switch must go as a JSON `false`, never as
 * the string `"false"` — the service stores `is_circled` and the rest as
 * booleans, and most readers treat a non-empty string as true, so a take
 * explicitly marked NOT circled would come back circled. Numbers go as
 * numbers for the same reason. The form holds text because a half-typed
 * number is text; this is where that ends.
 *
 * ⚠ A switch is sent even when OFF. Only genuinely blank text is dropped —
 * a flag somebody deliberately turned off is a fact about the take, and
 * leaving it out would let a default put it back.
 */
fun Map<String, SectionValues>.toLogBody(sections: List<TemplateSection>): JsonObject {
    val typeOf = sections.associate { section ->
        section.key to section.fields.associate { it.key to it.type }
    }
    return buildJsonObject {
        forEach { (sectionKey, fields) ->
            val types = typeOf[sectionKey].orEmpty()
            val written = fields.filterKeys { types[it] != "divider" }
                .filter { (key, value) -> value.isNotBlank() || isBoolean(types[key]) }
            if (written.isNotEmpty()) {
                put(
                    sectionKey,
                    buildJsonObject {
                        written.forEach { (key, value) -> put(key, typedValue(types[key].orEmpty(), value)) }
                    },
                )
            }
        }
    }
}

private fun isBoolean(type: String?): Boolean = type == "switch" || type == "checkbox"

/** One value as its field's type stores it. */
private fun typedValue(type: String, value: String): JsonPrimitive = when {
    isBoolean(type) -> JsonPrimitive(value == "true")
    // A number the viewer has half-typed stays text rather than becoming 0.
    type == "number" -> value.toDoubleOrNull()?.let { JsonPrimitive(it) } ?: JsonPrimitive(value)
    else -> JsonPrimitive(value)
}
