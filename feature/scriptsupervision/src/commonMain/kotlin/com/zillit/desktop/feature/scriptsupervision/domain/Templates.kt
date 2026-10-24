package com.zillit.desktop.feature.scriptsupervision.domain

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The template designer's model — the web's `screens/reports/templateDefs.js`.
 *
 * A template says what a day's logging looks like: which sections appear, in
 * what order, and what fields each one holds. Logging through it writes a
 * take plus one record per section, so the SECTION KEYS here are the
 * service's own record kinds, not labels.
 *
 * ⚠ **Labels are written INTO the template when it is made**, not resolved
 * when it is read. [buildDefaultSections] takes a resolver and bakes the
 * words in, which is why a template made in English stays English for
 * everyone afterwards. That is the web's behaviour and the service's model —
 * `label` is a stored string — so it is kept rather than improved.
 */
object Templates {

    /** Every field type the designer offers, in the web's order. */
    val FIELD_TYPES: List<String> = listOf(
        "text",
        "textarea",
        "number",
        "select",
        "multiselect",
        "switch",
        "checkbox",
        "date",
        "time",
        "image",
        "lookup",
        "calculated",
        "divider",
    )

    /** Prime lengths, as the designer lists them. */
    val LENSES: List<String> = listOf(
        "12", "14", "16", "18", "21", "25", "27", "32", "35", "40", "50", "65",
        "75", "85", "100", "135", "150", "200",
    )

    /** What a lookup field can be filled from. */
    val LOOKUP_SOURCES: List<String> = listOf("cast", "scene", "breakdown_element", "breakdown_category")

    /** A condition's operators, and a calculated field's formulas. */
    val CONDITION_OPERATORS: List<String> = listOf("equals", "not_equals", "is_set", "is_not_set")
    val FORMULA_TYPES: List<String> = listOf("duration_diff", "concat", "count")

