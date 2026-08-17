package com.zillit.desktop.core.designsystem

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Semantic colour roles.
 *
 * Screens name *intent* (`textMuted`, `danger`, `tabActiveBackground`), never a
 * shade. Adding a theme means adding one [ZillitColors] instance; no screen
 * changes. This is the mechanism behind full light/dark support — a composable
 * that reads only these roles is correct in both without a single conditional.
 *
 * Material 3's own `ColorScheme` is still populated (see `Theme.kt`) so stock
 * Material components inherit the theme too, but Zillit components read here.
 */
/**
 * The marks on file-type chips.
 *
 * Their own scale rather than the semantic roles above, because those carry
 * meanings a file type must not borrow: a PDF is not an error, and a
 * spreadsheet is not a success. Named by hue for exactly that reason — there is
 * no intent to name, only "the red one", and which family wears which is the
 * caller's business.
 *
 * Every value is a mark on [ZillitColors.surfaceSunken], so the two schemes
 * carry different shades of the same hues: deep enough to read on a pale well,
 * light enough to read on a dark one.
 */
@Immutable
data class ZillitFileColors(
    val red: Color,
    val blue: Color,
    val green: Color,
    val amber: Color,
    val purple: Color,
    val pink: Color,
    val indigo: Color,
    val teal: Color,
    val neutral: Color,
)

@Immutable
data class ZillitColors(
    val isDark: Boolean,

    // surfaces, back to front
    val canvas: Color,           // window background, behind everything
    val surface: Color,          // cards, panels, the workspace body
    val surfaceRaised: Color,    // menus, popovers, floating windows
    val surfaceSunken: Color,    // wells, table headers, the rail
    val surfaceHover: Color,
    val surfaceSelected: Color,

    // text
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val textOnAccent: Color,
    val textDisabled: Color,

    // lines
    val border: Color,
    val borderStrong: Color,
    val divider: Color,
    val focusRing: Color,

    // brand / interaction
    val accent: Color,
    val accentHover: Color,
    val accentPressed: Color,
    val accentSoft: Color,
    val accentText: Color,
    val secondary: Color,

    // status
    val danger: Color,
    val dangerSoft: Color,
    val success: Color,
    val successSoft: Color,
    val warning: Color,
    val warningSoft: Color,
    val info: Color,
    val infoSoft: Color,

    /**
     * Three further status hues, for the finance tools only.
     *
     * Account Hub statuses outnumber the four semantic slots above and do not
     * map onto them: an *escalated* batch is not an error, a card *in transit*
     * is not a warning, and a *spent* float is not a success. Rendering them
     * through danger/warning made those states indistinguishable from real
     * problems on a queue an accountant scans at speed. See [ZillitPalette].
     */
    val violet: Color,
    val violetSoft: Color,
    val teal: Color,
    val tealSoft: Color,
    val gold: Color,
    val goldSoft: Color,

    // workspace chrome (plan §3)
    val tabBar: Color,
    val tabActive: Color,
    val tabInactive: Color,
    val tabHover: Color,
    val tabIndicator: Color,
    val railBackground: Color,
    val railActive: Color,
    val windowShadow: Color,
    val scrim: Color,

    /**
     * The bar on a panel that floats over the app — the mail composer's.
     *
     * Dark in both themes on purpose. A floating panel has to read as being
     * *in front of* what it covers, and in light mode nothing else on screen
     * is dark, so a dark bar is the cheapest way to say so at a glance. In
     * dark mode it is raised above the surface instead, for the same reason.
     */
    val titleBar: Color,
    val titleBarText: Color,

    // -- sign-in ------------------------------------------------------------
    //
    // Identical in both themes, deliberately. The sign-in page is a brand
    // splash shown before any preference is loaded: it is dark-on-orange on the
    // web, the user has not chosen a theme at that point, and flipping it to a
    // light card would make the desktop client not look like Zillit. Declared as
    // roles rather than raw colours so the rest of the design-system rules —
    // no `Color(0x…)` outside the palette — still hold.
    val signInBackdrop: Color,
    val signInCard: Color,
    val signInStepBadge: Color,
    val signInLink: Color,
    val signInText: Color,
    val signInTextMuted: Color,

    // -- notice board -------------------------------------------------------
    val noticeCard: Color,
    val noticeAuthor: Color,
    /**
     * The web's notice bubble (`#374151`), identical in both themes — the
     * bubble is a self-contained dark surface with white text, like a photo,
     * not themed furniture.
     */
    val noticeBubble: Color,

    /** Marks for file-type chips; see [ZillitFileColors]. */
    val files: ZillitFileColors,
)

