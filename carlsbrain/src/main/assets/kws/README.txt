Wake word model files — place them in THIS directory
====================================================

The "Hey Brain" wake word uses sherpa-onnx keyword spotting (Apache-2.0). The model
is not committed to git because it is ~15 MB of binaries, so it has to be added by
hand once per checkout.

Model: sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01  (English)

Download it from either of:

  https://github.com/k2-fsa/sherpa-onnx/releases  (search the release assets for
      "sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01")
  https://www.modelscope.cn/models/pkufool/sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01

Unpack it and copy exactly these four files into carlsbrain/src/main/assets/kws/,
flat — do NOT keep the model's own subdirectory:

  encoder-epoch-12-avg-2-chunk-16-left-64.onnx
  decoder-epoch-12-avg-2-chunk-16-left-64.onnx
  joiner-epoch-12-avg-2-chunk-16-left-64.onnx
  tokens.txt

That is all. Do not add a keywords.txt here — the app writes its own at runtime into
internal storage from the phrase selected in Settings > AI & Voice, which is what
makes the phrase changeable without a rebuild.

bpe.model is not needed at runtime either. It is only useful offline, for working out
the token sequence of a NEW phrase — and note it is a sentencepiece UNIGRAM model
despite the filename, so a greedy BPE tokeniser produces wrong tokens that fail
silently. See docs/wake-word.md before adding a phrase.

Until these files are present the app still builds and runs; the wake word simply
stays inactive and its notification reads
"Hey Brain: Wake word model missing — see docs/wake-word.md".
