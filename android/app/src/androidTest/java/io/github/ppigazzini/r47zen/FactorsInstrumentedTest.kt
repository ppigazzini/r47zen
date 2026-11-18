package io.github.ppigazzini.r47zen

import android.os.Bundle
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.math.BigDecimal
import java.math.BigInteger

// FACTORS on the device's own build of the core, each answer held to the input's
// factorization: distinct primes, each with a multiplicity of at least 1, that
// multiply back to the input. By unique factorization nothing else passes, so a
// composite left as a factor, which prime.c's SQUFOF fallback reports as prime,
// fails. 4294971521 and 41622656721349609 have cofactors between 2^32 and 2^64,
// where a wrong mini-gmp mpz_fits_uint_p once factored the first wrong.
@RunWith(AndroidJUnit4::class)
class FactorsInstrumentedTest {
    @Test
    fun factorsRunsThroughAndroidRuntime() {
        val failures = mutableListOf<String>()

        ActivityScenario.launch(MainActivity::class.java).use {
            assertTrue(
                "Native runtime did not become ready for FACTORS coverage",
                waitUntil(RUNTIME_READY_TIMEOUT_MS) { ProgramLoadTestBridge.isRuntimeReady() },
            )
            for (input in INPUTS) {
                factor(input)?.let { failure -> failures += failure }
                // A FACTORS that R/S did not stop holds the calculator thread, and the
                // next reset would wait for rest with no end.
                if (ProgramLoadTestBridge.isSimFunctionRunning()) {
                    break
                }
            }
        }

        assertTrue(
            "FACTORS instrumentation failures:\n${failures.joinToString(separator = "\n")}",
            failures.isEmpty(),
        )
    }

    // Factor one input and return what was wrong, or null.
    private fun factor(input: String): String? {
        ProgramLoadTestBridge.resetRuntime()
        val resetState = ProgramLoadTestBridge.snapshotState()
        if (resetState.lastErrorCode != ERROR_NONE) {
            return buildFailure(input, "reset", "reset left calculator error ${resetState.lastErrorCode}")
        }
        if (!ProgramLoadTestBridge.seedXLongInteger(input)) {
            return buildFailure(input, "seed", "failed to put the input in X")
        }
        if (!ProgramLoadTestBridge.beginSimFunction(ITM_FACTORS)) {
            return buildFailure(input, "run", "failed to start the asynchronous FACTORS worker")
        }
        val completed = waitUntil(FACTORS_TIMEOUT_MS) { !ProgramLoadTestBridge.isSimFunctionRunning() }
        // Reading the result waits for rest, which a FACTORS still running never
        // reaches: stop it as a user does, and fail.
        if (!completed) {
            ProgramLoadTestBridge.pressRunStopKey()
            waitUntil(STOP_TIMEOUT_MS) { !ProgramLoadTestBridge.isSimFunctionRunning() }
            return buildFailure(input, "run", "FACTORS did not return within $FACTORS_TIMEOUT_MS ms")
        }
        // The text first: the call that reads it at rest publishes the state as it
        // ends, so the snapshot reads what FACTORS left, not an earlier state.
        val text = ProgramLoadTestBridge.getXRegisterClipboardText()
        val finalState = ProgramLoadTestBridge.snapshotState()
        return when {
            finalState.lastErrorCode != ERROR_NONE ->
                buildFailure(input, "run", "FACTORS hit calculator error ${finalState.lastErrorCode}")
            ProgramLoadTestBridge.getXRegisterType() != DT_REAL34_MATRIX ->
                buildFailure(input, "result", "FACTORS left X as '$text', not a matrix")
            else -> checkFactorization(BigInteger(input), text)
        }
    }

    // The clipboard copies the 2 x n result as "2xn", then the n factors, then their n
    // multiplicities, each a reduced decimal: 10 can read 1E+1.
    private fun checkFactorization(input: BigInteger, text: String): String? {
        val words = text.trim().split(Regex("\\s+"))
        val shape = words.firstOrNull()?.split('x')?.mapNotNull { it.toIntOrNull() }
        if (shape == null || shape.size != 2 || shape[0] != 2 || words.size != 1 + 2 * shape[1]) {
            return buildFailure("$input", "result", "FACTORS left '$text', not a 2 x n matrix")
        }
        val values = words.drop(1).map { runCatching { BigDecimal(it).toBigIntegerExact() }.getOrNull() }
        if (values.any { it == null }) {
            return buildFailure("$input", "result", "FACTORS left '$text', not all integers")
        }
        val factors = values.take(shape[1]).filterNotNull()
        val multiplicities = values.drop(shape[1]).filterNotNull()
        if (factors.toSet().size != factors.size ||
            factors.any { it <= BigInteger.ONE || !it.isProbablePrime(PRIME_CERTAINTY) }
        ) {
            return buildFailure("$input", "result", "'$text' holds a factor that is not a distinct prime")
        }
        // A factor is at least 2, so no multiplicity exceeds the input's bit length.
        val maxMultiplicity = BigInteger.valueOf(input.bitLength().toLong())
        if (multiplicities.any { it < BigInteger.ONE || it > maxMultiplicity }) {
            return buildFailure("$input", "result", "'$text' holds a multiplicity out of range")
        }
        val product = factors.zip(multiplicities).fold(BigInteger.ONE) { product, (factor, power) ->
            product * factor.pow(power.toInt())
        }
        return if (product == input) null else buildFailure("$input", "result", "'$text' multiplies back to $product")
    }

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) {
                return true
            }
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        return condition()
    }

    private fun buildFailure(input: String, phase: String, details: String): String {
        val message = "FACTORS $input [$phase]: $details"
        reportStatus("$message\n")
        return message
    }

    private fun reportStatus(message: String) {
        System.out.print(message)
        System.out.flush()
        val statusBundle = Bundle().apply {
            putString("stream", message)
        }
        InstrumentationRegistry.getInstrumentation().sendStatus(0, statusBundle)
    }

    companion object {
        private val INPUTS = listOf(
            "5424563354566542698521412502251020304050",
            "4294971521",
            "41622656721349609",
        )
        private const val ITM_FACTORS = 1477
        private const val DT_REAL34_MATRIX = 6
        private const val ERROR_NONE = 0
        private const val RUNTIME_READY_TIMEOUT_MS = 15_000L
        private const val FACTORS_TIMEOUT_MS = 45_000L
        private const val STOP_TIMEOUT_MS = 5_000L
        private const val POLL_INTERVAL_MS = 25L
        private const val PRIME_CERTAINTY = 64
    }
}