internal val LightColors = ZillitColors(
    isDark = false,

    canvas = ZillitPalette.Grey100,
    surface = ZillitPalette.White,
    surfaceRaised = ZillitPalette.White,
    surfaceSunken = ZillitPalette.Grey50,
    surfaceHover = ZillitPalette.Grey100,
    surfaceSelected = ZillitPalette.OrangeSoft,

    textPrimary = ZillitPalette.Grey900,
    textSecondary = ZillitPalette.Grey600,
    textMuted = ZillitPalette.Grey500,
    textOnAccent = ZillitPalette.White,
    textDisabled = ZillitPalette.Grey400,

    border = ZillitPalette.Grey300,
    borderStrong = ZillitPalette.Grey400,
    divider = ZillitPalette.Grey200,
    focusRing = ZillitPalette.Orange,

    accent = ZillitPalette.Orange,
    accentHover = ZillitPalette.OrangeHover,
    accentPressed = ZillitPalette.Gold,
    accentSoft = ZillitPalette.OrangeSoft,
    accentText = ZillitPalette.OrangeHover,
    secondary = ZillitPalette.Navy,

    danger = ZillitPalette.Red,
    dangerSoft = ZillitPalette.RedSoft,
    success = ZillitPalette.Green,
    successSoft = ZillitPalette.GreenSoft,
    warning = ZillitPalette.Amber,
    warningSoft = ZillitPalette.AmberSoft,
    info = ZillitPalette.Blue,
    infoSoft = ZillitPalette.BlueSoft,

    violet = ZillitPalette.Violet,
    violetSoft = ZillitPalette.VioletSoft,
    teal = ZillitPalette.Teal,
    tealSoft = ZillitPalette.TealSoft,
    gold = ZillitPalette.Gold,
    goldSoft = ZillitPalette.GoldSoft,

    tabBar = ZillitPalette.Grey50,
    tabActive = ZillitPalette.White,
    tabInactive = ZillitPalette.Grey100,
    tabHover = ZillitPalette.Grey200,
    tabIndicator = ZillitPalette.Orange,
    railBackground = ZillitPalette.White,
    railActive = ZillitPalette.OrangeSoft,
    windowShadow = Color(0x1A000000),
    scrim = Color(0x66000000),
    titleBar = ZillitPalette.Dark500,
    titleBarText = ZillitPalette.DarkText,
    signInBackdrop = ZillitPalette.OrangeDeep,
    signInCard = ZillitPalette.LoginCard,
    signInStepBadge = ZillitPalette.Orange,
    signInLink = ZillitPalette.Orange,
    signInText = ZillitPalette.White,
    signInTextMuted = ZillitPalette.DarkTextDim,
    noticeCard = ZillitPalette.NoticeCardLight,
    noticeAuthor = ZillitPalette.NoticeAuthor,
    // A card on the light canvas, like every other card in the app. It used to
    // be the dark bubble in both themes — a slab of navy on a pale page, which
    // read as a component that had not been themed yet.
    noticeBubble = ZillitPalette.White,

    // Deep enough to read as a mark on a pale well.
    files = ZillitFileColors(
        red = Color(0xFFD03A3A),
        blue = Color(0xFF2563C9),
        green = Color(0xFF11804A),
        amber = Color(0xFF9A6100),
        purple = Color(0xFF7A3BC4),
        pink = Color(0xFFBE3179),
        indigo = Color(0xFF3F51B5),
        teal = Color(0xFF0E7C86),
        neutral = ZillitPalette.Grey600,
    ),
)

