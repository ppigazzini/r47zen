package io.github.ppigazzini.r47zen

import java.util.Locale

internal data class DeveloperPerformanceSnapshot(
    val uiFramesPerSecond: Float,
    val lcdUpdatesPerSecond: Float,
    val averageLcdUpdateMillis: Float,
    val lcdUpdateSamples: Int,
    val averageDirtyRowsPercent: Float = 0f,
) {
    fun overlayLabel(): String {
        if (uiFramesPerSecond <= 0f && lcdUpdateSamples == 0) {
            return "UI -- Hz | LCD -- Hz | DR -- | Copy --"
        }

        val copyLabel = if (lcdUpdateSamples > 0) {
            String.format(Locale.US, "%.1f ms", averageLcdUpdateMillis)
        } else {
            "--"
        }
        val dirtyRowsLabel = if (lcdUpdateSamples > 0) {
            String.format(Locale.US, "%.0f%%", averageDirtyRowsPercent)
        } else {
            "--"
        }
        return String.format(
            Locale.US,
            "UI %.1f Hz | LCD %.1f Hz | DR %s | Copy %s",
            uiFramesPerSecond,
            lcdUpdatesPerSecond,
            dirtyRowsLabel,
            copyLabel,
        )
    }

    companion object {
        val EMPTY = DeveloperPerformanceSnapshot(
            uiFramesPerSecond = 0f,
            lcdUpdatesPerSecond = 0f,
            averageLcdUpdateMillis = 0f,
            lcdUpdateSamples = 0,
            averageDirtyRowsPercent = 0f,
        )
    }
}
