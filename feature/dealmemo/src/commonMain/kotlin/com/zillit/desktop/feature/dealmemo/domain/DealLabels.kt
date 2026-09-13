package com.zillit.desktop.feature.dealmemo.domain

import com.zillit.desktop.core.localization.Labels

/**
 * The web's label helpers (`accountHub/data/utils.js`), which every deal-memo
 * surface bottoms out in.
 *
 * `department_name` and `designation_name` are translation keys
 * (`line_producer_label`), so a label is the translation when there is one and
 * the humanised key when there is not — never the title-cased key while a
 * translation exists, which is the mistake the web fixed under ZL-21094.
 */
object DealLabels {

    private val UPPERCASE_WORDS = setOf("sfx", "vfx", "epk", "dit", "covid", "hod", "pa")

    /** The app's translation, or null — never the key back. */
    val translation: (String) -> String? = { key -> Labels.current.exact(key) }

    /**
     * `formatLabel`: a `_label` key through the translations first; otherwise
     * the key without `_label`, each underscore-separated word capitalised,
     * `sfx vfx epk dit covid hod pa` in capitals.
     */
    fun formatLabel(label: String?, translate: (String) -> String? = translation): String {
        if (label.isNullOrEmpty()) return ""
        if (label.endsWith("_label")) {
            val translated = translate(label)
            if (!translated.isNullOrEmpty() && translated != label) return translated
        }
        return label.removeSuffix("_label")
            .split('_')
            .joinToString(" ") { word ->
                when {
                    word in UPPERCASE_WORDS -> word.uppercase()
                    else -> word.replaceFirstChar { it.uppercase() }
                }
            }
    }
}

/** One department of the production's master list, keyed by its rate-card identifier. */
data class CatalogueDepartment(
    /** The Zillit-master `_id`. */
    val id: String?,
    /** `department_camera` — the key rate rows and deals store. */
    val identifier: String,
    /** A translation key, `camera_department_label`. */
    val nameKey: String,
    val designations: List<CatalogueDesignation> = emptyList(),
)

data class CatalogueDesignation(
    val id: String?,
    val identifier: String,
    val nameKey: String,
)

/** The departments master (`GET /v2/departments?designations=true`), in its own order. */
data class DepartmentCatalogue(val departments: List<CatalogueDepartment> = emptyList()) {

    private val designationDepartment: Map<String, CatalogueDepartment> by lazy {
        buildMap {
            departments.forEach { department ->
                department.designations.forEach { putIfAbsent(it.identifier, department) }
            }
        }
    }

    private val designationsById: Map<String, CatalogueDesignation> by lazy {
        buildMap { departments.forEach { d -> d.designations.forEach { putIfAbsent(it.identifier, it) } } }
    }

    val isEmpty: Boolean get() = departments.isEmpty()

    fun hasDesignation(identifier: String): Boolean = identifier in designationsById

    /** The first department that lists [designationIdentifier]. */
    fun departmentOf(designationIdentifier: String): CatalogueDepartment? = designationDepartment[designationIdentifier]

    fun department(identifier: String?): CatalogueDepartment? =
        identifier?.let { key -> departments.firstOrNull { it.identifier == key } }

    /**
     * `designationLabelByIdentifier`: the catalogue's name when it knows the
     * role, the identifier itself when it does not, an em dash for nothing.
     */
    fun designationLabel(identifier: String?, translate: (String) -> String? = DealLabels.translation): String {
        if (identifier.isNullOrEmpty()) return "—"
        return designationsById[identifier]?.let { DealLabels.formatLabel(it.nameKey, translate) } ?: identifier
    }
}