    /**
     * The built-in sections, in their default order, with their fields.
     *
     * Transcribed from the web exactly, options included: the template log
     * maps several of these onto service enums, so a renamed option is a
     * value the service refuses.
     */
    val SECTION_DEFS: List<SectionDef> = listOf(
        SectionDef(
            key = "scene",
            required = true,
            fields = listOf(
                FieldDef("scene_number", "number", required = true),
                FieldDef("scene_name", "text", required = true),
                FieldDef("scene_description", "textarea"),
                FieldDef("script_page_start", "number"),
                FieldDef("script_page_end", "number"),
                FieldDef("story_day", "text"),
                FieldDef("shooting_day", "text"),
                FieldDef("location", "text"),
                FieldDef(
                    "status",
                    "select",
                    options = listOf("pending", "in_progress", "completed", "omitted", "postponed"),
                ),
            ),
        ),
        SectionDef(
            key = "take",
            required = true,
            fields = listOf(
                FieldDef("take_number", "number", required = true),
                FieldDef("slate_number", "text"),
                FieldDef("camera_roll", "text"),
                FieldDef("duration_display", "text"),
                FieldDef("lens_mm", "select", options = LENSES),
                FieldDef("fps", "select", options = listOf("23.976", "24", "25", "29.97", "30", "48", "60", "120")),
                FieldDef(
                    "shutter_angle",
                    "select",
                    options = listOf("45", "72", "90", "144", "172.8", "180", "270", "360"),
                ),
                FieldDef("shot_description", "textarea"),
                FieldDef("sound_roll", "text"),
                FieldDef("status_divider", "divider"),
                FieldDef("is_printed", "switch"),
                FieldDef("is_circled", "switch"),
                FieldDef("is_directors_select", "switch"),
                FieldDef("is_ng", "switch"),
                FieldDef("is_wild_track", "switch"),
                FieldDef("is_end_board", "switch"),
                FieldDef("is_mos", "switch"),
                FieldDef("notes", "textarea"),
                FieldDef("director_notes", "textarea"),
            ),
        ),
        SectionDef(
            key = "camera_details",
            fields = listOf(
                FieldDef("camera", "select", options = listOf("A-Cam", "B-Cam", "C-Cam", "D-Cam")),
                FieldDef("lens_mm", "select", options = LENSES),
                FieldDef(
                    "shot_size",
                    "select",
                    options = listOf("ECU", "CU", "MCU", "MS", "MWS", "WS", "EWS", "OTS", "POV", "2-Shot", "Group"),
                ),
                FieldDef(
                    "camera_angle",
                    "select",
                    options = listOf("Eye Level", "Low Angle", "High Angle", "Dutch", "Bird's Eye", "Worm's Eye"),
                ),
                FieldDef(
                    "screen_direction",
                    "select",
                    options = listOf(
                        "Left to Right",
                        "Right to Left",
                        "Toward Camera",
                        "Away from Camera",
                        "Static",
                    ),
                ),
                FieldDef(
                    "camera_movement",
                    "select",
                    options = listOf(
                        "Static", "Pan", "Tilt", "Dolly", "Crane", "Steadicam", "Handheld", "Drone", "Zoom",
                    ),
                ),
                FieldDef(
                    "filter",
                    "select",
                    options = listOf(
                        "None", "ND 0.3", "ND 0.6", "ND 0.9", "ND 1.2", "Polarizer",
                        "Pro-Mist 1/8", "Pro-Mist 1/4", "Pro-Mist 1/2", "Classic Soft", "Glimmer Glass",
                    ),
                ),
                FieldDef("notes", "textarea"),
            ),
        ),
        SectionDef(
            key = "dialog_notes",
            fields = listOf(
                FieldDef("character", "text", required = true),
                FieldDef("script_line", "text"),
                FieldDef("actual_line", "textarea"),
                FieldDef("is_ad_lib", "switch"),
                FieldDef("line_change_approved", "switch"),
                FieldDef("notes", "textarea"),
            ),
        ),
        SectionDef(
            key = "action_continuity",
            fields = listOf(
                FieldDef("character", "text", required = true),
                FieldDef("description", "textarea"),
                FieldDef("hand_props", "textarea"),
                FieldDef("body_position", "textarea"),
                FieldDef("eyeline", "textarea"),
                FieldDef("movement_notes", "textarea"),
                FieldDef("continuity_errors", "text"),
            ),
        ),
        SectionDef(
            key = "sound_report",
            fields = listOf(
                FieldDef("sound_roll", "text", required = true),
                FieldDef("track_number", "number"),
                FieldDef("timecode", "text"),
                FieldDef(
                    "record_type",
                    "select",
                    required = true,
                    options = listOf("sync", "mos", "wild", "room_tone"),
                ),
                FieldDef("sample_rate", "text"),
                FieldDef("channels", "number"),
                FieldDef("mic_setup", "text"),
                FieldDef(
                    "quality",
                    "select",
                    options = listOf("excellent", "good", "usable", "noisy", "unusable"),
                ),
                FieldDef("adr_needed", "switch"),
                FieldDef("notes", "textarea"),
            ),
        ),
        SectionDef(
            key = "props_wardrobe",
            fields = listOf(
                FieldDef("character", "text", required = true),
                FieldDef("wardrobe", "textarea"),
                FieldDef("makeup", "textarea"),
                FieldDef("props_name", "text"),
                FieldDef("props_state", "text"),
                FieldDef("props_notes", "text"),
            ),
        ),
        SectionDef(
            key = "matching_note",
            fields = listOf(
                FieldDef(
                    "category",
                    "select",
                    required = true,
                    options = DashboardStats.MATCH_CATEGORIES,
                ),
                FieldDef("description", "textarea", required = true),
                FieldDef("priority", "select", options = listOf("high", "medium", "low")),
                FieldDef("resolved", "switch"),
            ),
        ),
        SectionDef(
            key = "stunt_safety",
            fields = listOf(
                FieldDef("risk_level", "select", required = true, options = listOf("low", "medium", "high", "extreme")),
                FieldDef("stunt_description", "textarea"),
                FieldDef("stunt_coordinator", "text"),
                FieldDef("performers", "text"),
                FieldDef("safety_briefing_completed", "switch"),
                FieldDef("medic_on_set", "switch"),
                FieldDef("fire_safety", "switch"),
                FieldDef("wardrobe_before", "text"),
                FieldDef("wardrobe_after", "text"),
                FieldDef("makeup_before", "text"),
                FieldDef("makeup_after", "text"),
                FieldDef("incidents", "textarea"),
                FieldDef("notes", "textarea"),
            ),
        ),
        SectionDef(
            key = "weather_lighting",
            fields = listOf(
                FieldDef("story_time_of_day", "text"),
                FieldDef("actual_time_of_day", "text"),
                FieldDef("interior_exterior", "select", options = listOf("INT", "EXT", "INT/EXT")),
                FieldDef("weather_actual", "text"),
                FieldDef("lighting_setup", "textarea"),
                FieldDef("natural_light", "text"),
                FieldDef("color_temp", "text"),
                FieldDef("notes", "textarea"),
            ),
        ),
    )

