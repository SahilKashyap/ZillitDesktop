package com.zillit.desktop.feature.email.domain

/**
 * A file the user chose, read into memory.
 *
 * In memory because attachments are small by policy — mail servers reject
 * anything past about 25 MB — and streaming would buy nothing while costing a
 * temp file to clean up. The picker enforces the ceiling; this type assumes it
 * already has.
 */
data class PickedFile(
    val name: String,
    val contentType: String,
    val bytes: ByteArray,
) {
    /** Never prints the bytes. */
    override fun toString(): String = "PickedFile(name=$name, size=${bytes.size})"

    // Identity is the file, not the array reference — `bytes` makes the
    // generated equals compare by reference, which is never what is wanted.
    override fun equals(other: Any?): Boolean =
        this === other || (other is PickedFile && name == other.name && bytes.contentEquals(other.bytes))

    override fun hashCode(): Int = 31 * name.hashCode() + bytes.contentHashCode()
}
