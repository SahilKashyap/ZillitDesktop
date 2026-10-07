package com.zillit.desktop.feature.selectstills.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.zillit.desktop.core.common.ZillitLog
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image

actual fun decodeStillBitmap(bytes: ByteArray): ImageBitmap? =
    runCatching { Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()

/**
 * macOS's Open panel in directory mode (`apple.awt.fileDialogForDirectories`),
 * which is what AWT offers for a folder; elsewhere a Swing chooser restricted
 * to directories. Both run on the AWT thread, so this suspends on IO.
 */
actual suspend fun chooseStillsFolder(title: String): String? = withContext(Dispatchers.IO) {
    runCatching {
        if (isMac) {
            System.setProperty(MAC_DIRECTORIES, "true")
            try {
                val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD)
                dialog.isMultipleMode = false
                dialog.isVisible = true
                dialog.files.firstOrNull()?.absolutePath
            } finally {
                System.setProperty(MAC_DIRECTORIES, "false")
            }
        } else {
            val chooser = javax.swing.JFileChooser().apply {
                dialogTitle = title
                fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY
                isMultiSelectionEnabled = false
            }
            if (chooser.showOpenDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) {
                chooser.selectedFile?.takeIf(File::isDirectory)?.absolutePath
            } else {
                null
            }
        }
    }.getOrElse { throwable ->
        ZillitLog.w("Stills") { "folder chooser unavailable: ${throwable::class.simpleName}" }
        null
    }
}

private val isMac: Boolean get() = System.getProperty("os.name").orEmpty().lowercase().contains("mac")

private const val MAC_DIRECTORIES = "apple.awt.fileDialogForDirectories"