    /** Quick-add bundles of fields, in the web's order. */
    val FIELD_GROUP_PRESETS: List<SectionDef> = listOf(
        SectionDef(
            key = "lens_package",
            fields = listOf(
                FieldDef(
                    "lens_brand",
                    "select",
                    options = listOf("Cooke", "Zeiss", "Arri", "Panavision", "Canon", "Angenieux"),
                ),
                FieldDef("lens_type", "select", options = listOf("Prime", "Zoom", "Anamorphic", "Spherical")),
                FieldDef("focal_length", "text"),
                FieldDef("t_stop", "text"),
                FieldDef("focus_distance", "text"),
            ),
        ),
        SectionDef(
            key = "audio_setup",
            fields = listOf(
                FieldDef("boom_mic", "text"),
                FieldDef("lav_mics", "textarea"),
                FieldDef("recorder", "text"),
                FieldDef("sample_rate", "select", options = listOf("44.1kHz", "48kHz", "96kHz")),
                FieldDef("bit_depth", "select", options = listOf("16-bit", "24-bit", "32-bit float")),
            ),
        ),
        SectionDef(
            key = "wardrobe_set",
            fields = listOf(
                FieldDef("character", "text", required = true),
                FieldDef("costume_description", "textarea"),
                FieldDef("accessories", "textarea"),
                FieldDef("distressing_notes", "textarea"),
                FieldDef("photo_reference", "text"),
            ),
        ),
        SectionDef(
            key = "vfx_notes",
            fields = listOf(
                FieldDef(
                    "plate_type",
                    "select",
                    options = listOf("Clean", "Dirty", "Green Screen", "Blue Screen", "HDRI"),
                ),
                FieldDef("tracking_markers", "switch"),
                FieldDef("green_screen", "switch"),
                FieldDef("hdri_captured", "switch"),
                FieldDef("vfx_notes", "textarea"),
            ),
        ),
        SectionDef(
            key = "location_info",
            fields = listOf(
                FieldDef("address", "textarea"),
                FieldDef("gps_coordinates", "text"),
                FieldDef("parking_notes", "text"),
                FieldDef("base_camp", "text"),
                FieldDef("nearest_hospital", "text"),
            ),
        ),
    )

    /** Built-in sections' data colours, and the fallbacks a custom one takes. */
    val SECTION_COLORS: Map<String, String> = mapOf(
        "scene" to "#2563eb",
        "take" to "#7c3aed",
        "camera_details" to "#0891b2",
        "dialog_notes" to "#db2777",
        "action_continuity" to "#ea580c",
        "sound_report" to "#059669",
        "props_wardrobe" to "#65a30d",
        "matching_note" to "#dc2626",
        "stunt_safety" to "#f97316",
        "weather_lighting" to "#0d9488",
    )
    private val CUSTOM_SECTION_COLORS: Map<String, String> = mapOf(
        "notes" to "#6366f1",
        "section" to "#0891b2",
        "table" to "#7c3aed",
    )
    private const val CUSTOM_DEFAULT_COLOR = "#6366f1"

