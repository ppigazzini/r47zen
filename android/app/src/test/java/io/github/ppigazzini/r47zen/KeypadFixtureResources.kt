package io.github.ppigazzini.r47zen

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
        val json = parseObject(readText("$RESOURCE_ROOT/${scenario.fileName}"))
        val metaArray = json.requireArray("meta")
        val labelArray = json.requireArray("labels")
        val meta = IntArray(metaArray.size) { index -> metaArray[index].asInt() }
        val labels = Array(labelArray.size) { index -> labelArray[index].asString() }
        return ExportedKeypadFixture(
            scenario = scenario,
            meta = meta,
            labels = labels,
        )
    }

    private fun loadManifestInternal(): KeypadFixtureManifest {
        val json = parseObject(readText("$RESOURCE_ROOT/manifest.json"))
        val scenariosArray = json.requireArray("scenarios")
        val scenarios = List(scenariosArray.size) { index ->
            val scenario = scenariosArray[index].asObject()
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

    private fun readText(path: String): String {
        val loader = Thread.currentThread().contextClassLoader ?: KeypadFixtureResources::class.java.classLoader
        val stream = checkNotNull(loader?.getResourceAsStream(path)) {
            "Missing keypad fixture resource: $path"
        }
        return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    private fun parseObject(text: String): Map<String, Any?> {
        return SimpleJsonParser(text).parseObject()
    }

    private fun Map<String, Any?>.requireArray(key: String): List<Any?> {
        return this[key] as? List<Any?> ?: error("Expected array for $key")
    }

    private fun Map<String, Any?>.requireString(key: String): String {
        return this[key].asString()
    }

    private fun Map<String, Any?>.requireInt(key: String): Int {
        return this[key].asInt()
    }

    private fun Any?.asObject(): Map<String, Any?> {
        return this as? Map<String, Any?> ?: error("Expected object but was $this")
    }

    private fun Any?.asString(): String {
        return this as? String ?: error("Expected string but was $this")
    }

    private fun Any?.asInt(): Int {
        return when (this) {
            is Int -> this
            is Long -> this.toInt()
            is Double -> this.toInt()
            else -> error("Expected numeric value but was $this")
        }
    }

    private class SimpleJsonParser(private val text: String) {
        private var index = 0

        fun parseObject(): Map<String, Any?> {
            val value = parseValue()
            skipWhitespace()
            if (index != text.length) {
                error("Unexpected trailing content at position $index")
            }
            return value as? Map<String, Any?> ?: error("JSON root is not an object")
        }

        private fun parseValue(): Any? {
            skipWhitespace()
            if (index >= text.length) {
                error("Unexpected end of JSON input")
            }

            return when (text[index]) {
                '{' -> parseMap()
                '[' -> parseList()
                '"' -> parseString()
                't', 'f' -> parseBoolean()
                'n' -> parseNull()
                else -> parseNumber()
            }
        }

        private fun parseMap(): Map<String, Any?> {
            expect('{')
            skipWhitespace()
            if (peek('}')) {
                index++
                return emptyMap()
            }

            val values = linkedMapOf<String, Any?>()
            while (true) {
                val key = parseString()
                skipWhitespace()
                expect(':')
                values[key] = parseValue()
                skipWhitespace()
                when {
                    peek(',') -> index++
                    peek('}') -> {
                        index++
                        return values
                    }
                    else -> error("Expected ',' or '}' at position $index")
                }
            }
        }

        private fun parseList(): List<Any?> {
            expect('[')
            skipWhitespace()
            if (peek(']')) {
                index++
                return emptyList()
            }

            val values = mutableListOf<Any?>()
            while (true) {
                values += parseValue()
                skipWhitespace()
                when {
                    peek(',') -> index++
                    peek(']') -> {
                        index++
                        return values
                    }
                    else -> error("Expected ',' or ']' at position $index")
                }
            }
        }

        private fun parseString(): String {
            expect('"')
            val builder = StringBuilder()
            while (index < text.length) {
                when (val ch = text[index++]) {
                    '"' -> return builder.toString()
                    '\\' -> builder.append(parseEscape())
                    else -> builder.append(ch)
                }
            }
            error("Unterminated JSON string")
        }

        private fun parseEscape(): Char {
            if (index >= text.length) {
                error("Unterminated escape sequence")
            }
            return when (val escaped = text[index++]) {
                '"', '\\', '/' -> escaped
                'b' -> '\b'
                'f' -> '\u000C'
                'n' -> '\n'
                'r' -> '\r'
                't' -> '\t'
                'u' -> {
                    val codePoint = text.substring(index, index + 4).toInt(16)
                    index += 4
                    codePoint.toChar()
                }
                else -> error("Unsupported escape sequence \\$escaped")
            }
        }

        private fun parseBoolean(): Boolean {
            return if (text.startsWith("true", index)) {
                index += 4
                true
            } else if (text.startsWith("false", index)) {
                index += 5
                false
            } else {
                error("Invalid boolean at position $index")
            }
        }

        private fun parseNull(): Any? {
            if (!text.startsWith("null", index)) {
                error("Invalid null literal at position $index")
            }
            index += 4
            return null
        }

        private fun parseNumber(): Number {
            val start = index
            if (peek('-')) {
                index++
            }
            while (index < text.length && text[index].isDigit()) {
                index++
            }
            if (peek('.')) {
                index++
                while (index < text.length && text[index].isDigit()) {
                    index++
                }
            }
            if (peek('e') || peek('E')) {
                index++
                if (peek('+') || peek('-')) {
                    index++
                }
                while (index < text.length && text[index].isDigit()) {
                    index++
                }
            }

            val raw = text.substring(start, index)
            return if (raw.contains('.') || raw.contains('e', ignoreCase = true)) {
                raw.toDouble()
            } else {
                raw.toLong()
            }
        }

        private fun skipWhitespace() {
            while (index < text.length && text[index].isWhitespace()) {
                index++
            }
        }

        private fun expect(expected: Char) {
            skipWhitespace()
            if (!peek(expected)) {
                error("Expected '$expected' at position $index")
            }
            index++
        }

        private fun peek(expected: Char): Boolean {
            return index < text.length && text[index] == expected
        }
    }
}
