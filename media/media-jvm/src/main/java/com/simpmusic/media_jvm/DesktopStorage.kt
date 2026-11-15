package com.simpmusic.media_jvm

import java.io.File

/**
 * The app's per-user storage root on the desktop: `~/.duomusic` since the 2026-11 rebrand.
 *
 * A pre-rebrand `~/.simpmusic` is moved over whole, once, on the first [dir] call — downloads,
 * reverb IRs and waveform caches survive the rename in place. If both roots exist (the app ran
 * once on the new name, then an old build created data again) the new one wins and the legacy
 * root is left untouched for the user to inspect; a failed move (permissions, cross-device
 * home) degrades to an empty new root rather than an error.
 */
object DesktopStorage {
    private val home = System.getProperty("user.home") ?: "."
    private val legacyRoot = File(home, ".simpmusic")
    private val root = File(home, ".duomusic")

    @Volatile
    private var migrated = false

    /** [name] under the storage root, created if missing, after the one-way legacy move. */
    @Synchronized
    fun dir(name: String): File {
        if (!migrated) {
            migrated = true
            if (legacyRoot.isDirectory && !root.exists()) {
                runCatching { legacyRoot.renameTo(root) }
            }
        }
        return File(root, name).apply { mkdirs() }
    }
}