    /** The colours a custom section can be given. */
    val SECTION_SWATCHES: List<String> = listOf(
        "#2563eb", "#7c3aed", "#0891b2", "#db2777", "#ea580c", "#059669", "#65a30d",
        "#dc2626", "#f97316", "#0d9488", "#6366f1", "#F7941D", "#8b5cf6", "#0ea5e9",
    )

    /** A section's data colour: its own, its built-in one, or its type's. */
    fun sectionColour(section: TemplateSection): String = section.color.ifBlank {
        SECTION_COLORS[section.key]
            ?: CUSTOM_SECTION_COLORS[section.sectionType]
            ?: CUSTOM_DEFAULT_COLOR
    }

    /**
     * The module word a field's label comes from.
     *
     * Most are `field_<key>`; the handful here are words the module already
     * has under another name, which is the same rule the catalogue follows.
     */
    fun fieldLabelKey(key: String): String = FIELD_LABEL_KEYS[key] ?: "field_$key"

    /** The module word a section's label comes from. */
    fun sectionLabelKey(key: String): String = when (key) {
        "scene" -> "scene"
        "take" -> "take"
        else -> "section_$key"
    }

    /** A built-in section by key, or null for a custom one. */
    fun sectionDef(key: String): SectionDef? = SECTION_DEFS.firstOrNull { it.key == key }

    /**
     * [list] with the item at [from] moved one place up (-1) or down (+1).
     *
     * The same list back when the move would fall off either end, so a call
     * site can offer the control and let this refuse it.
     */
    fun <T> moved(list: List<T>, from: Int, by: Int): List<T> {
        val to = from + by
        if (from !in list.indices || to !in list.indices) return list
        val next = list.toMutableList()
        next.add(to, next.removeAt(from))
        return next
    }

    /** A new template's sections: every built-in one, on, with its fields. */
    fun buildDefaultSections(label: (String) -> String): List<TemplateSection> =
        SECTION_DEFS.mapIndexed { index, def ->
            TemplateSection(
                key = def.key,
                label = label(sectionLabelKey(def.key)),
                enabled = true,
                sortOrder = index,
                fields = def.fields.map { it.toField(label) },
            )
        }

    /** A built-in section's fields, for one switched back on with none left. */
    fun defaultFields(key: String, label: (String) -> String): List<TemplateField> =
        sectionDef(key)?.fields.orEmpty().map { it.toField(label) }

    /**
     * A saved template's sections as the editor holds them, in saved order.
     *
     * ⚠ `enabled` is `!= false`, not `== true`: the service leaves the key
     * out of a section that has never been switched, and reading that as off
     * would hide half a template the first time it was opened.
     */
    fun loadSections(template: Rec?): List<TemplateSection> =
        template?.recs("sections").orEmpty()
            .mapIndexed { index, rec ->
                TemplateSection(
                    key = rec.str("key"),
                    label = rec.str("label").ifBlank { rec.str("key") },
                    enabled = rec.json["enabled"].let { it == null || it is JsonNull || rec.bool("enabled") },
                    sortOrder = if (rec.has("sort_order")) rec.int("sort_order") else index,
                    hideTitle = rec.bool("hide_title"),
                    isCustom = rec.bool("isCustom"),
                    sectionType = rec.str("sectionType"),
                    color = rec.str("color"),
                    fields = rec.recs("fields").map(::loadField),
                )
            }
            .sortedBy { it.sortOrder }

    private fun loadField(rec: Rec): TemplateField = TemplateField(
        key = rec.str("key"),
        label = rec.str("label"),
        type = rec.str("type").ifBlank { "text" },
        required = rec.bool("required"),
        options = rec.strings("options"),
        defaultValue = rec.json["defaultValue"]?.takeIf { it !is JsonNull },
        validation = rec.rec("validation")?.let { v ->
            Validation(
                min = v.doubleOrNull("min"),
                max = v.doubleOrNull("max"),
                minLength = v.int("minLength").takeIf { it > 0 },
                maxLength = v.int("maxLength").takeIf { it > 0 },
                pattern = v.str("pattern"),
                patternLabel = v.str("patternLabel"),
            )
        },
        conditions = rec.recs("conditions").map {
            Condition(field = it.str("field"), operator = it.str("operator"), value = it.str("value"))
        },
        formula = rec.rec("formula")?.let {
            Formula(formulaType = it.str("formulaType"), sourceFields = it.strings("sourceFields"))
        },
        lookup = rec.rec("lookupConfig")?.let {
            Lookup(source = it.str("source"), displayField = it.str("displayField"))
        },
    )

