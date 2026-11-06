package io.github.ppigazzini.r47zen

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioEngineTest {
    private companion object {
        const val CAPACITY = 48000
        const val CLICK_SAMPLES = 1323
        const val TRACK_FRAMES = 2048
    }

    @Test
    fun samplesToWrite_padsAShortClickToTheTrackBelowApi31() {
        assertEquals(TRACK_FRAMES, AudioEngine.samplesToWrite(CLICK_SAMPLES, TRACK_FRAMES, CAPACITY))
    }

    @Test
    fun samplesToWrite_writesOnlyTheToneWhenTheThresholdIsOneFrame() {
        assertEquals(CLICK_SAMPLES, AudioEngine.samplesToWrite(CLICK_SAMPLES, 1, CAPACITY))
    }

    @Test
    fun samplesToWrite_neverPassesTheBuffer() {
        assertEquals(CAPACITY, AudioEngine.samplesToWrite(CAPACITY + 1, 1, CAPACITY))
        assertEquals(CAPACITY, AudioEngine.samplesToWrite(1, CAPACITY + 1, CAPACITY))
    }
}
