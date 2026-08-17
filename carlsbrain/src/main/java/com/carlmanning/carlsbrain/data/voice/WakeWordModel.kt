package com.carlmanning.carlsbrain.data.voice

import android.content.Context
import android.util.Log
import java.io.File
import java.util.Locale

/**
 * sherpa-onnx keyword-spotting assets and the runtime `keywords.txt` that selects the phrase.
 *
 * Replaces Picovoice Porcupine, whose free tier was terminated on 30 June 2026.
 *
 * See `docs/wake-word.md` for the full background. The two facts that matter most here:
 *
 *  1. The gigaspeech model's `bpe.model` is a sentencepiece **UNIGRAM** model despite its name,
 *     so tokenisations cannot be derived with a greedy BPE splitter — wrong tokens fail
 *     *silently* (the app builds, runs, and simply never triggers). Every entry in
 *     [WAKE_KEYWORDS] below has been verified in-vocabulary on a real device. Do not add a
 *     phrase here without validating its tokens against the model's `tokens.txt` first.
 *  2. `keywords.txt` must be UTF-8, no BOM, LF line endings. A CRLF line leaves a stray `\r`
 *     glued to the final token, which then never matches.
 *
 * An out-of-vocabulary token in `keywords.txt` makes sherpa's native `EncodeKeywords` fail, and
 * the file-loading path answers that with `SHERPA_ONNX_EXIT(-1)` — a process abort, not an
 * exception. That is why the phrase chosen in Settings is always resolved back through
 * [keywordFor] rather than trusted as free text.
 */
object WakeWordModel {

    private const val TAG = "WakeWordModel"

    /** Subdirectory holding the model inside `src/main/assets`. */
    const val ASSET_DIR = "kws"

    /** Model files Carl must place in `assets/kws/` — see `assets/kws/README.txt`. */
    val MODEL_ASSETS = listOf(
        "encoder-epoch-12-avg-2-chunk-16-left-64.onnx",
        "decoder-epoch-12-avg-2-chunk-16-left-64.onnx",
        "joiner-epoch-12-avg-2-chunk-16-left-64.onnx",
        "tokens.txt"
    )

    /** Shown in notifications and Settings when the model files are absent. */
    const val MISSING_MODEL_MESSAGE = "Wake word model missing — see docs/wake-word.md"

    /**
     * A selectable wake phrase.
     *
     * @param displayName what Carl sees in Settings, and the value stored in DataStore.
     * @param tokens the space-separated sentencepiece tokens, verified in-vocabulary.
     */
    data class WakeKeyword(val displayName: String, val tokens: String)

    /**
     * Every validated phrase. Selectable at runtime from Settings, so A/B testing a phrase is
     * a preference change rather than a rebuild.
     */
    val WAKE_KEYWORDS = listOf(
        WakeKeyword("HEY BRAIN", "▁HE Y ▁B RA IN"),
        WakeKeyword("OK BRAIN", "▁O K ▁B RA IN"),
        WakeKeyword("WAKE UP BRAIN", "▁WA KE ▁UP ▁B RA IN"),
        WakeKeyword("HEY BIG BRAIN", "▁HE Y ▁B IG ▁B RA IN"),
        WakeKeyword("HEY BRAIN BOX", "▁HE Y ▁B RA IN ▁BO X"),
        WakeKeyword("HEY BRAIN POD", "▁HE Y ▁B RA IN ▁PO D"),
        WakeKeyword("HEY BRAIN WAKE UP", "▁HE Y ▁B RA IN ▁WA KE ▁UP"),
        WakeKeyword("OK DEEP BRAIN", "▁O K ▁DE E P ▁B RA IN")
    )

    /** The phrase used when nothing is stored, or when a stored name is no longer recognised. */
    val DEFAULT_KEYWORD = WAKE_KEYWORDS.first()

    /** Resolves a stored display name to a validated entry, never returning an unknown phrase. */
    fun keywordFor(displayName: String): WakeKeyword =
        WAKE_KEYWORDS.firstOrNull { it.displayName.equals(displayName, ignoreCase = true) }
            ?: DEFAULT_KEYWORD

    /** Absolute paths handed to sherpa-onnx once the assets have been staged on disk. */
    data class KwsFiles(
        val encoder: String,
        val decoder: String,
        val joiner: String,
        val tokens: String,
        val keywordsFile: String
    )

