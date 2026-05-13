package io.github.ppigazzini.r47

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeypadFixtureContractTest {
    @Test
    fun manifestMatchesKotlinSceneContractSurface() {
        val manifest = KeypadFixtureResources.manifest()

        assertEquals(KeypadSnapshot.SCENE_CONTRACT_VERSION, manifest.sceneContractVersion)
        assertEquals(KeypadSnapshot.META_LENGTH, manifest.metaLength)
        assertEquals(KeypadSnapshot.KEY_COUNT, manifest.keyCount)
        assertEquals(KeypadSnapshot.LABELS_PER_KEY, manifest.labelsPerKey)
        assertEquals(10, manifest.scenarios.size)
        assertTrue(manifest.upstreamCommit.matches(Regex("[0-9a-f]{40}")))
    }

    @Test
    fun keypadSnapshotConsumesAllExportedFixtures() {
        KeypadFixtureResources.loadAll().forEach { fixture ->
            val snapshot = fixture.snapshot()

            assertEquals(
                fixture.name,
                KeypadSnapshot.SCENE_CONTRACT_VERSION,
                snapshot.sceneContractVersion,
            )
            assertEquals(
                fixture.name,
                fixture.labels[0],
                snapshot.keyStateFor(1).primaryLabel,
            )
            assertEquals(
                fixture.name,
                fixture.labels[(KeypadSnapshot.KEY_COUNT - 1) * KeypadSnapshot.LABELS_PER_KEY + KeypadSceneContract.LABEL_AUX],
                snapshot.keyStateFor(KeypadSnapshot.KEY_COUNT).auxLabel,
            )
            assertEquals(KeypadKeySnapshot.EMPTY, snapshot.keyStateFor(0))
            assertEquals(KeypadKeySnapshot.EMPTY, snapshot.keyStateFor(KeypadSnapshot.KEY_COUNT + 1))
        }
    }

    @Test
    fun exportedFixturesCoverExpectedLayoutAndMenuStates() {
        val fixtures = KeypadFixtureResources.loadAll().associateBy { it.name }

        val shiftFPreview = fixtures.getValue("shift-f-preview").snapshot()
        assertTrue(shiftFPreview.shiftF)
        assertFalse(shiftFPreview.shiftG)

        val alphaLower = fixtures.getValue("alpha-lower").snapshot()
        assertTrue(alphaLower.alphaOn)

        val dottedRow = fixtures.getValue("dotted-row-state").snapshot()
        assertTrue(dottedRow.softmenuDottedRow in 0..2)
        assertTrue(dottedRow.softmenuPageCount > 3)
        assertTrue(dottedRow.softmenuHasNextPage)

        val staticSingle = fixtures.getValue("static-single-scene").snapshot()
        assertTrue(
            (1..37).any { code ->
                staticSingle.keyStateFor(code).layoutClass == KeypadSceneContract.LAYOUT_CLASS_STATIC_SINGLE
            },
        )

        val tam = fixtures.getValue("tam-scene").snapshot()
        assertTrue(
            (1..37).any { code ->
                tam.keyStateFor(code).layoutClass == KeypadSceneContract.LAYOUT_CLASS_TAM
            },
        )
    }
}
