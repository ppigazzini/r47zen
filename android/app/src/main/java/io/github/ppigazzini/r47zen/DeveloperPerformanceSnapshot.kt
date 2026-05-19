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
            return "App -- fps | LCD -- ups | Copy --"
        }

        val copyLabel = if (lcdUpdateSamples > 0) {
            String.format(Locale.US, "%.1f ms", averageLcdUpdateMillis)
        } else {
            "--"
        }
        return String.format(
            Locale.US,
            "App %.1f fps | LCD %.1f ups | Copy %s",
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
