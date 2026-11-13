package io.github.ppigazzini.r47zen

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// The audio thread's start, which builds an AudioTrack: Robolectric stands in for it.
@RunWith(RobolectricTestRunner::class)
class AudioEngineStartTest {
    @Before
    fun enableTheBeeper() {
        AudioEngine.stop()
        AudioEngine.updateSettings(true, 100)
    }

    @After
    fun stopTheThread() {
        AudioEngine.stop()
    }

    // With no audio thread, a program's tones only queue: the Activity was stopped, and a
    // running program goes on. They belong to a moment past, so a new thread plays none.
    @Test
    fun aNewAudioThread_playsNoToneQueuedBeforeIt() {
        repeat(5) { AudioEngine.playTone(440_000, 250) }

        AudioEngine.start { false }

        assertEquals(0, AudioEngine.queuedToneCount())
    }
}
