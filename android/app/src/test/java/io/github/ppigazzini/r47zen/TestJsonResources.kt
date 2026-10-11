package io.github.ppigazzini.r47zen

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// Reads the JSON test resources: the exported keypad fixtures under
// src/test/resources and the contract goldens that app/build.gradle adds to
// the test resources from scripts/r47_contracts/data.
internal object TestJsonResources {
    fun readObject(path: String): JsonObject {
        val loader = Thread.currentThread().contextClassLoader ?: TestJsonResources::class.java.classLoader
        val stream = checkNotNull(loader?.getResourceAsStream(path)) { "Missing test resource: $path" }
        val text = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        return Json.parseToJsonElement(text).jsonObject
    }
}

internal fun JsonElement.asString(): String {
    val primitive = jsonPrimitive
    check(primitive.isString) { "Expected a string but was $this" }
    return primitive.content
}

internal fun JsonElement.asInt(): Int = jsonPrimitive.int

internal fun JsonObject.requireString(key: String): String = getValue(key).asString()

internal fun JsonObject.requireInt(key: String): Int = getValue(key).asInt()
