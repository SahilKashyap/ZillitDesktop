package com.zillit.desktop.feature.email.domain

/**
 * Undo for the composer.
 *
 * The editor's field has no undo of its own — it is a controlled component
 * whose value round-trips through [RichText] — so the history lives here,
 * beside the document it snapshots, where it can be tested without a UI.
 *
 * Immutable on purpose: the editor holds one of these in state and replaces
 * it, the same way it replaces the document.
 */
data class EditHistory(
    val undos: List<RichText> = emptyList(),
    val redos: List<RichText> = emptyList(),
) {
    val canUndo: Boolean get() = undos.isNotEmpty()
    val canRedo: Boolean get() = redos.isNotEmpty()

    /**
     * Remembers [current] as the state to return to, called before an edit is
     * applied. A new edit forks the timeline, so redo is cleared — the
     * behaviour of every editor since redo existed.
     */
    fun record(current: RichText): EditHistory {
        if (undos.lastOrNull() == current) return copy(redos = emptyList())
        return EditHistory(undos = (undos + current).takeLast(LIMIT), redos = emptyList())
    }

    /** The document to restore in place of [current], with the history after it. */
    fun undo(current: RichText): Pair<EditHistory, RichText>? {
        val restored = undos.lastOrNull() ?: return null
        return EditHistory(undos.dropLast(1), redos + current) to restored
    }

    /** The document to restore in place of [current], with the history after it. */
    fun redo(current: RichText): Pair<EditHistory, RichText>? {
        val restored = redos.lastOrNull() ?: return null
        return EditHistory(undos + current, redos.dropLast(1)) to restored
    }

    private companion object {
        /**
         * Snapshots are one per keystroke; a long message needs room, but the
         * list must not grow with the session.
         */
        const val LIMIT = 200
    }
}
