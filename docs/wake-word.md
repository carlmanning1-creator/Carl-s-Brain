# Wake word — sherpa-onnx keyword spotting

## Why this changed

Picovoice terminated its free tier on 30 June 2026. Porcupine no longer initialises at
all, so the "Hey Brain" wake word was permanently dead. It has been replaced with
**sherpa-onnx** keyword spotting (k2-fsa, Apache-2.0), which runs entirely on device with
no account, no access key and no activation server.

The Picovoice access key field has been removed from Settings and the
`picovoiceAccessKey` preference is gone. The old `Hey-Brain_en_android_v4_0_0.ppn` asset
has been deleted.

## Model files (must be added by hand)

Model: `sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01` (English).

Sources:

- <https://github.com/k2-fsa/sherpa-onnx/releases> — look for the model in the release assets
- <https://www.modelscope.cn/models/pkufool/sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01>

Copy these four files, flat, into `carlsbrain/src/main/assets/kws/`:

| File | Purpose |
| --- | --- |
| `encoder-epoch-12-avg-2-chunk-16-left-64.onnx` | streaming zipformer2 encoder |
| `decoder-epoch-12-avg-2-chunk-16-left-64.onnx` | transducer decoder |
| `joiner-epoch-12-avg-2-chunk-16-left-64.onnx` | transducer joiner |
| `tokens.txt` | token id table — every token in `keywords.txt` must appear here |

They are not committed (≈15 MB of binaries). `carlsbrain/src/main/assets/kws/README.txt`
repeats these instructions next to the directory itself.

**The app builds and runs without them.** `WakeWordModel.prepare()` returns null when an
asset is absent and `VoiceCaptureService` shows
`Hey Brain: Wake word model missing — see docs/wake-word.md` in its foreground
notification. Nothing crashes, nothing fails silently, and the rest of the app is
unaffected.

`bpe.model` is **not** needed at runtime. It is only useful offline, for tokenising a new
phrase.

## Gotcha 1 — `bpe.model` is UNIGRAM, not BPE

Despite the filename, the model's `bpe.model` is a sentencepiece **unigram** model. Running
a greedy BPE tokeniser over a phrase produces token sequences that look plausible, are
accepted by the build, and **never trigger** — a completely silent failure that costs hours
to diagnose.

Every phrase must be tokenised with sentencepiece using that exact model, and every
resulting token must then be checked against `tokens.txt`. An out-of-vocabulary token makes
sherpa's native `EncodeKeywords` fail, and the file-loading path answers that with
`SHERPA_ONNX_EXIT(-1)` — a **process abort**, not an exception. That is why
`WakeWordModel.keywordFor()` always resolves the stored preference back to a known-good
entry instead of trusting arbitrary text.

## Gotcha 2 — `keywords.txt` encoding

`keywords.txt` must be **UTF-8, no BOM, LF line endings**. CRLF leaves a `\r` glued to the
final token of each line, which then never matches — again silently. The app writes the
file itself with `writeBytes(...toByteArray(Charsets.UTF_8))` and an explicit `\n` so no
editor or writer can inject either.

## Per-line tuning syntax

Each line of `keywords.txt` is whitespace-separated. Tokens found in `tokens.txt` build the
keyword; the three prefixed extras may appear in any order after them:

| Syntax | Meaning |
| --- | --- |
| `:2.0` | boosting score for this keyword (raises detection likelihood) |
| `#0.35` | trigger threshold (probability) for this keyword |
| `@phrase` | display string reported back in the result |

Example:

```
▁HE Y ▁B RA IN :2.0 #0.35 @HEY_BRAIN
```

`@phrase` is whitespace-delimited, so the display name is written with underscores in place
of spaces.

## Validated tokenisations

All confirmed in-vocabulary and triggering on a real device. `HEY BRAIN` is the default and
the most reliable.

| Phrase | Tokens |
| --- | --- |
| `HEY BRAIN` | `▁HE Y ▁B RA IN` |
| `OK BRAIN` | `▁O K ▁B RA IN` |
| `WAKE UP BRAIN` | `▁WA KE ▁UP ▁B RA IN` |
| `HEY BIG BRAIN` | `▁HE Y ▁B IG ▁B RA IN` |
| `HEY BRAIN BOX` | `▁HE Y ▁B RA IN ▁BO X` |
| `HEY BRAIN POD` | `▁HE Y ▁B RA IN ▁PO D` |
| `HEY BRAIN WAKE UP` | `▁HE Y ▁B RA IN ▁WA KE ▁UP` |
| `OK DEEP BRAIN` | `▁O K ▁DE E P ▁B RA IN` |

