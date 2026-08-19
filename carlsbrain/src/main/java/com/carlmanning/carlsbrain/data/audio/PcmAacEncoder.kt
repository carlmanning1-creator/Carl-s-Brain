package com.carlmanning.carlsbrain.data.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

/**
 * Encodes a stream of 16 kHz mono PCM16 into a single AAC/MP4 file.
 *
 * ## Why this exists rather than MediaRecorder
 * MediaRecorder owns the microphone and can only start recording *now*, which is precisely
 * what the ambient buffer is designed to work around. To produce one continuous meeting
 * recording that begins before the trigger, the buffered PCM and the live PCM have to go
 * through the same encoder — so the app has to hold the PCM and do the encoding itself.
 *
 * The alternative was to keep two audio files (a WAV of the buffer plus the usual m4a) and
 * concatenate their transcripts. That was rejected: Fireflies is given one file per meeting,
 * so two files means two transcriptions, two sets of speaker labels, and a seam Carl would
 * have to reconcile by hand. Uncompressed WAV for the whole meeting was rejected too — 90
 * minutes is ~173 MB, over Whisper's 25 MB limit and a poor thing to push over mobile data.
 * At 32 kbps AAC the same meeting is roughly 21 MB.
 *
 * Not thread-safe: [feed] and [finish] must be called from the one thread that owns the
 * recording loop.
 */
class PcmAacEncoder(
    private val outputFile: File,
    private val sampleRate: Int = AmbientBuffer.SAMPLE_RATE,
    private val bitRate: Int = 32_000
) {

    private companion object {
        const val TAG = "PcmAacEncoder"
        const val MIME = MediaFormat.MIMETYPE_AUDIO_AAC
        const val DEQUEUE_TIMEOUT_US = 10_000L
    }

    private var codec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var trackIndex = -1
    private var muxerStarted = false
    private var totalBytesFed = 0L
    private var failed = false
    private val bufferInfo = MediaCodec.BufferInfo()

    /** True once the encoder has hit an unrecoverable error; all further calls are no-ops. */
    val isFailed: Boolean get() = failed

    /** Milliseconds of audio accepted so far — the meeting duration, including the buffer. */
    fun encodedMs(): Long = totalBytesFed / (sampleRate.toLong() * AmbientBuffer.BYTES_PER_SAMPLE / 1000)

    fun start(): Boolean {
        return runCatching {
            val format = MediaFormat.createAudioFormat(MIME, sampleRate, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
            }
            val c = MediaCodec.createEncoderByType(MIME)
            c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            c.start()
            codec = c
            outputFile.parentFile?.mkdirs()
            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            true
        }.getOrElse {
            Log.e(TAG, "Encoder start failed: ${it.message}")
            failed = true
            release()
            false
        }
    }

    /** Feeds [len] bytes of little-endian PCM16. Safe to call with any chunk size. */
    fun feed(data: ByteArray, len: Int) {
        val c = codec ?: return
        if (failed || len <= 0) return
        var offset = 0
        var remaining = len.coerceAtMost(data.size)
        try {
            while (remaining > 0) {
                val inIndex = c.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                if (inIndex < 0) {
                    // No input buffer free — drain output to make room, then retry.
                    drainOutput(endOfStream = false)
                    continue
                }
                val inBuf: ByteBuffer = c.getInputBuffer(inIndex) ?: continue
                inBuf.clear()
                val chunk = minOf(inBuf.remaining(), remaining)
                inBuf.put(data, offset, chunk)
                // Presentation time is derived from the byte count, not the clock: the buffered
                // audio is fed far faster than real time, and a wall-clock timestamp would
                // compress the whole prepended section into a fraction of a second.
                val ptsUs = totalBytesFed * 1_000_000L /
                        (sampleRate.toLong() * AmbientBuffer.BYTES_PER_SAMPLE)
                c.queueInputBuffer(inIndex, 0, chunk, ptsUs, 0)
                totalBytesFed += chunk
                offset += chunk
                remaining -= chunk
                drainOutput(endOfStream = false)
            }
        } catch (e: Exception) {
            Log.e(TAG, "feed failed: ${e.message}")
            failed = true
        }
    }

    /**
     * Signals end of stream, flushes the muxer and closes the file.
     *
     * @return the finished file, or null if encoding failed or produced nothing. A null return
     *   means the caller should treat the meeting as having no audio, not retry.
     */
    fun finish(): File? {
        val c = codec
        if (c == null || failed) {
            release()
            return null
        }
        runCatching {
            var attempts = 0
            while (attempts < 50) {
                val inIndex = c.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                if (inIndex >= 0) {
                    c.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    break
                }
                drainOutput(endOfStream = false)
                attempts++
            }
            drainOutput(endOfStream = true)
        }.onFailure {
            Log.e(TAG, "finish failed: ${it.message}")
            failed = true
        }
        release()
        return if (!failed && outputFile.exists() && outputFile.length() > 0) outputFile else null
    }

    private fun drainOutput(endOfStream: Boolean) {
        val c = codec ?: return
        val m = muxer ?: return
        // Bounded so a codec that never delivers its end-of-stream flag cannot hang the
        // recording thread forever — at 10 ms per timeout this gives up after ~5 seconds.
        var idleWaits = 0
        while (true) {
            val outIndex = c.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)
            when {
                outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    // Only keep waiting when we are draining the tail; otherwise returning lets
                    // the caller queue more input rather than spinning here.
                    if (!endOfStream) return
                    if (++idleWaits > 500) {
                        Log.w(TAG, "Gave up waiting for end-of-stream")
                        return
                    }
                }
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (!muxerStarted) {
                        trackIndex = m.addTrack(c.outputFormat)
                        m.start()
                        muxerStarted = true
                    }
                }
                outIndex >= 0 -> {
                    val outBuf = c.getOutputBuffer(outIndex)
                    if (outBuf != null && bufferInfo.size > 0 && muxerStarted &&
                        (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                    ) {
                        outBuf.position(bufferInfo.offset)
                        outBuf.limit(bufferInfo.offset + bufferInfo.size)
                        m.writeSampleData(trackIndex, outBuf, bufferInfo)
                    }
                    c.releaseOutputBuffer(outIndex, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return
                }
            }
        }
    }

    private fun release() {
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
        // stop() throws if no sample was ever written; the file is then useless either way, so
        // the failure is recorded rather than swallowed so finish() returns null.
        if (muxerStarted) {
            runCatching { muxer?.stop() }.onFailure { failed = true }
        } else if (muxer != null) {
            failed = true
        }
        runCatching { muxer?.release() }
        muxer = null
        muxerStarted = false
    }
}