    /**
     * The editor's sections as the service's template body takes them.
     *
     * ⚠ `sort_order` is written from the list's POSITION, not from the
     * section's own stored number. Reordering only moves list items, so
     * saving the old numbers would store the old order.
     *
     * Empty settings are left out entirely rather than sent as blanks: the
     * service keeps what it is given, and an empty `validation` object on
     * every field is noise that comes back on every read.
     */
    fun toSectionsBody(sections: List<TemplateSection>): JsonArray = buildJsonArray {
        sections.forEachIndexed { index, section ->
            add(
                buildJsonObject {
                    put("key", section.key)
                    put("label", section.label)
                    put("enabled", section.enabled)
                    put("sort_order", index)
                    put("hide_title", section.hideTitle)
                    if (section.isCustom) put("isCustom", true)
                    if (section.sectionType.isNotBlank()) put("sectionType", section.sectionType)
                    // Kept as saved: there is no colour picker for a built-in.
                    if (section.color.isNotBlank()) put("color", section.color)
                    put("fields", buildJsonArray { section.fields.forEach { add(it.toBody()) } })
                },
            )
        }
    }

    /**
     * What a template FILE carries: the design, not the project's bookkeeping.
     *
     * No id, no version, no `is_default` — an exported template is a design
     * to start from, and importing one must not claim another project's
     * default or pretend to be version 7 of something.
     */
    fun portable(template: Rec): JsonObject = buildJsonObject {
        put("name", template.str("name"))
        put("description", template.str("description"))
        put("tags", buildJsonArray { template.strings("tags").forEach { add(JsonPrimitive(it)) } })
        put("sections", template.json["sections"] as? JsonArray ?: JsonArray(emptyList()))
    }

    /** Whether a decoded file is a template at all. */
    fun isTemplateFile(rec: Rec?): Boolean =
        rec != null && rec.str("name").isNotBlank() && rec.json["sections"] is JsonArray

    /**
     * A name safe to save a file under.
     *
     * ⚠ A RUN of anything that is not a letter or a digit collapses to ONE
     * underscore, which is the web's `/[^a-zA-Z0-9]+/g`. Replacing character
     * by character instead turns "Day 1 (template)" into
     * "Day_1__template_", and the two clients would name the same export
     * differently.
     */
    fun fileSafe(name: String): String =
        name.ifBlank { "template" }.replace(NOT_FILE_SAFE, "_")

    private val NOT_FILE_SAFE = Regex("[^a-zA-Z0-9]+")

    private val FIELD_LABEL_KEYS: Map<String, String> = mapOf(
        "scene_name" to "scene_name",
        "scene_description" to "description",
        "shooting_day" to "shooting_day",
        "location" to "location",
        "status" to "status",
        "status_divider" to "status",
        "take_number" to "take",
        "slate_number" to "slate",
        "camera_roll" to "camera_roll",
        "sound_roll" to "sound_roll",
        "is_printed" to "flag_is_printed",
        "is_circled" to "flag_is_circled",
        "is_directors_select" to "flag_is_directors_select",
        "is_ng" to "flag_is_ng",
        "is_mos" to "flag_is_mos",
        "notes" to "notes",
        "description" to "description",
    )
}

/** A built-in section or a preset group: its key and the fields it brings. */
data class SectionDef(val key: String, val required: Boolean = false, val fields: List<FieldDef>)

