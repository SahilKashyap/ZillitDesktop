package com.zillit.desktop.feature.documentdistribution.domain

/** How big the stamp is drawn, relative to the auto-fit size the engine picks. */
enum class WatermarkSize(val wire: String, val scale: Double) {
    Small("small", SMALL_SCALE),
    Medium("medium", 1.0),
    Large("large", LARGE_SCALE),
    ;

    companion object {
        fun from(wire: String?): WatermarkSize =
            entries.firstOrNull { it.wire == wire?.lowercase() } ?: Large
    }
}

/** What a watermark line says. */
enum class WatermarkLine(val wire: String) {
    /** The recipient's own name, personalised per copy by the server. */
    RecipientName("name"),
    Custom("custom"),
    None("none"),
    ;

    companion object {
        fun from(wire: String?): WatermarkLine =
            entries.firstOrNull { it.wire == wire?.lowercase() } ?: None
    }
}

/**
 * The watermark wizard's configuration.
 *
 * Ported from the web's `utils/watermarkConfig.js`, whose defaults are load
 * bearing: **every watermark-capable attachment is stamped unless the sender
 * opts out**, and the default stamp is the recipient's name, large, grey, at
 * 40% (ZL-19547). Shipping a different default would silently change what
 * leaves a production.
 *
 * Sent to the server as a config object rather than rendered text when
 * composing email, because only the server knows each recipient — see
 * [render], which exists for the one flow that *does* pre-render (batch zip
 * download, where the sender picks the names themselves).
 */
data class WatermarkStyle(
    val line1: WatermarkLine = WatermarkLine.RecipientName,
    val line1Custom: String = "",
    val line2: WatermarkLine = WatermarkLine.None,
    val line2Custom: String = "",
    val size: WatermarkSize = WatermarkSize.Large,
    /** Hex, as the stamping engines take it. */
    val color: String = DEFAULT_COLOR,
    val opacity: Double = DEFAULT_OPACITY,
) {

    /**
     * The literal text for a given set of recipients.
     *
     * Mirrors `renderWatermarkText`: one recipient stamps their name, several
     * stamp a count, none stamps the placeholder. Two lines joined by a
     * newline, which every engine splits on and caps at two.
     */
    fun render(recipients: List<Recipient>): String {
        val token = when {
            recipients.isEmpty() -> PLACEHOLDER
            recipients.size == 1 ->
                recipients.first().name.ifBlank { recipients.first().email }.ifBlank { PLACEHOLDER }

            else -> "${recipients.size} RECIPIENTS"
        }
        val first = if (line1 == WatermarkLine.RecipientName) token else line1Custom.trim()
        val second = if (line2 == WatermarkLine.Custom) line2Custom.trim() else ""
        return listOf(first, second).filter { it.isNotBlank() }.joinToString("\n")
    }

    /** "Name / Confidential" — the composer's one-line summary of the stamp. */
    fun summary(): String {
        val first = if (line1 == WatermarkLine.RecipientName) "Name" else line1Custom.ifBlank { "—" }
        val second = if (line2 == WatermarkLine.Custom) line2Custom else ""
        return if (second.isBlank()) first else "$first / $second"
    }

    companion object {
        const val DEFAULT_COLOR = "#6b7280"
        const val DEFAULT_OPACITY = 0.4

        /** Line 2 draws at this share of line 1, in every engine. */
        const val LINE2_SCALE = 0.7

        private const val PLACEHOLDER = "RECIPIENT"

        /** The swatches the picker offers; grey leads because it is the default. */
        val COLORS = listOf(
            DEFAULT_COLOR, "#dc2626", "#111827", "#2563eb",
            "#16a34a", "#ea580c", "#7c3aed", "#0891b2",
        )
    }
}

private const val SMALL_SCALE = 0.65
private const val LARGE_SCALE = 1.5

/**
 * The production's shared defaults for the wizard's Size, Colour and
 * Opacity controls (`/api/v2/document-distribution/watermark-settings`).
 *
 * One set per project, seen by everyone using the tool. Until someone saves,
 * the server answers the built-in values with [isDefault] true — which are
 * exactly [WatermarkStyle]'s own defaults, so a project that has never saved
 * looks the same as one whose settings failed to load.
 */
data class WatermarkSettings(
    val size: WatermarkSize = WatermarkSize.Large,
    val color: String = WatermarkStyle.DEFAULT_COLOR,
    val opacity: Double = WatermarkStyle.DEFAULT_OPACITY,
    /** True until the project saves for the first time. */
    val isDefault: Boolean = true,
    /** Project user id of the last person to save; null before the first save. */
    val updatedBy: String? = null,
    /** Epoch ms of the last save; 0 before the first. */
    val updated: Long = 0,
) {
    companion object {
        val BuiltIn = WatermarkSettings()
    }
}

/**
 * A partial save: only the fields sent change on the server, so a save
 * carries what the sender actually touched and two people changing different
 * controls at once do not overwrite each other.
 */
data class WatermarkSettingsPatch(
    val size: WatermarkSize? = null,
    val color: String? = null,
    val opacity: Double? = null,
) {
    val isEmpty: Boolean get() = size == null && color == null && opacity == null
}

/** This style, with the appearance the production has agreed on. */
fun WatermarkStyle.withDefaults(settings: WatermarkSettings): WatermarkStyle =
    copy(size = settings.size, color = settings.color, opacity = settings.opacity)

/**
 * The appearance fields of this style that differ from [settings] — what a
 * wizard save sends. Colour compares case-insensitively because the server
 * stores `#RRGGBB` uppercase and the swatches are lowercase.
 */
fun WatermarkStyle.patchAgainst(settings: WatermarkSettings): WatermarkSettingsPatch = WatermarkSettingsPatch(
    size = size.takeIf { it != settings.size },
    color = color.takeIf { !it.equals(settings.color, ignoreCase = true) },
    opacity = opacity.takeIf { it != settings.opacity },
)
