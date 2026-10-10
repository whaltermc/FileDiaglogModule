# FileDialogModule

Drop-in Gradle module implementing the launcher side of the FileDialogModule file-dialog
protocol (watches `.filedialogmodule/filedialogmodule-file-dialog.request.properties` in a game
directory, shows Android's document picker, writes the response file).

Package: `dev.filedialogmodule`

## Add it to your project

1. Copy this whole `filedialogmodule/` folder to the root of your launcher project,
   next to your `app/` module.
2. In `settings.gradle.kts`, add:

   ```kotlin
   include(":filedialogmodule")
   ```

3. In your `app/build.gradle.kts`:

   ```kotlin
   dependencies {
       implementation(project(":filedialogmodule"))
   }
   ```

4. Sync Gradle. The activity's manifest entry merges in automatically — nothing to add to
   `app/src/main/AndroidManifest.xml`.

## Building just the AAR

If you want the standalone `.aar` file rather than a source dependency:

```
./gradlew :filedialogmodule:assembleRelease
```

Output lands at `filedialogmodule/build/outputs/aar/filedialogmodule-release.aar`.

## Using it

Wherever your launcher starts/stops a game process:

```kotlin
import dev.filedialogmodule.FileDialogWatcher

val watcher = FileDialogWatcher(applicationContext, gameDirectory)
watcher.start()
// ... launch the game process ...
// on process exit:
watcher.stop()
```

`gameDirectory` must be the same directory the game process treats as its `user.dir` —
that's where it writes `.filedialogmodule/filedialogmodule-file-dialog.request.properties` and expects
the response at `.filedialogmodule/filedialogmodule-file-dialog.response.properties`.

## Versions pinned in build.gradle.kts

- `compileSdk 34`, `minSdk 26`, Java/Kotlin target 17
- `androidx.core:core-ktx:1.13.1`
- `androidx.activity:activity-ktx:1.9.3`
- `org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1`

Bump these to match whatever your `app` module already uses if they differ — mismatched
AndroidX/Kotlin versions across modules are a common source of build failures.

## Notes / limitations

- Extension filtering in the system picker only works for extensions Android's
  `MimeTypeMap` recognizes. Mod-specific formats like `.schem` have no registered MIME
  type, so those requests fall back to showing every file.
- Picked files are copied into `<gameDir>/.filedialogmodule/picks/` and are not currently
  cleaned up automatically.
- One `FileDialogWatcher` is meant per running game directory; it doesn't guard against
  two instances racing on the same directory.
- `FileDialogModuleWatcher` / `FileDialogModulePickerActivity` remain as deprecated
  typealiases, so existing callers keep compiling.
