package io.github.ppigazzini.r47zen

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
        val json = parseObject(readText(RESOURCE_NAME))
        val expectationsObject = json.requireObject("android_fixture_expectations")
        val expectations = expectationsObject.mapValues { (_, scenarioValue) ->
            scenarioValue.asObject().map { (keyCode, expectationValue) ->
                keyCode.toInt() to expectationValue.asObject().let { expectation ->
                    KeyboardLayoutKeyExpectation(
                        primaryLabel = expectation.requireString("primary_label"),
                        fLabel = expectation.requireString("f_label"),
                        gLabel = expectation.requireString("g_label"),
                        letterLabel = expectation.requireString("letter_label"),
                        auxLabel = expectation.requireString("aux_label"),
                    )
                }
            }.toMap()
        }
        return KeyboardLayoutContract(fixtureExpectations = expectations)
    }

    private fun readText(path: String): String {
        val loader = Thread.currentThread().contextClassLoader ?: KeyboardLayoutContractResources::class.java.classLoader
        val stream = checkNotNull(loader?.getResourceAsStream(path)) {
            "Missing keyboard layout contract resource: $path"
        }
        return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    private fun parseObject(text: String): Map<String, Any?> {
        return SimpleJsonParser(text).parseObject()
    }

    private fun Map<String, Any?>.requireObject(key: String): Map<String, Any?> {
        return this[key].asObject()
    }

    private fun Map<String, Any?>.requireString(key: String): String {
        return this[key].asString()
    }

    private fun Any?.asObject(): Map<String, Any?> {
        val value = this as? Map<*, *> ?: error("Expected object but was $this")
        return value.entries.associate { (key, nestedValue) ->
            (key as? String ?: error("Expected string key but was $key")) to nestedValue
        }
    }

    private fun Any?.asString(): String {
        return this as? String ?: error("Expected string but was $this")
    }

    private class SimpleJsonParser(private val text: String) {
        private var index = 0

        fun parseObject(): Map<String, Any?> {
            val value = parseValue()
            skipWhitespace()
            if (index != text.length) {
                error("Unexpected trailing content at position $index")
            }
            val root = value as? Map<*, *> ?: error("JSON root is not an object")
            return root.entries.associate { (key, nestedValue) ->
                (key as? String ?: error("Expected string key but was $key")) to nestedValue
            }
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
                skipWhitespace()
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
                'u' -> parseUnicodeEscape()
                else -> error("Unsupported escape sequence \\$escaped")
            }
        }

        private fun parseUnicodeEscape(): Char {
            if (index + 4 > text.length) {
                error("Incomplete unicode escape")
            }
            val hex = text.substring(index, index + 4)
            index += 4
            return hex.toInt(16).toChar()
        }

        private fun parseBoolean(): Boolean {
            return when {
                text.startsWith("true", index) -> {
                    index += 4
                    true
                }
                text.startsWith("false", index) -> {
                    index += 5
                    false
                }
                else -> error("Invalid boolean at position $index")
            }
        }

        private fun parseNull(): Any? {
            if (!text.startsWith("null", index)) {
                error("Invalid null at position $index")
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
            val token = text.substring(start, index)
            return if (token.contains('.')) token.toDouble() else token.toLong()
        }

        private fun skipWhitespace() {
            while (index < text.length && text[index].isWhitespace()) {
                index++
            }
        }

        private fun expect(expected: Char) {
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
