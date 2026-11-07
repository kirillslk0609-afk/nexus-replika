# Exploratory host comparisons (not Android acceptance results)

These experiments informed the change; they are development data, not a blind evaluation. Final paired Android results are in `../test-results/noise-comparison.json` and `../../docs/NOISE_RESULTS.md`.

- `boundaries.json`: Whisper base INT8, changes to crop start and zero padding on the original clean/noisy Russian clip. Zero padding alone did not reliably fix noisy speech.
- `trial.json`: raw base ASR versus GTCRN-enhanced audio, 25 cases. GTCRN from https://github.com/k2-fsa/sherpa-onnx/releases/download/speech-enhancement-models/gtcrn_simple.onnx; upstream https://github.com/Xiaobin-Rong/gtcrn (MIT). It worsened several Russian clips and is not shipped.
- `spectral-trial.json`, `spectral-human.json`: base ASR with an experimental 512-point Hann STFT / 128-sample hop noise filter. Noise power estimated from the quietest fifth of frames; adjacent-frame power smoothing; gain sqrt(max(0.1, 1 - 1.5 * noise / power)). This filter is not shipped.
- `conservative.json`: the same experiment with factor 0.75 and gain floor 0.2; not shipped.
- `filter-probe.json`: parameter/low-pass probes on the original failing clip. These alone were insufficient evidence for selecting a production filter.
- `whisper-small-host.json`: 45 raw Whisper small comparisons, before the repetition retry was added. Host and Android numeric inference may differ; these numbers are not substituted for Android results.
- `retry-probe.json`: one noisy clip produced a word loop; retry with 200 ms zero padding yielded speech again. The production rule detects repeated words/phrases, not this filename or reference text.

All models ran locally. WER ignores punctuation/case, but does not expand digits or normalize Russian е/ё. The corpus and transformations are documented in `../../config/noise-fixtures.json`.
