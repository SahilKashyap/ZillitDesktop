package com.zillit.desktop.feature.scriptsupervision.ui.screens.templates

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.zillit.desktop.feature.scriptsupervision.domain.Rec
import com.zillit.desktop.feature.scriptsupervision.domain.TemplateField
import com.zillit.desktop.feature.scriptsupervision.domain.TemplateSection
import com.zillit.desktop.feature.scriptsupervision.domain.Templates

/**
 * A template being designed.
 *
 * The editor's whole state in one object, because almost every action reads
 * more of it than it writes: inserting a section needs the enabled ones to
 * know where "after the third" is, moving one needs its neighbours, and
 * saving needs all of it at once.
 *
 * Every change sets [dirty], which is what makes leaving the page ask.
 */
@Stable
internal class TemplateDraft(template: Rec?, label: (String) -> String) {
    val isNew: Boolean = template == null

    var name by mutableStateOf(template?.str("name").orEmpty())
        private set
    var description by mutableStateOf(template?.str("description").orEmpty())
        private set
    var tags by mutableStateOf(template?.strings("tags").orEmpty())
        private set
    var sections by mutableStateOf(
        if (template == null) Templates.buildDefaultSections(label) else Templates.loadSections(template),
    )
        private set
    var version by mutableStateOf(template?.int("version")?.coerceAtLeast(1) ?: 1)
        private set
    var history by mutableStateOf(template?.recs("version_history").orEmpty())
        private set
    var editingKey by mutableStateOf<String?>(null)
    var dirty by mutableStateOf(false)
        private set

    /** The section open in the side panel, if it is still there and still on. */
    val editing: TemplateSection? get() = sections.firstOrNull { it.key == editingKey && it.enabled }

    /** The sections a reader sees, in order. */
    val enabled: List<TemplateSection> get() = sections.filter { it.enabled }

    fun rename(value: String) { name = value; dirty = true }
    fun describe(value: String) { description = value; dirty = true }
    fun retag(value: List<String>) { tags = value; dirty = true }
    fun saved() { dirty = false }

    /**
     * A restored version replaces the SECTIONS and the version only.
     *
     * ⚠ Not the name, the description or the tags: the web keeps those,
     * because a restore is of the form's design, not of what it is called.
     */
    fun applyVersion(template: Rec) {
        sections = Templates.loadSections(template)
        version = template.int("version").coerceAtLeast(1)
        history = template.recs("version_history")
        editingKey = null
        dirty = false
    }

    fun update(section: TemplateSection) {
        sections = sections.map { if (it.key == section.key) section else it }
        dirty = true
    }

    /**
     * Switches a section on or off.
     *
     * ⚠ A BUILT-IN section switched back on with no fields left gets its
     * defaults back. Without that, a reader who emptied it and switched it
     * off is left with a section that cannot be used and cannot be refilled.
     */
    fun toggle(section: TemplateSection, on: Boolean, label: (String) -> String) {
        update(
            section.copy(
                enabled = on,
                fields = if (on && section.fields.isEmpty()) {
                    Templates.defaultFields(section.key, label)
                } else {
                    section.fields
                },
            ),
        )
    }

    /**
     * Adds a section after the enabled one at [afterIndex].
     *
     * -1 puts it first, and an index past the end puts it last — which is
     * what the "+" between sections and the Sections menu's Custom need
     * respectively.
     */
    fun insert(type: String, afterIndex: Int, label: (String) -> String) {
        val section = newSection(type, label)
        val after = enabled.getOrNull(afterIndex)
        sections = when {
            afterIndex < 0 -> listOf(section) + sections
            after == null -> sections + section
            else -> sections.toMutableList().apply { add(indexOf(after) + 1, section) }
        }
        editingKey = section.key
        dirty = true
    }

    /** A built-in section is switched OFF; a custom one is deleted. */
    fun remove(section: TemplateSection, label: (String) -> String) {
        if (Templates.sectionDef(section.key) != null) {
            toggle(section, on = false, label = label)
        } else {
            sections = sections.filterNot { it.key == section.key }
            dirty = true
        }
        editingKey = null
    }

    /** One place up or down AMONG THE ENABLED sections, which is what is seen. */
    fun move(key: String, by: Int) {
        val order = enabled
        val from = order.indexOfFirst { it.key == key }
        val other = order.getOrNull(from + by) ?: return
        val next = sections.toMutableList()
        val moving = next.removeAt(next.indexOfFirst { it.key == key })
        next.add(next.indexOfFirst { it.key == other.key } + if (by > 0) 1 else 0, moving)
        sections = next
        dirty = true
    }

    /**
     * Why this template cannot be saved yet, as a key and the section to open.
     *
     * The service refuses a section or a field with no name, so all three
     * checks are made here — a refusal from the service names neither which
     * section nor which field.
     */
    fun problem(): Problem? {
        if (enabled.isEmpty()) return Problem("enable_a_section", "", null)
        sections.firstOrNull { it.label.isBlank() }?.let {
            return Problem("section_title_required", "", it.key)
        }
        sections.firstOrNull { section -> section.fields.any { it.label.isBlank() } }?.let {
            return Problem("unnamed_field", it.label, it.key)
        }
        return null
    }

    private fun newSection(type: String, label: (String) -> String): TemplateSection {
        // ⚠ An allocator, not repeated `nextKey` calls: `nextKey` reads the
        // sections as they are, and the new ones are not in them yet — so
        // two calls in a row would both answer `f_1` and the section would
        // carry two fields under one key.
        val keys = keyAllocator()
        val base = TemplateSection(
            key = keys("custom"),
            label = label("new_section"),
            enabled = true,
            isCustom = true,
        )
        val field = { text: String, fieldType: String -> newField(text, fieldType).copy(key = keys("f")) }
        return when (type) {
            "notes" -> base.copy(
                sectionType = "notes",
                label = label("notes"),
                fields = listOf(field(label("notes"), "textarea")),
            )
            "section" -> base.copy(
                sectionType = "section",
                fields = listOf(
                    field(label("field_n") + " 1", "text"),
                    field(label("field_n") + " 2", "text"),
                ),
            )
            // The Sections menu's Custom: one notes field.
            else -> base.copy(fields = listOf(field(label("notes"), "textarea")))
        }
    }

    /**
     * Hands out keys nothing in this template uses, remembering what it has
     * already given out in this batch.
     */
    fun keyAllocator(): (String) -> String {
        val taken = sections.flatMapTo(mutableSetOf()) { section ->
            section.fields.map { it.key } + section.key
        }
        return { prefix ->
            var n = 1
            while ("${prefix}_$n" in taken) n++
            "${prefix}_$n".also(taken::add)
        }
    }

    /**
     * A blank field with a key nothing in this template uses.
     *
     * ⚠ Unique within the TEMPLATE, not within the session. A session
     * counter would hand out `f_1` again the next time the app ran, and a
     * template that already had an `f_1` would gain a second field under
     * one key — which the editor treats as one field and the service stores
     * as one value.
     */
    fun blankField(label: String): TemplateField = newField(label, "text").copy(key = keyAllocator()("f"))

    /** What is wrong, which section it is in, and whose label to name. */
    data class Problem(val key: String, val arg: String, val sectionKey: String?)
}

/**
 * A blank field, ready to be named.
 *
 * Its key is replaced by the draft's own allocator wherever it
 * is added to one, so nothing here has to know what is already taken.
 */
internal fun newField(label: String, type: String): TemplateField =
    TemplateField(key = "f_new", label = label, type = type)