These live in `WakeWordModel.WAKE_KEYWORDS`
(`carlsbrain/src/main/java/com/carlmanning/carlsbrain/data/voice/WakeWordModel.kt`).

## The phrase is selectable at runtime

Nothing is hardcoded to a single keyword. **Settings → AI & Voice → Hey Brain** offers:

- a **phrase dropdown** listing the validated tokenisations above
- a **threshold slider**, range 0–0.60. `0` means "use the model default" and omits `#`
  entirely; anything below 0.10 snaps back to 0. Higher means fewer false triggers but more
  misses.

Changing either sends `ACTION_RESTART_WAKE_WORD`, which recycles the listening loop so
`keywords.txt` is rewritten from the new preference — it takes effect immediately, no app
restart. That is a distinct action rather than a STOP followed by a START, for two reasons:
`startWakeWordLoop()` returns early while a loop is already listening (so a bare START is a
no-op), and a STOP+START pair races — the START can launch a second audio thread while the
first still owns the `AudioRecord`. The restart branch instead just clears `isListening` and
lets the running loop's own `finally` block bring it back 1.5 s later.

A/B testing a phrase is therefore a preference change rather than a rebuild cycle.

## How it is wired up

`keywords.txt` is written at runtime into `filesDir/kws/`, and the four model assets are
staged alongside it on first use. The model has to be on the filesystem rather than read
straight from assets: sherpa's asset-loading constructor resolves *every* path through the
`AssetManager`, `keywords.txt` included, which would put the keyword list back inside the
APK and undo runtime selection. The file-loading constructor (`assetManager = null`) also
validates its config and returns a null pointer on failure, which Kotlin's `require` turns
into a catchable exception — as opposed to the native abort the asset path performs.

API used, all verified against the sherpa-onnx sources at
`sherpa-onnx/kotlin-api/` and the `android/SherpaOnnxKws` demo:

- `KeywordSpotter(assetManager, config)`, `createStream()`, `isReady()`, `decode()`,
  `getResult()`, `reset()`, `release()`
- `KeywordSpotterConfig(featConfig, modelConfig, keywordsFile, …)`
- `OnlineModelConfig(transducer, tokens, modelType = "zipformer2", numThreads, provider)`
- `OnlineTransducerModelConfig(encoder, decoder, joiner)`
- `getFeatureConfig(sampleRate = 16000, featureDim = 80)`
- `OnlineStream.acceptWaveform(FloatArray, sampleRate)`, `release()`

Audio: 16 kHz mono PCM 16-bit, read 100 ms at a time (1600 samples) and converted to
floats in `[-1, 1]`. Porcupine dictated a fixed `frameLength`; sherpa accepts whatever
buffer it is handed. RMS of each buffer is still computed and recorded with the trigger, and
only the samples actually returned by `AudioRecord` are included.

A detection stays in the result until the stream is reset, so `reset()` is called
immediately on a hit — otherwise every later decode would report the same one.

Everything else in `VoiceCaptureService` is unchanged: the
`wakeWordActive`/`isListening`/`isConversationActive` state machine, all four `ACTION_*`
branches and the null-intent restart, the `AudioRecord` read-error handling
(`ERROR_DEAD_OBJECT`, consecutive-error backoff, retry path), `AcousticEchoCanceler` and
`NoiseSuppressor` attach/release, and the `isAppSpeaking()` gate that stops the app hearing
its own TTS say "brain".

## Trigger log

The diagnostics ring buffer source constant was renamed `PORCUPINE` → `KWS`
(`UserPreferences.TRIGGER_SOURCE_KWS`). Entries logged under the old name simply show as
`PORCUPINE` until they age out of the 20-entry buffer.

## Dependency

```kotlin
implementation("com.k2fsa.sherpa.onnx:sherpa-onnx-android:1.13.5")
```

⚠️ **This coordinate is unverified.** 1.13.5 is the current sherpa-onnx release, but the
official `android/SherpaOnnxKws` demo does not use a Maven dependency at all — it vendors
the Kotlin API sources and the prebuilt `.so` files directly. Check Maven Central and adjust
the coordinate or version if it does not resolve.

Fallback if it does not resolve: download the prebuilt `sherpa-onnx-<version>-android.aar`
from the k2-fsa GitHub release, drop it in `carlsbrain/libs/`, and swap the line above for

```kotlin
implementation(fileTree("libs") { include("*.aar") })
```

The `packaging { jniLibs { useLegacyPackaging = true } }` block in
`carlsbrain/build.gradle.kts` was originally added for Porcupine's unaligned `.so`. It is
now possibly removable, but it affects every native library in the APK, so it has been left
in place deliberately and flagged rather than removed as a side effect of this change.
