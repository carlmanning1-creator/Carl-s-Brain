Wake word model files
=====================

The "Hey Brain" wake word uses sherpa-onnx keyword spotting (Apache-2.0). The four
files it needs are COMMITTED to this directory, so a fresh clone builds a working
wake word with no manual download step:

  encoder-epoch-12-avg-2-chunk-16-left-64.onnx   (12.2 MB)
  decoder-epoch-12-avg-2-chunk-16-left-64.onnx   (1.1 MB)
  joiner-epoch-12-avg-2-chunk-16-left-64.onnx    (0.6 MB)
  tokens.txt                                     (5 KB)

Model: sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01  (English)
Source: https://github.com/k2-fsa/sherpa-onnx/releases/download/kws-models/
            sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01.tar.bz2

They were originally gitignored as "too big to commit", which meant every checkout
silently produced an APK with a dead wake word. ~14 MB once, in a single-user repo,
is the cheaper trade.

Deliberately NOT here
---------------------

keywords.txt — the app writes its own at runtime into internal storage, from the
phrase selected in Settings > AI & Voice. That is what makes the phrase changeable
without a rebuild. A keywords.txt in assets would be ignored at best and confusing
at worst.

bpe.model — not used at runtime. It is only needed offline, to work out the token
sequence for a NEW phrase. Note it is a sentencepiece UNIGRAM model despite the
filename, so a greedy BPE tokeniser produces wrong tokens that fail silently. Every
phrase in WakeWordModel.WAKE_KEYWORDS has been verified twice: each token confirmed
present in tokens.txt, and the segmentation confirmed against bpe.model itself with
sentencepiece. Do the same before adding a phrase — see docs/wake-word.md.

The int8 variants in the upstream archive are also omitted. They would cut ~9 MB,
but the float models are what was tested on Carl's phone (accurate triggering, no
false positives across a 30-minute meeting) and that is not worth re-litigating for
APK size.

If a file here is ever missing or truncated, the app still builds and runs — the
wake word just stays inactive, with the notification reading
"Hey Brain: Wake word model missing — see docs/wake-word.md".
