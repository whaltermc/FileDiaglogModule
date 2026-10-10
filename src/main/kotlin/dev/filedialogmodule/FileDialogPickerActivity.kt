package dev.filedialogmodule

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import java.io.File

// Transparent picker host for one FileDialogModule file-dialog request.
// Hands the request off to the system document picker via
// Storage Access Framework and writes the FileDialogModule response file when the user
// picks a file, cancels, supplies a malformed request, or the activity ends
// without a picker result.
// Manifest registration:
// - Declared in this module's AndroidManifest.xml.
// - Merged into the host app automatically.
// - Uses a translucent no-history single-task activity with no UI of its own.
// Response contract written via FileDialogResponse.write:
// - status="ok" with path set to the copied file under the picks directory.
// - status="cancel" when the user dismisses the picker or the activity ends bare.
// - status="error" with message when the selection cannot be opened or copied.
// See FileDialogWatcher for request polling and response file locations.
class FileDialogPickerActivity : ComponentActivity() {

    companion object {
        // Log tag for picker failures and malformed requests.
        private const val TAG = "FileDialogPicker"

        // Request token echoed back in the response file for game-side matching.
        const val EXTRA_TOKEN = "token"

        // Raw filters string ("ext1;ext2", comma-separated, or "*" for all files).
        const val EXTRA_FILTERS = "filters"

        // Human-readable description of the request. Informational only.
        const val EXTRA_DESCRIPTION = "description"

        // Absolute path of the picks directory where selections are copied.
        const val EXTRA_PICKS_DIR = "picksDir"

        // Absolute path of the response properties file to write.
        const val EXTRA_RESPONSE_FILE = "responseFile"
    }

    // Request token for this invocation. Empty means no valid request was supplied.
    private lateinit var token: String

    // Response file to write exactly once per request.
    private lateinit var responseFile: File

    // Directory receiving copies of picked documents.
    private lateinit var picksDir: File

    // System document picker launcher.
    // Maps a null Uri to a cancel response, a non-null Uri to handlePicked,
    // then finishes the activity in either case.
    private val openDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) {
            respond("cancel")
        } else {
            handlePicked(uri)
        }
        finish()
    }

    // Validates intent extras and launches the system picker.
    // Expects EXTRA_TOKEN, EXTRA_RESPONSE_FILE, and EXTRA_PICKS_DIR.
    // Finishes immediately without responding when any required extra is missing,
    // since no valid response file target exists in that case.
    // @param savedInstanceState Unused; activity holds no persistent UI state.
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        token = intent.getStringExtra(EXTRA_TOKEN).orEmpty()
        val responsePath = intent.getStringExtra(EXTRA_RESPONSE_FILE)
        val picksPath = intent.getStringExtra(EXTRA_PICKS_DIR)

        if (token.isEmpty() || responsePath.isNullOrEmpty() || picksPath.isNullOrEmpty()) {
            Log.w(TAG, "missing extras, nothing to respond to")
            finish()
            return
        }
        responseFile = File(responsePath)
        picksDir = File(picksPath)

        val filters = intent.getStringExtra(EXTRA_FILTERS) ?: "*"
        openDocument.launch(mimeTypesFor(filters))
    }

    // Converts an extension filter list to system picker MIME types.
    // The system picker filters by MIME type rather than extension. Each entry is split
    // on ";" or ",", trimmed, stripped of a leading dot, lowercased, and resolved via
    // MimeTypeMap.getMimeTypeFromExtension. Distinct resolved types are returned.
    // Unmapped input such as mod-specific .schem has no registered MIME type and cannot
    // be expressed to the system picker, so blank, "*", or fully unmapped filters
    // fall back to a wildcard MIME type showing every file.
    // @param filters Raw filters string from the request, or "*" for no filtering.
    // @return MIME type array suitable for OpenDocument.launch.
    private fun mimeTypesFor(filters: String): Array<String> {
        if (filters.isBlank() || filters == "*") return arrayOf("*/*")
        val map = MimeTypeMap.getSingleton()
        val mimeTypes = filters.split(";", ",")
            .map { it.trim().removePrefix(".") }
            .filter { it.isNotEmpty() }
            .mapNotNull { map.getMimeTypeFromExtension(it.lowercase()) }
            .distinct()
        return if (mimeTypes.isEmpty()) arrayOf("*/*") else mimeTypes.toTypedArray()
    }

    // Copies the picked document into picksDir and responds ok.
    // Destination name is prefixed with token and sanitized via sanitize.
    // Responds error when the document cannot be opened or an exception occurs.
    // @param uri Content URI returned by the system picker.
    private fun handlePicked(uri: Uri) {
        try {
            val name = displayNameOf(uri) ?: "import"
            val destination = File(picksDir, "$token-${sanitize(name)}")
            contentResolver.openInputStream(uri)?.use { input ->
                FileDialogResponse.copyInto(input, destination)
            } ?: run {
                respond("error", message = "could not open the selected document")
                return
            }
            respond("ok", path = destination.absolutePath)
        } catch (t: Throwable) {
            Log.e(TAG, "failed handling picked document", t)
            respond("error", message = t.message ?: t.javaClass.simpleName)
        }
    }

    // Resolves a display name for a content URI.
    // Prefers OpenableColumns.DISPLAY_NAME from the content resolver,
    // falling back to the URI path segment when unavailable.
    // @param uri Content URI to name.
    // @return Display name, or null when neither source yields one.
    private fun displayNameOf(uri: Uri): String? {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val col = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (col >= 0 && cursor.moveToFirst()) return cursor.getString(col)
        }
        return uri.lastPathSegment
    }

    // Sanitizes a file name for safe storage under picksDir.
    // Replaces characters outside A-Z a-z 0-9 . _ - with _ and keeps the
    // trailing 150 characters to bound length while preserving the extension.
    // @param name Raw display name.
    // @return Filesystem-safe name.
    private fun sanitize(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._-]"), "_").takeLast(150)

    // Writes the response file for the current token.
    // No-op when responseFile was never initialized from a valid intent.
    // @param status "ok", "cancel", or "error".
    // @param path Copied file path for "ok", else null.
    // @param message Detail for "error" and "cancel" cases.
    private fun respond(status: String, path: String? = null, message: String = "") {
        if (!::responseFile.isInitialized) return
        FileDialogResponse.write(responseFile, token, status, path, message)
    }

    // Guarantees the game never waits a full timeout on activity teardown.
    // When the picker callback never fired and no response file exists yet,
    // writes a cancel response indicating the activity ended without a result.
    override fun onDestroy() {
        super.onDestroy()
        if (!responseFile.isFile) {
            respond("cancel", message = "picker activity ended without a result")
        }
    }
}
