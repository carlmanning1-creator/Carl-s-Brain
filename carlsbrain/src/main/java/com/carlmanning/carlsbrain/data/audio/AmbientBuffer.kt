package com.carlmanning.carlsbrain.data.audio

import android.util.Log
import java.io.File
import java.io.RandomAccessFile

/**
 * The rolling ambient audio buffer — the last N minutes of what the microphone heard, kept so
 * that "start recording" can start from *before* the moment Carl thought to press it.
 *
 * ## Why it is file-backed
 * At the top of Carl's chosen range (20 minutes) the buffer is 16 kHz × 16-bit × 20 × 60 =
 * 38.4 MB. Holding that on the heap would be a large, permanently-resident allocation in a
 * process that also runs Compose, Room and a wake-word model, and is exactly the kind of thing
 * Android kills a background app for. A preallocated circular file costs the same bytes on
 * disk (in cacheDir, so the system can reclaim it) and effectively nothing in RAM.
 *
 * ## Ownership
 * This is a passive sink with no microphone of its own. Exactly one component feeds it at a
 * time, because Android will not give two AudioRecord clients in the same app usable
 * concurrent capture:
 *  - wake word ON  → [com.carlmanning.carlsbrain.data.local.worker.VoiceCaptureService]'s KWS
 *    loop feeds each frame here as it decodes it.
 *  - wake word OFF → [com.carlmanning.carlsbrain.data.local.worker.AmbientBufferService] runs
 *    its own capture loop.
 *
 * A consequence worth knowing: while the wake word is parked (quiet hours, or an active
 * conversation) nothing is feeding the buffer, so the ring covers the last N minutes *of
 * capture*, not of wall-clock. [bufferedMs] reports the real amount held rather than assuming
 * the ring is full.
 *
 * All public methods are safe to call from any thread and are no-ops when the buffer is
 * closed, so callers never need to check state first.
 */
object AmbientBuffer {

    private const val TAG = "AmbientBuffer"

    const val SAMPLE_RATE = 16000
    const val BYTES_PER_SAMPLE = 2
    private const val BYTES_PER_MS = (SAMPLE_RATE * BYTES_PER_SAMPLE) / 1000  // 32

    /** Carl's chosen adjustable range for the buffer length, in minutes. */
    const val MIN_MINUTES = 5
    const val MAX_MINUTES = 20
    const val DEFAULT_MINUTES = 5

    private const val RING_FILE_NAME = "ambient_ring.pcm"

    private val lock = Any()
    private var raf: RandomAccessFile? = null
    private var capacityBytes = 0
    private var writePos = 0
    private var filledBytes = 0

    fun capacityBytesFor(minutes: Int): Int =
        minutes.coerceIn(MIN_MINUTES, MAX_MINUTES) * 60 * SAMPLE_RATE * BYTES_PER_SAMPLE

    val isOpen: Boolean get() = synchronized(lock) { raf != null }

    /** Bytes currently held, as milliseconds of audio. */
    fun bufferedMs(): Long = synchronized(lock) { filledBytes.toLong() / BYTES_PER_MS }

    /**
     * Opens (or resizes) the ring to hold [minutes] of audio.
     *
     * Changing the length discards what is held rather than trying to preserve it: the stored
     * bytes are laid out relative to the old capacity, and salvaging them would mean a rewrite
     * for no real benefit — the setting is changed rarely and deliberately.
     */
    fun open(cacheDir: File, minutes: Int) {
        val wanted = capacityBytesFor(minutes)
        synchronized(lock) {
            if (raf != null && capacityBytes == wanted) return
            closeLocked()
            val file = File(cacheDir, RING_FILE_NAME)
            runCatching {
                val f = RandomAccessFile(file, "rw")
                f.setLength(wanted.toLong())
                raf = f
                capacityBytes = wanted
                writePos = 0
                filledBytes = 0
            }.onFailure {
                Log.e(TAG, "Could not open ring buffer: ${it.message}")
                raf = null
                capacityBytes = 0
            }
        }
    }

