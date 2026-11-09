package io.github.ppigazzini.r47zen

/** The names of the app's shared preferences files. */
internal object AppPreferences {
    /** Every setting, the work directory, and the saved manual. */
    const val NAME = "R47Prefs"

    /**
     * Written by versions that kept a list of calculator slots, and read by none since. An
     * install upgraded from one still has the file, so a factory reset deletes it and the backup
     * rules keep it out of a backup.
     */
    const val RETIRED_SLOTS_NAME = "R47Slots"
}
