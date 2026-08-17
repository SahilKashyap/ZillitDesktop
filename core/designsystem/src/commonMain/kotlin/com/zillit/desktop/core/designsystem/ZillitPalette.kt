package com.zillit.desktop.core.designsystem

import androidx.compose.ui.graphics.Color

/**
 * Raw brand colours, transcribed from the web app's `tailwind.config.js`.
 *
 * **Nothing outside this file may declare a `Color(0x…)`.** Screens read
 * semantic roles from [ZillitColors] instead, which is what makes a single
 * theme change propagate everywhere and what keeps light and dark in step.
 */
internal object ZillitPalette {

    // -- brand -------------------------------------------------------------
    val Orange = Color(0xFFF99300)          // tailwind `primary`
    val OrangeHover = Color(0xFFE08503)     // tailwind `outlook.hover`
    val OrangeSoft = Color(0xFFFFF7EB)      // tailwind `outlook.selected`
    val Navy = Color(0xFF04327D)            // tailwind `accent`

    /**
     * The deeper orange behind the sign-in page (web `Login.jsx`, `#ff6d00`).
     *
     * Distinct from [Orange], which is the UI primary. Both appear on that page
     * — the field behind the card, and the step badges on it.
     */
    val OrangeDeep = Color(0xFFFF6D00)

    /** The sign-in card (web `bg-[#121b21]`). Fixed in both themes — see `QrLoginPage`. */
    val LoginCard = Color(0xFF121B21)

    // -- notice board ------------------------------------------------------
    //
    // Android's `chat_text_layout.xml`: a blue-tinted card with a blue author
    // name. Transcribed rather than reinterpreted — crew move between the phone
    // and the desktop during a shoot and the board should read the same.

    /** `@color/blue` — the author line. */
    val NoticeAuthor = Color(0xFF3F51B5)

    /** The web's unit-chat bubble: Tailwind gray-700. */
    val NoticeBubble = Color(0xFF374151)

    /** `@color/light_blue`, 7.5% of #0B3A82 — the card tint. */
    val NoticeCardLight = Color(0x130B3A82)

    /** The same tint lifted for dark mode, where 7.5% of navy is invisible. */
    val NoticeCardDark = Color(0x2A5B8AD6)
    val Gold = Color(0xFFC9930E)

    // -- neutrals, light ---------------------------------------------------
    val White = Color(0xFFFFFFFF)
    val Grey50 = Color(0xFFF8F9FB)          // tailwind `bg`
    val Grey100 = Color(0xFFF0F2F5)         // tailwind `bg.3`
    val Grey200 = Color(0xFFE5E8ED)         // tailwind `bg.4`
    val Grey300 = Color(0xFFE2E4E9)         // tailwind `border`
    val Grey400 = Color(0xFFD1D5DB)         // tailwind `border.hi`
    val Grey500 = Color(0xFF9CA3AF)         // tailwind `txt.muted`
    val Grey600 = Color(0xFF6B7280)         // tailwind `txt.dim`
    val Grey900 = Color(0xFF1A1E2C)         // tailwind `txt`

    // -- neutrals, dark ----------------------------------------------------
    // Seeded from the web app's Account Hub dark tokens and extended into a
    // full ramp — the web only needed a handful because it does not ship a
    // complete dark theme.
    val Dark900 = Color(0xFF14161B)
    val Dark800 = Color(0xFF1A1D23)         // tailwind account-hub dark bg
    val Dark700 = Color(0xFF23272F)
    val Dark600 = Color(0xFF2C313A)
    val Dark500 = Color(0xFF3A404B)
    val Dark400 = Color(0xFF4C5361)
    val DarkText = Color(0xFFF3F4F6)
    val DarkTextDim = Color(0xFFA8AEBB)

    // -- status ------------------------------------------------------------
    val Red = Color(0xFFD92D20)
    val RedSoft = Color(0xFFFEE4E2)
    val RedDark = Color(0xFFF97066)
    val Green = Color(0xFF12B76A)
    val GreenSoft = Color(0xFFD1FADF)
    val GreenDark = Color(0xFF32D583)
    val Amber = Color(0xFFF79009)
    val AmberSoft = Color(0xFFFEF0C7)
    val Blue = Color(0xFF2E90FA)
    val BlueSoft = Color(0xFFD1E9FF)

    // -- finance status ----------------------------------------------------
    //
    // The Account Hub statuses do not fit four semantic slots: a cash batch can
    // be escalated (not an error), a card can be in transit (not a warning),
    // and a float can be spent (not a success). The web gives each its own hue
    // — `cardExpenses/lib/constants.js` STATUS_COLORS names teal, purple and
    // gold beside the four — so those three are transcribed rather than
    // collapsed onto danger/warning, which is what makes an escalation read
    // differently from a rejection at a glance.
    val Violet = Color(0xFF7C3AED)
    val VioletSoft = Color(0xFFF1EBFF)
    val VioletDark = Color(0xFFA78BFA)
    val Teal = Color(0xFF0C7166)
    val TealSoft = Color(0xFFE8F7F4)
    val TealDark = Color(0xFF2DD4BF)
    val GoldSoft = Color(0xFFFDF2E2)
    val GoldDark = Color(0xFFFBBF24)
}
