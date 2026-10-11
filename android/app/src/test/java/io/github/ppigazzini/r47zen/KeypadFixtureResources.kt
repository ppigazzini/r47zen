package io.github.ppigazzini.r47zen

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

internal data class KeypadFixtureManifestEntry(
    val name: String,
    val fileName: String,
    val description: String,
)

internal data class KeypadFixtureManifest(
    val upstreamCommit: String,
    val sceneContractVersion: Int,
    val metaLength: Int,
    val keyCount: Int,
    val labelsPerKey: Int,
    val scenarios: List<KeypadFixtureManifestEntry>,
)

internal data class ExportedKeypadFixture(
    val scenario: KeypadFixtureManifestEntry,
    val meta: IntArray,
    val labels: Array<String>,
) {
    val name: String
        get() = scenario.name

    fun snapshot(): KeypadSnapshot = KeypadSnapshot.fromNative(meta, labels)
}

internal object KeypadFixtureResources {
    private const val RESOURCE_ROOT = "keypad-fixtures"

    private val manifestCache by lazy { loadManifestInternal() }

    fun manifest(): KeypadFixtureManifest = manifestCache

    fun load(name: String): ExportedKeypadFixture {
        val scenario = manifestCache.scenarios.first { it.name == name }
        return load(scenario)
    }

    fun loadAll(): List<ExportedKeypadFixture> {
        return manifestCache.scenarios.map(::load)
    }

    private fun load(scenario: KeypadFixtureManifestEntry): ExportedKeypadFixture {
        val json = TestJsonResources.readObject("$RESOURCE_ROOT/${scenario.fileName}")
        return ExportedKeypadFixture(
            scenario = scenario,
            meta = json.getValue("meta").jsonArray.map { it.asInt() }.toIntArray(),
            labels = json.getValue("labels").jsonArray.map { it.asString() }.toTypedArray(),
        )
    }

    private fun loadManifestInternal(): KeypadFixtureManifest {
        val json = TestJsonResources.readObject("$RESOURCE_ROOT/manifest.json")
        val scenarios = json.getValue("scenarios").jsonArray.map { element ->
            val scenario = element.jsonObject
            KeypadFixtureManifestEntry(
                name = scenario.requireString("name"),
                fileName = scenario.requireString("file"),
                description = scenario.requireString("description"),
            )
        }

        return KeypadFixtureManifest(
            upstreamCommit = json.requireString("upstreamCommit"),
            sceneContractVersion = json.requireInt("sceneContractVersion"),
            metaLength = json.requireInt("metaLength"),
            keyCount = json.requireInt("keyCount"),
            labelsPerKey = json.requireInt("labelsPerKey"),
            scenarios = scenarios,
        )
    }
}