    fun close() = synchronized(lock) { closeLocked() }

    private fun closeLocked() {
        runCatching { raf?.close() }
        raf = null
        capacityBytes = 0
        writePos = 0
        filledBytes = 0
    }

    /** Deletes the backing file. Called when the buffer is switched off in Settings. */
    fun deleteFile(cacheDir: File) {
        synchronized(lock) {
            closeLocked()
            runCatching { File(cacheDir, RING_FILE_NAME).delete() }
        }
    }

    /** Forgets everything held without closing — used immediately after a drain. */
    fun clear() = synchronized(lock) {
        writePos = 0
        filledBytes = 0
    }

    /** Feeds [count] 16-bit samples, as delivered by AudioRecord.read(ShortArray, ...). */
    fun feed(samples: ShortArray, count: Int) {
        if (count <= 0) return
        val n = count.coerceAtMost(samples.size)
        val bytes = ByteArray(n * BYTES_PER_SAMPLE)
        var i = 0
        for (s in 0 until n) {
            val v = samples[s].toInt()
            bytes[i++] = (v and 0xFF).toByte()          // little-endian, matching WAV/PCM16
            bytes[i++] = ((v shr 8) and 0xFF).toByte()
        }
        feedBytes(bytes, bytes.size)
    }

    /** Feeds [len] bytes of little-endian PCM16. */
    fun feedBytes(data: ByteArray, len: Int) {
        if (len <= 0) return
        synchronized(lock) {
            val f = raf ?: return
            val cap = capacityBytes
            if (cap <= 0) return

            // A single write longer than the whole ring can only leave its own tail behind, so
            // skip straight to that tail instead of wrapping the ring several times.
            var offset = 0
            var remaining = len.coerceAtMost(data.size)
            if (remaining > cap) {
                offset = remaining - cap
                remaining = cap
            }

            val effective = remaining
            runCatching {
                while (remaining > 0) {
                    val room = cap - writePos
                    val chunk = minOf(room, remaining)
                    f.seek(writePos.toLong())
                    f.write(data, offset, chunk)
                    writePos = (writePos + chunk) % cap
                    offset += chunk
                    remaining -= chunk
                }
                if (filledBytes < cap) filledBytes = minOf(cap, filledBytes + effective)
            }.onFailure {
                Log.e(TAG, "Ring write failed: ${it.message}")
                closeLocked()
            }
        }
    }

    /**
     * Streams everything held, oldest sample first, to [sink] in chunks, then clears the ring.
     *
     * Streaming rather than returning a ByteArray for the same reason the ring is on disk: the
     * caller (the AAC encoder) consumes it incrementally, so materialising 38 MB first would be
     * pure waste. Returns the number of bytes handed over.
     *
     * The ring is held locked for the duration, so any audio captured while a drain is in
     * progress is dropped rather than interleaved — the drain takes well under a second, and
     * live capture is about to be redirected to the encoder anyway.
     */
    fun drainTo(chunkBytes: Int = 64 * 1024, sink: (ByteArray, Int) -> Unit): Long {
        synchronized(lock) {
            val f = raf ?: return 0L
            val cap = capacityBytes
            val total = filledBytes
            if (cap <= 0 || total <= 0) return 0L

            // When the ring has wrapped, the oldest byte is the one about to be overwritten.
            val start = if (total < cap) 0 else writePos
            val buf = ByteArray(chunkBytes)
            var written = 0L
            runCatching {
                var readSoFar = 0
                while (readSoFar < total) {
                    val pos = (start + readSoFar) % cap
                    val chunk = minOf(buf.size, total - readSoFar, cap - pos)
                    f.seek(pos.toLong())
                    f.readFully(buf, 0, chunk)
                    sink(buf, chunk)
                    readSoFar += chunk
                    written += chunk
                }
            }.onFailure {
                Log.e(TAG, "Ring drain failed after $written bytes: ${it.message}")
            }
            writePos = 0
            filledBytes = 0
            return written
        }
    }
}
