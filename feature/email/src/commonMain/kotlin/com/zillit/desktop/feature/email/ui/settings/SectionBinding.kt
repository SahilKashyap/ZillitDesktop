package com.zillit.desktop.feature.email.ui.settings

/**
 * The state and handler of one settings sub-page, bundled so the screen's
 * parameter list stays readable — four of these would otherwise be eight
 * positional arguments of near-identical shape.
 */
class SectionBinding<S, E>(
    val state: S,
    val onEvent: (E) -> Unit,
)
