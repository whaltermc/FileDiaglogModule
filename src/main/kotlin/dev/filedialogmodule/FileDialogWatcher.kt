package dev.filedialogmodule

import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.Properties

// Launcher-side endpoint of the FileDialogModule file-dialog protocol.
// Protocol overview:
// - The game process writes a request file at
//   <gameDir>/.filedialogmodule/filedialogmodule-file-dialog.request.properties
//   with keys token, defaultPath, description, filters ("ext1;ext2" or "*").
// - This watcher polls for that file while a game instance is running.
// - On a new token it consumes the request file and starts
//   FileDialogPickerActivity via an explicit Intent.
// - The activity writes a response file at
//   <gameDir>/.filedialogmodule/filedialogmodule-file-dialog.response.properties
//   with keys token, status ("ok", "cancel", "error"), path, message.
// Lifecycle:
// - Create one instance per running game directory.
// - Call start() when the game process starts, stop() when it exits.
// - start() is idempotent and never starts a second polling loop.
// - Instances are not safe for sharing one gameDir between two watchers concurrently.
// Threading:
// - Polling runs on Dispatchers.IO.
// - picksDir holds copies of picked documents so a plain JVM game process can read them by path.
// @param appContext Application context used to start FileDialogPickerActivity.
// @param gameDir Directory the game process uses as its user.dir.
class FileDialogWatcher(
    private val appContext: Context,
    private val gameDir: File
) {
    companion object {
        // Log tag for this watcher.
        private const val TAG = "FileDialogWatcher"

        // Name of the module directory created inside gameDir.
        const val MODULE_DIR_NAME = ".filedialogmodule"

        // File name of the game-to-launcher request properties file.
        const val REQUEST_FILE_NAME = "filedialogmodule-file-dialog.request.properties"

        // File name of the launcher-to-game response properties file.
        const val RESPONSE_FILE_NAME = "filedialogmodule-file-dialog.response.properties"

        // Delay between request-file polls in milliseconds.
        private const val POLL_INTERVAL_MS = 150L
    }

    // Module directory inside gameDir holding request, response, and picks.
    private val moduleDir = File(gameDir, MODULE_DIR_NAME)

    // Request file written by the game. Consumed and deleted on handling.
    private val requestFile = File(moduleDir, REQUEST_FILE_NAME)

    // Response file written by the picker activity for the game to read.
    private val responseFile = File(moduleDir, RESPONSE_FILE_NAME)

    // Directory where picked documents are copied for game-side path access.
    // Created on start(). Contents are not cleaned up automatically.
    val picksDir = File(moduleDir, "picks")

    // Active polling job, or null when stopped.
    private var job: Job? = null

    // Token of the last handled request, used for de-duplication across polls.
    private var lastHandledToken: String? = null

    // Starts polling for file-dialog requests.
    // Creates moduleDir and picksDir if missing. Does nothing if polling is already active.
    fun start() {
        if (job?.isActive == true) return
        moduleDir.mkdirs()
        picksDir.mkdirs()
        job = CoroutineScope(Dispatchers.IO).launch {
            while (true) {
                try {
                    pollOnce()
                } catch (t: Throwable) {
                    Log.w(TAG, "poll failed", t)
                }
                delay(POLL_INTERVAL_MS)
            }
        }
        Log.i(TAG, "watching $moduleDir")
    }

    // Stops polling and resets de-duplication state.
    // Safe to call when already stopped.
    fun stop() {
        job?.cancel()
        job = null
        lastHandledToken = null
    }

    // Performs a single poll for a new request file.
    // A request is handled once per unique non-empty token. Handling claims the token
    // before launching the picker so a slow picker flow is not re-triggered on the next poll.
    // The request file is deleted upon claiming. Malformed or duplicate requests are ignored.
    // Launches FileDialogPickerActivity with token, filters, description, picks dir,
    // and response file path as extras.
    private fun pollOnce() {
        if (!requestFile.isFile) return
        val props = loadPropertiesQuietly(requestFile) ?: return
        val token = props.getProperty("token", "")
        if (token.isEmpty() || token == lastHandledToken) return

        lastHandledToken = token
        requestFile.delete()

        val filters = props.getProperty("filters", "*")
        val description = props.getProperty("description", "")
        Log.i(TAG, "request token=$token filters=$filters")

        val intent = Intent(appContext, FileDialogPickerActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(FileDialogPickerActivity.EXTRA_TOKEN, token)
            putExtra(FileDialogPickerActivity.EXTRA_FILTERS, filters)
            putExtra(FileDialogPickerActivity.EXTRA_DESCRIPTION, description)
            putExtra(FileDialogPickerActivity.EXTRA_PICKS_DIR, picksDir.absolutePath)
            putExtra(FileDialogPickerActivity.EXTRA_RESPONSE_FILE, responseFile.absolutePath)
        }
        appContext.startActivity(intent)
    }

    // Loads a Java properties file, returning null on any failure.
    // A null result signals a transient read such as a concurrent game-side write in progress,
    // and the caller retries on the next poll.
    // @param file Properties file to load.
    // @return Loaded properties, or null if unreadable or malformed.
    private fun loadPropertiesQuietly(file: File): Properties? = try {
        val props = Properties()
        file.inputStream().use { props.load(it) }
        props
    } catch (t: Throwable) {
        null
    }
}
