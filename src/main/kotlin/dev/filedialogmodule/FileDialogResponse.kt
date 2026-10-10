package dev.filedialogmodule

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.Properties

// Helpers for writing the response file in the format game-side code reads.
// Uses atomic write-then-rename with copy-and-delete fallback for filesystems
// where rename across volumes fails.
internal object FileDialogResponse {

    // Writes a response properties file for the given request token.
    // @param responseFile Destination response file. Parent directories are not created here;
    //   the caller provides a path under an existing module directory.
    // @param token Request token being answered. Echoed back for game-side matching.
    // @param status Result status: "ok", "cancel", or "error".
    // @param path Absolute path of the copied picked file when status is "ok", else null.
    // @param message Human-readable detail, notably for "error" and "cancel" cases.
    fun write(responseFile: File, token: String, status: String, path: String? = null, message: String = "") {
        val props = Properties().apply {
            setProperty("token", token)
            setProperty("status", status)
            setProperty("path", path ?: "")
            setProperty("message", message)
        }
        val tmp = File(responseFile.parentFile, responseFile.name + ".tmp")
        tmp.outputStream().use { out: OutputStream -> props.store(out, "FileDialogModule file dialog") }
        if (!tmp.renameTo(responseFile)) {
            tmp.copyTo(responseFile, overwrite = true)
            tmp.delete()
        }
    }

    // Copies a content stream to a destination file, creating parent directories as needed.
    // @param input Open stream of the picked document.
    // @param destination Target file under the picks directory.
    fun copyInto(input: InputStream, destination: File) {
        destination.parentFile?.mkdirs()
        destination.outputStream().use { out -> input.copyTo(out) }
    }
}

// Kept for callers referencing the pre-rename helper name.
internal typealias FileDialogModuleResponse = FileDialogResponse
