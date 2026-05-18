package io.github.ppigazzini.r47zen

import java.util.Locale

internal data class DeveloperPerformanceSnapshot(
    val uiFramesPerSecond: Float,
    val lcdUpdatesPerSecond: Float,
    val averageLcdUpdateMillis: Float,
    val lcdUpdateSamples: Int,
) {
    fun overlayLabel(): String {
        if (uiFramesPerSecond <= 0f && lcdUpdateSamples == 0) {
            return "DEV -- fps | LCD --/s | copy --"
        }

        val copyLabel = if (lcdUpdateSamples > 0) {
            String.format(Locale.US, "%.2f ms", averageLcdUpdateMillis)
        } else {
            "--"
        }
        return String.format(
            Locale.US,
            "DEV %.1f fps | LCD %.1f/s | copy %s",
            uiFramesPerSecond,
            lcdUpdatesPerSecond,
            copyLabel,
        )
    }

    companion object {
        val EMPTY = DeveloperPerformanceSnapshot(
            uiFramesPerSecond = 0f,
            lcdUpdatesPerSecond = 0f,
            averageLcdUpdateMillis = 0f,
            lcdUpdateSamples = 0,
        )
    }
}