/** One field of a built-in section, before its label is resolved. */
data class FieldDef(
    val key: String,
    val type: String,
    val required: Boolean = false,
    val options: List<String> = emptyList(),
) {
    /** This definition as an editable field, with its label baked in. */
    fun toField(label: (String) -> String): TemplateField = TemplateField(
        key = key,
        label = label(Templates.fieldLabelKey(key)),
        type = type,
        required = required,
        options = options,
    )
}

/** A section of a template, as the editor holds it. */
data class TemplateSection(
    val key: String,
    val label: String,
    val enabled: Boolean = true,
    val sortOrder: Int = 0,
    val hideTitle: Boolean = false,
    val isCustom: Boolean = false,
    val sectionType: String = "",
    val color: String = "",
    val fields: List<TemplateField> = emptyList(),
)

/**
 * A field of a template, as the editor holds it.
 *
 * ⚠ [defaultValue] is a raw [JsonElement] rather than a String because a
 * default is whatever its field's type stores — a boolean for a switch, a
 * number, a LIST for a multiselect. Flattening it to text would quietly
 * rewrite a multiselect's default to `[a, b]` the first time the template
 * was opened and saved.
 */
data class TemplateField(
    val key: String,
    val label: String,
    val type: String,
    val required: Boolean = false,
    val options: List<String> = emptyList(),
    val defaultValue: JsonElement? = null,
    val validation: Validation? = null,
    val conditions: List<Condition> = emptyList(),
    val formula: Formula? = null,
    val lookup: Lookup? = null,
) {
    /** Whether this field offers a list to pick from. */
    val isChoice: Boolean get() = type == "select" || type == "multiselect"

    /** This field as the service's template body takes it. */
    fun toBody(): JsonObject = buildJsonObject {
        put("key", key)
        put("label", label)
        put("type", type)
        put("required", required)
        defaultValue?.takeIf { it !is JsonNull && it.toString() != "\"\"" }?.let { put("defaultValue", it) }
        validation?.toBody()?.takeIf { it.isNotEmpty() }?.let { put("validation", it) }
        // The service requires a field on every rule, so a half-written one
        // is dropped rather than sent and refused.
        conditions.filter { it.field.isNotBlank() }
            .takeIf { it.isNotEmpty() }
            ?.let { rules -> put("conditions", buildJsonArray { rules.forEach { add(it.toBody()) } }) }
        formula?.takeIf { type == "calculated" && it.formulaType.isNotBlank() }?.let { put("formula", it.toBody()) }
        lookup?.takeIf { type == "lookup" && it.source.isNotBlank() }?.let { put("lookupConfig", it.toBody()) }
        if (isChoice) {
            put("options", buildJsonArray { options.filter { it.isNotBlank() }.forEach { add(JsonPrimitive(it)) } })
        }
    }
}

/** What a field will accept. Every part is optional. */
data class Validation(
    val min: Double? = null,
    val max: Double? = null,
    val minLength: Int? = null,
    val maxLength: Int? = null,
    val pattern: String = "",
    val patternLabel: String = "",
) {
    fun toBody(): JsonObject = buildJsonObject {
        min?.let { put("min", it) }
        max?.let { put("max", it) }
        minLength?.let { put("minLength", it) }
        maxLength?.let { put("maxLength", it) }
        if (pattern.isNotBlank()) put("pattern", pattern)
        if (patternLabel.isNotBlank()) put("patternLabel", patternLabel)
    }
}

/** A "show this field when…" rule. */
data class Condition(val field: String, val operator: String, val value: String) {
    fun toBody(): JsonObject = buildJsonObject {
        put("field", field)
        put("operator", operator)
        put("value", value)
    }
}

/** How a calculated field works its value out. */
data class Formula(val formulaType: String, val sourceFields: List<String>) {
    fun toBody(): JsonObject = buildJsonObject {
        put("formulaType", formulaType)
        put("sourceFields", buildJsonArray { sourceFields.forEach { add(JsonPrimitive(it)) } })
    }
}

/** Where a lookup field's choices come from. */
data class Lookup(val source: String, val displayField: String) {
    fun toBody(): JsonObject = buildJsonObject {
        put("source", source)
        if (displayField.isNotBlank()) put("displayField", displayField)
    }
}
