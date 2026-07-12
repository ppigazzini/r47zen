package io.github.ppigazzini.r47zen

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import java.util.concurrent.LinkedBlockingQueue

object AudioEngine {
    private const val TAG = "R47Audio"

    private val audioQueue = LinkedBlockingQueue<Long>()

    @Volatile
    private var audioThread: Thread? = null

    @Volatile
    private var isBeeperEnabled = true

    @Volatile
    private var beeperVolume = 20

    @Volatile
    private var runningCheck: () -> Boolean = { false }

    fun updateSettings(enabled: Boolean, volume: Int) {
        isBeeperEnabled = enabled
        beeperVolume = volume.coerceIn(0, 100)
    }

    @Synchronized
    fun start(shouldKeepRunning: () -> Boolean) {
        runningCheck = shouldKeepRunning
        if (audioThread?.isAlive == true) {
            return
        }

        audioThread = Thread {
            Log.i(TAG, "Starting Audio Thread (Zero-GC)...")
            val sampleRate = 44100
            // Build the AudioTrack inside a guard: on a device where the config
            // is unsupported, build() throws, and outside a try/catch that would
            // propagate to the thread's uncaught handler and crash the process.
            // A failed beeper must not take the app down.
            val audioTrack = try {
                val minBufSize = AudioTrack.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(maxOf(minBufSize, 4096))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            } catch (error: Exception) {
                Log.e(TAG, "Failed to create AudioTrack; beeper disabled for this session", error)
                clearThreadHandleIfCurrent()
                return@Thread
            }

            val bufferSize = 48000
            val buffer = ShortArray(bufferSize)

            audioTrack.play()
            try {
                while (runningCheck()) {
                    val packed = audioQueue.take()
                    if (!runningCheck()) {
                        break
                    }

                    val frequency = (packed shr 32).toInt()
                    val durationMs = (packed and 0xFFFFFFFF).toInt()
                    val amplitude: Short = (beeperVolume * 163.84).toInt().toShort()
                    val totalSamples = (durationMs + 20) * sampleRate / 1000
                    val noteSamples = durationMs * sampleRate / 1000
                    val actualSamples = minOf(totalSamples, bufferSize)
                    val period = if (frequency > 0) sampleRate / frequency else 0

                    if (period > 0) {
                        for (index in 0 until actualSamples) {
                            if (index >= noteSamples) {
                                buffer[index] = 0
                            } else {
                                var sample = if ((index % period) < (period / 2)) {
                                    amplitude
                                } else {
                                    (-amplitude).toShort()
                                }

                                val rampSamples = 88
                                if (index < rampSamples) {
                                    sample = (sample * index / rampSamples).toShort()
                                } else if (index > noteSamples - rampSamples) {
                                    sample = (sample * (noteSamples - index) / rampSamples).toShort()
                                }
                                buffer[index] = sample
                            }
                        }
                    } else {
                        for (index in 0 until actualSamples) {
                            buffer[index] = 0
                        }
                    }

                    audioTrack.write(buffer, 0, actualSamples)
                }
            } catch (_: InterruptedException) {
            } catch (error: Exception) {
                Log.e(TAG, "Audio thread error", error)
            } finally {
                try {
                    audioTrack.stop()
                    audioTrack.release()
                } catch (_: Exception) {
                }
                Log.i(TAG, "Audio Thread stopped.")
                clearThreadHandleIfCurrent()
            }
        }.apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    // Clear the handle only if it still points at the calling thread, so a thread
    // finishing its teardown never clobbers a replacement start() already
    // installed. Not @Synchronized: it runs on the audio thread while stop()/
    // start() hold the object monitor, and the identity check keeps it safe.
    private fun clearThreadHandleIfCurrent() {
        if (audioThread === Thread.currentThread()) {
            audioThread = null
        }
    }

    @Synchronized
    fun stop() {
        audioQueue.clear()
        // Drop the handle as well as interrupting: a subsequent start() then sees
        // no thread and spawns a fresh one, instead of observing the still-alive
        // dying thread and skipping the spawn (which left the beeper dead for the
        // session). The dying thread's identity-checked self-clear will not touch
        // the replacement.
        audioThread?.interrupt()
        audioThread = null
    }

    fun playTone(milliHz: Int, durationMs: Int) {
        if (!isBeeperEnabled || milliHz <= 0) {
            return
        }

        val frequency = maxOf(1, milliHz / 1000)
        val packed = (frequency.toLong() shl 32) or (durationMs.toLong() and 0xFFFFFFFF)
        audioQueue.offer(packed)
    }
}
