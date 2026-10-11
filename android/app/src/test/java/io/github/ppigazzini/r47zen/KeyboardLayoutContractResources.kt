package io.github.ppigazzini.r47zen

import kotlinx.serialization.json.jsonObject

internal data class KeyboardLayoutKeyExpectation(
    val primaryLabel: String,
    val fLabel: String,
    val gLabel: String,
    val letterLabel: String,
    val auxLabel: String,
)

internal data class KeyboardLayoutContract(
    val fixtureExpectations: Map<String, Map<Int, KeyboardLayoutKeyExpectation>>,
)

internal object KeyboardLayoutContractResources {
    private const val RESOURCE_NAME = "r47_keyboard_layout_contract.json"

    private val contractCache by lazy { loadContractInternal() }

    fun contract(): KeyboardLayoutContract = contractCache

    private fun loadContractInternal(): KeyboardLayoutContract {
        val json = TestJsonResources.readObject(RESOURCE_NAME)
        val expectations = json.getValue("android_fixture_expectations").jsonObject.mapValues { (_, scenario) ->
            scenario.jsonObject.entries.associate { (keyCode, expectationValue) ->
                val expectation = expectationValue.jsonObject
                keyCode.toInt() to KeyboardLayoutKeyExpectation(
                    primaryLabel = expectation.requireString("primary_label"),
                    fLabel = expectation.requireString("f_label"),
                    gLabel = expectation.requireString("g_label"),
                    letterLabel = expectation.requireString("letter_label"),
                    auxLabel = expectation.requireString("aux_label"),
                )
            }
        }
        return KeyboardLayoutContract(fixtureExpectations = expectations)
    }
}
