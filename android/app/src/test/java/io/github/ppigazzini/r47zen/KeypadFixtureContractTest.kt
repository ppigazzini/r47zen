package io.github.ppigazzini.r47zen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class KeypadFixtureContractTest {
    @Test
    fun manifestMatchesKotlinSceneContractSurface() {
        val manifest = KeypadFixtureResources.manifest()

        assertEquals(KeypadSnapshot.SCENE_CONTRACT_VERSION, manifest.sceneContractVersion)
        assertEquals(KeypadSnapshot.META_LENGTH, manifest.metaLength)
        assertEquals(KeypadSnapshot.KEY_COUNT, manifest.keyCount)
        assertEquals(KeypadSnapshot.LABELS_PER_KEY, manifest.labelsPerKey)
        assertTrue(manifest.upstreamCommit.matches(Regex("[0-9a-f]{40}")))
    }

    @Test
    fun manifestListsExactlyTheExportedSceneFiles() {
        // The exporter writes one file a scene and a manifest naming them, so a
        // scene file the manifest leaves out is a scene no test reads.
        val root = File(requireNotNull(javaClass.classLoader?.getResource("keypad-fixtures/manifest.json")).toURI())
            .parentFile
        val sceneFiles = root.listFiles { file -> file.extension == "json" && file.name != "manifest.json" }
            .orEmpty()
            .map { it.name }
            .sorted()
        val listed = KeypadFixtureResources.manifest().scenarios.map { it.fileName }

        assertEquals(sceneFiles, listed.sorted())
        assertEquals(listed.size, listed.toSet().size)
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

    @Test
    fun userMenusSceneMarksEveryAssignedLabelAsAMenu() {
        // The exporter assigns user menus to the f and g labels of three rows on
        // the USER keyboard; each of those labels opens a menu, so the scene
        // carries the menu roles the marker is drawn from.
        val scene = KeypadFixtureResources.load("user-menus-scene").snapshot()

        assertTrue(scene.userModeEnabled)
        for (code in USER_MENU_KEYS) {
            val key = scene.keyStateFor(code)
            assertEquals(
                "key $code f",
                KeypadSceneContract.TEXT_ROLE_F_UNDERLINE,
                key.labelRole(KeypadSceneContract.LABEL_F),
            )
            assertEquals(
                "key $code g",
                KeypadSceneContract.TEXT_ROLE_G_UNDERLINE,
                key.labelRole(KeypadSceneContract.LABEL_G),
            )
        }
        val longest = USER_MENU_KEYS.maxOf { code ->
            maxOf(scene.keyStateFor(code).fLabel.length, scene.keyStateFor(code).gLabel.length)
        }
        assertEquals("the longest name a user menu can have", MAX_USER_MENU_NAME_GLYPHS, longest)
    }

    private companion object {
        // The rows keypad_fixture_exporter.c fills with user menus.
        val USER_MENU_KEYS = (13..17) + (23..27) + (33..37)

        // validateName in the core accepts at most 7 glyphs.
        const val MAX_USER_MENU_NAME_GLYPHS = 7
    }
}
