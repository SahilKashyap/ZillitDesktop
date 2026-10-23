package com.zillit.desktop.feature.scriptsupervision.domain

/**
 * The looks group's values and rules — the web's `screens/looks/options.js`.
 *
 * ⚠ Every list here is TRANSCRIBED from the service's own validator
 * (`validators/v2/continuity.js`), in its display order. The service refuses a
 * value outside its enum, so renaming one to read better on the desktop would
 * make the form unable to save — which is why these are the service's words
 * and the catalogue carries the reader's.
 */
object Looks {

    /** A look's stage, from planned to wrapped. */
    val STATUSES: List<String> = listOf("planned", "established", "active", "broken", "wrapped")

    /** The three departments that keep looks; one folder, told apart by `department`. */
    val DEPARTMENTS: List<String> = listOf("costume", "hair", "makeup")

    val GARMENT_CATEGORIES: List<String> = listOf(
        "top",
        "bottom",
        "dress",
        "outerwear",
        "footwear",
        "headwear",
        "accessory",
        "undergarment",
        "jewelry",
        "other",
    )

    /** Where a garment came from — what wardrobe has to return, and to whom. */
    val GARMENT_SOURCES: List<String> = listOf("bought", "rented", "made", "pulled", "actor_own", "other")

    val GARMENT_CONDITIONS: List<String> = listOf("new", "good", "aged", "distressed", "damaged")

    val BOX_DEPARTMENTS: List<String> = listOf("costume", "hair", "makeup", "props", "set_dressing", "general")

    val BOX_STATUSES: List<String> = listOf("packed", "in_use", "wrapped", "returned", "lost")

    val PHOTO_CATEGORIES: List<String> = listOf(
        "wardrobe",
        "props",
        "hair_makeup",
        "set_dressing",
        "action",
        "reference",
        "vfx",
        "general",
    )

    /** What one line of a look's detail sheet is: a colour, a product, a note. */
    val DETAIL_TYPES: List<String> = listOf("color", "product", "note", "other")

    /**
     * A look as the department writes it on a tag: COSTUME#3.
     *
     * A look with no number falls back to its name, because an unnumbered look
     * still has to be nameable on a card.
     */
    fun code(look: Rec?): String {
        if (look == null) return ""
        val number = look.str("look_number")
        if (number.isBlank()) return look.str("name")
        return "${look.str("department").uppercase()}#$number"
    }

    /**
     * A garment's or prop's photos.
     *
     * Two generations of record meet here: a new one keeps a `photo_urls`
     * list, an older one a single `image_url` cover. Both read as a list, so
     * nothing on screen has to know which it is looking at.
     */
    fun photosOf(item: Rec?): List<String> {
        val list = item?.strings("photo_urls").orEmpty().filter { it.isNotBlank() }
        if (list.isNotEmpty()) return list
        return listOfNotNull(item?.str("image_url")?.takeIf { it.isNotBlank() })
    }

    /**
     * The cover to keep when the photo list changes.
     *
     * The old cover stays the cover while it is still in the list; otherwise
     * the first photo becomes it. Without this rule, removing any photo would
     * leave `image_url` pointing at a photo the record no longer has.
     */
    fun coverOf(item: Rec?, photos: List<String>): String {
        val old = item?.str("image_url").orEmpty()
        return if (old in photos) old else photos.firstOrNull().orEmpty()
    }

    /**
     * Natural order by [pick], so 2 comes before 10 and 1A before 1B.
     *
     * The web gets this from `localeCompare(…, { numeric: true })`; the number
     * and the rest are compared apart here, which is the same order for every
     * value this service stores in these fields.
     */
    fun byNatural(pick: (Rec) -> String): Comparator<Rec> = compareBy(
        { pick(it).takeWhile { c -> c.isDigit() }.toLongOrNull() ?: Long.MAX_VALUE },
        { pick(it) },
    )
}
