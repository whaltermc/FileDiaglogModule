package dev.filedialogmodule

// Backward-compatible aliases for the pre-rename FileDialogModule class names.
// New code should use FileDialogWatcher and FileDialogPickerActivity directly.
// These aliases keep existing callers compiling, including access to companion
// members such as MODULE_DIR_NAME, REQUEST_FILE_NAME, and EXTRA_TOKEN.

// Alias for FileDialogWatcher. Preserves the previous public class name.
typealias FileDialogModuleWatcher = FileDialogWatcher

// Alias for FileDialogPickerActivity. Preserves the previous public class name.
typealias FileDialogModulePickerActivity = FileDialogPickerActivity
