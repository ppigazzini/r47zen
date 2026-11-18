package io.github.ppigazzini.r47zen

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class AudioEngineTest {
    private val fullVolumeOn = 100

    @Before
    fun enableTheBeeper() {
        AudioEngine.updateSettings(true, fullVolumeOn)
        AudioEngine.clearQueuedTones()
    }

    @After
    fun emptyTheQueue() {
        AudioEngine.clearQueuedTones()
    }

    // The calculator thread waits the note's length and no more, so anything longer here would
    // build a backlog that keeps sounding after a program stops.
    @Test
    fun aNote_playsForExactlyTheTimeTheCalculatorWaits() {
        assertEquals(11025, AudioEngine.noteSampleCount(durationMs = 250, sampleRate = 44100))
        assertEquals(441, AudioEngine.noteSampleCount(durationMs = 10, sampleRate = 44100))
    }

    @Test
    fun clearingTheQueue_dropsEveryNoteNotStarted() {
        repeat(5) { AudioEngine.playTone(440_000, 250) }
        assertEquals(5, AudioEngine.queuedToneCount())

        AudioEngine.clearQueuedTones()

        assertEquals(0, AudioEngine.queuedToneCount())
    }

    @Test
    fun theQueue_stopsGrowingAtItsCap() {
        repeat(AudioEngine.MAX_QUEUED_TONES + 10) { AudioEngine.playTone(440_000, 250) }

        assertEquals(AudioEngine.MAX_QUEUED_TONES, AudioEngine.queuedToneCount())
    }

    @Test
    fun aDisabledBeeper_queuesNothing() {
        AudioEngine.updateSettings(false, fullVolumeOn)

        AudioEngine.playTone(440_000, 250)

        assertEquals(0, AudioEngine.queuedToneCount())
    }
}