    /**
     * Stages the model on internal storage and writes `keywords.txt` for [displayName].
     *
     * The model has to live on the filesystem rather than being read straight out of assets:
     * sherpa's asset-loading constructor resolves *every* path — `keywords.txt` included —
     * through the AssetManager, which would put the keyword list back inside the APK and undo
     * runtime selection. The file-loading constructor also validates its config and returns a
     * null pointer on failure, which surfaces as a catchable exception instead of the native
     * abort the asset path performs.
     *
     * @param threshold trigger threshold; `<= 0` means "use the model default" and omits `#`.
     * @return null when a model asset is missing — the caller must show
     *   [MISSING_MODEL_MESSAGE] rather than crash.
     */
    fun prepare(context: Context, displayName: String, threshold: Float): KwsFiles? {
        val dir = File(context.filesDir, ASSET_DIR)
        if (!dir.exists() && !dir.mkdirs()) {
            Log.e(TAG, "Could not create $dir")
            return null
        }

        if (!stageModel(context, dir)) return null

        val keyword = keywordFor(displayName)
        val keywordsFile = File(dir, "keywords.txt")
        // Built by hand rather than with any writer that might inject a BOM or CRLF.
        val line = buildString {
            append(keyword.tokens)
            // Locale.US so a locale with a comma decimal separator cannot produce "#0,35",
            // which sherpa's std::stof would silently truncate to 0.
            if (threshold > 0f) append(" #").append(String.format(Locale.US, "%.2f", threshold))
            // @phrase is whitespace-delimited, so the display name is underscored to survive it.
            append(" @").append(keyword.displayName.replace(' ', '_'))
        }
        return runCatching {
            keywordsFile.writeBytes("$line\n".toByteArray(Charsets.UTF_8))
            KwsFiles(
                encoder = File(dir, MODEL_ASSETS[0]).absolutePath,
                decoder = File(dir, MODEL_ASSETS[1]).absolutePath,
                joiner = File(dir, MODEL_ASSETS[2]).absolutePath,
                tokens = File(dir, MODEL_ASSETS[3]).absolutePath,
                keywordsFile = keywordsFile.absolutePath
            )
        }.onFailure { Log.e(TAG, "Failed to write keywords.txt: ${it.message}") }.getOrNull()
    }

    /**
     * Marker written once the whole model has been staged successfully. Bump the suffix if the
     * model itself is ever replaced, so existing installs re-stage instead of reusing the old
     * files. Its presence is what stops a ~15 MB copy running on every wake-word start.
     */
    private const val STAGED_MARKER = ".staged-gigaspeech-2024-01-01"

    /**
     * Copies the model out of assets into [dir], once. Returns false when an asset is absent
     * from the APK — the "Carl hasn't added the model yet" case, which the caller reports with
     * [MISSING_MODEL_MESSAGE].
     *
     * Assets are streamed rather than mapped because aapt may compress `.onnx`, which rules out
     * `openFd` and makes the uncompressed length unknowable without reading. A partial copy is
     * deleted and the marker withheld, so the next attempt starts clean rather than handing
     * sherpa a truncated model.
     */
    private fun stageModel(context: Context, dir: File): Boolean {
        val marker = File(dir, STAGED_MARKER)
        if (marker.isFile && MODEL_ASSETS.all { File(dir, it).let { f -> f.isFile && f.length() > 0 } }) {
            return true
        }

        for (name in MODEL_ASSETS) {
            val dest = File(dir, name)
            val assetPath = "$ASSET_DIR/$name"
            try {
                context.assets.open(assetPath).use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
            } catch (e: Exception) {
                // Most often FileNotFoundException: the model was never added to assets.
                Log.e(TAG, "Wake word asset '$assetPath' unavailable: ${e.message}")
                runCatching { dest.delete() }
                return false
            }
            if (dest.length() == 0L) {
                Log.e(TAG, "Wake word asset '$assetPath' copied empty")
                runCatching { dest.delete() }
                return false
            }
        }

        return runCatching { marker.writeBytes(ByteArray(0)); true }
            .onFailure { Log.w(TAG, "Could not write staging marker: ${it.message}") }
            // A missing marker only costs a re-copy next time, so staging still counts as done.
            .getOrDefault(true)
    }
}