internal val DarkColors = ZillitColors(
    isDark = true,

    canvas = ZillitPalette.Dark900,
    surface = ZillitPalette.Dark800,
    surfaceRaised = ZillitPalette.Dark700,
    surfaceSunken = ZillitPalette.Dark900,
    surfaceHover = ZillitPalette.Dark600,
    surfaceSelected = Color(0x33F99300),

    textPrimary = ZillitPalette.DarkText,
    textSecondary = ZillitPalette.DarkTextDim,
    textMuted = ZillitPalette.Grey500,
    textOnAccent = ZillitPalette.Dark900,
    textDisabled = ZillitPalette.Dark400,

    border = ZillitPalette.Dark500,
    borderStrong = ZillitPalette.Dark400,
    divider = ZillitPalette.Dark600,
    focusRing = ZillitPalette.Orange,

    accent = ZillitPalette.Orange,
    accentHover = Color(0xFFFFA726),
    accentPressed = ZillitPalette.Gold,
    accentSoft = Color(0x29F99300),
    accentText = Color(0xFFFFB74D),
    secondary = Color(0xFF6D9EEB),

    danger = ZillitPalette.RedDark,
    dangerSoft = Color(0x33D92D20),
    success = ZillitPalette.GreenDark,
    successSoft = Color(0x3312B76A),
    warning = ZillitPalette.Amber,
    warningSoft = Color(0x33F79009),
    info = ZillitPalette.Blue,
    infoSoft = Color(0x332E90FA),

    violet = ZillitPalette.VioletDark,
    violetSoft = Color(0x337C3AED),
    teal = ZillitPalette.TealDark,
    tealSoft = Color(0x330C7166),
    gold = ZillitPalette.GoldDark,
    goldSoft = Color(0x33C9930E),

    tabBar = ZillitPalette.Dark900,
    tabActive = ZillitPalette.Dark700,
    tabInactive = ZillitPalette.Dark800,
    tabHover = ZillitPalette.Dark600,
    tabIndicator = ZillitPalette.Orange,
    railBackground = ZillitPalette.Dark800,
    railActive = Color(0x29F99300),
    windowShadow = Color(0x66000000),
    scrim = Color(0x99000000),
    // Raised rather than darker: the surface under it is already near-black,
    // so a darker bar would read as a hole instead of a header.
    titleBar = ZillitPalette.Dark600,
    titleBarText = ZillitPalette.DarkText,
    signInBackdrop = ZillitPalette.OrangeDeep,
    signInCard = ZillitPalette.LoginCard,
    signInStepBadge = ZillitPalette.Orange,
    signInLink = ZillitPalette.Orange,
    signInText = ZillitPalette.White,
    signInTextMuted = ZillitPalette.DarkTextDim,
    noticeCard = ZillitPalette.NoticeCardDark,
    noticeAuthor = ZillitPalette.NoticeAuthor,
    // Unchanged: this is the colour the bubble was designed in, and against the
    // dark canvas it already sits a step above the page the way a card should.
    noticeBubble = ZillitPalette.NoticeBubble,

    // The same hues lifted for a dark well. Straight reuse of the light set
    // reads as mud at these sizes — a 16dp glyph has no area to carry a dark
    // colour.
    files = ZillitFileColors(
        red = Color(0xFFFF7B72),
        blue = Color(0xFF6BA8FF),
        green = Color(0xFF4ECB8B),
        amber = Color(0xFFE3B341),
        purple = Color(0xFFC49BFF),
        pink = Color(0xFFFF87C8),
        indigo = Color(0xFF9AA6FF),
        teal = Color(0xFF4DD0D8),
        neutral = ZillitPalette.Grey500,
    ),
)

/**
 * Fails loudly rather than silently rendering unthemed colours, which is easy
 * to miss in one theme and obvious in the other.
 */
val LocalZillitColors = staticCompositionLocalOf<ZillitColors> {
    error("No ZillitColors — wrap the content in ZillitTheme { }")
}
