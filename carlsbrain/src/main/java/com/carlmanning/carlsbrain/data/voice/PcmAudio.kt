package com.carlmanning.carlsbrain.data.voice

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The last few seconds of microphone audio, kept by the wake-word loop.
 *
 * Two readers: the trigger clip (what did the spotter actually hear?) and the pre-roll handed to
 * [CommandCapture], which covers the words spoken between the end of "Hey Brain" and the moment
 * the spotter reports it. Single-threaded by design — only the audio thread touches it.
 */
class PcmRing(private val capacity: Int) {
    private val data = ShortArray(capacity)
    private var writeIndex = 0
    private var filled = 0

    fun write(buffer: ShortArray, count: Int) {
        val n = count.coerceIn(0, buffer.size)
        // A read longer than the ring keeps only its tail.
        val from = if (n > capacity) n - capacity else 0
        for (i in from until n) {
            data[writeIndex] = buffer[i]
            writeIndex = (writeIndex + 1) % capacity
        }
        filled = (filled + (n - from)).coerceAtMost(capacity)
    }

    /** The most recent [samples], oldest first. Shorter if less than that has been captured. */
    fun last(samples: Int): ShortArray {
        val n = samples.coerceIn(0, filled)
        val out = ShortArray(n)
        var index = (writeIndex - n + capacity) % capacity
        for (i in 0 until n) {
            out[i] = data[index]
            index = (index + 1) % capacity
        }
        return out
    }
}

/** A ShortArray that grows. Avoids boxing every sample into a List<Short>. */
class PcmAccumulator(initialCapacity: Int = 16000 * 8) {
    private var data = ShortArray(initialCapacity)
    var size = 0
        private set

    fun append(buffer: ShortArray, count: Int) {
        val n = count.coerceIn(0, buffer.size)
        if (size + n > data.size) {
            data = data.copyOf(maxOf(data.size * 2, size + n))
        }
        System.arraycopy(buffer, 0, data, size, n)
        size += n
    }

    fun toArray(): ShortArray = data.copyOf(size)
}

object Wav {
    /** 16-bit mono PCM wrapped in a canonical 44-byte RIFF header. */
    fun encode(samples: ShortArray, sampleRate: Int): ByteArray {
        val dataBytes = samples.size * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(36 + dataBytes)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(1)                    // PCM
            putShort(1)                    // mono
            putInt(sampleRate)
            putInt(sampleRate * 2)         // byte rate
            putShort(2)                    // block align
            putShort(16)                   // bits per sample
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(dataBytes)
        }
        val body = ByteBuffer.allocate(dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        body.asShortBuffer().put(samples)
        return ByteArrayOutputStream(44 + dataBytes).apply {
            write(header.array())
            write(body.array())
        }.toByteArray()
    }
}
