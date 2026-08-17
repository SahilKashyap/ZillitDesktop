package com.zillit.desktop.core.designsystem.component

/**
 * A square matrix of QR modules. `true` is a dark module.
 *
 * Deliberately not a bitmap: drawing the matrix directly stays crisp at any
 * size and needs no image decoding.
 */
class QrMatrix(val size: Int, private val modules: BooleanArray) {
    init {
        require(size > 0) { "QR matrix must have a positive size" }
        require(modules.size == size * size) { "expected ${size * size} modules, got ${modules.size}" }
    }

    operator fun get(x: Int, y: Int): Boolean = modules[y * size + x]
}

/**
 * Encodes [content] as a QR matrix.
 *
 * `expect`/`actual` so the encoder stays platform-specific while the drawing
 * code above it is shared.
 */
expect fun encodeQrCode(content: String): QrMatrix?
