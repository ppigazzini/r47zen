package io.github.ppigazzini.r47zen

import org.junit.Assert.assertEquals
import org.junit.Test

class HeldWarningsTest {
    private val held = HeldWarnings(capacity = 3)
    private val shown = mutableListOf<String>()

    @Test
    fun aWarningWithNoHost_waitsForTheNextOne() {
        held.hold("File not found")

        held.showTo { shown += it }

        assertEquals(listOf("File not found"), shown)
    }

    @Test
    fun aWarningIsShownOnce() {
        held.hold("File not found")
        held.showTo { shown += it }

        held.showTo { shown += it }

        assertEquals(listOf("File not found"), shown)
    }

    // A program that runs on with no window could warn without end: the latest few wait.
    @Test
    fun onlyTheLatestFew_wait() {
        listOf("one", "two", "three", "four", "five").forEach(held::hold)

        held.showTo { shown += it }

        assertEquals(listOf("three", "four", "five"), shown)
    }
}
