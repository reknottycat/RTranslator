# Isolated offline TTS prototype (Linux x86_64)

Research evidence only. No RTranslator application code or models are included.
Not an Android/SherpaTTS compatibility or listening-quality acceptance test.

## Reproduce

Use Python 3.12; prepare has network downloads, synthesis uses local resources.

```sh
python3.12 -m venv /tmp/tts-repro-venv
/tmp/tts-repro-venv/bin/pip install --no-deps -r requirements-frozen.txt
python3.12 prepare.py --work-dir /tmp/tts-repro-data
TTS_WORK_DIR=/tmp/tts-repro-data HF_HUB_OFFLINE=1 TRANSFORMERS_OFFLINE=1 /tmp/tts-repro-venv/bin/python synthesize.py
```

The tested installation used `pip install piper-tts`, `pip install --no-deps g2pw`, then `pip install unicode-rbnf transformers sentence-stream`. `g2pw` is used for lookup files only, not its Torch-based API. Frozen requirements omit Torch and omit g2pw's unused `requests` dependency; modern pip may warn about that metadata dependency. If installing the full frozen file pulls g2pw dependencies, instead install g2pw with `--no-deps`, and install the remaining frozen lines separately.

`prepare.py` checks every model SHA, extracts g2pW using Python's data filter, downloads tokenizer files, then appends the **absolute local tokenizer directory** as `model_source` to `g2pW/config.py`. No BERT model weights are needed. This avoids BertTokenizer trying to resolve `bert-base-chinese` over the network at synthesis time.

The test sets Python socket connections to raise; it is not OS-level packet capture. The original ONNX runtime warned about an unavailable telemetry-ID persistence path. The reproduction script additionally disables ORT telemetry.

## License evidence

Downloaded MODEL_CARD copies are included. `denis`: CC0 recordings, CC0 rdh base. `norman`: public-domain LibriVox recordings and trained from scratch. `chaowen`: CC0 recordings but hifi_tts_92 base uses Hi-Fi TTS CC BY 4.0: preserve attribution to Hi-Fi TTS and reader Cori Samuel, source https://www.openslr.org/109/ . Keep these notices with redistributed voices; do not treat repository MIT metadata as blanket model permission.

Official review: https://github.com/OHF-Voice/piper1-gpl/blob/main/docs/COMMERCIAL_USE.md
Piper runtime: GPLv3; this is an independent prototype, not code to merge into Apache-licensed application. g2pW port: Apache-2.0 (Piper file header; https://github.com/GitYCC/g2pW). BERT tokenizer upstream: https://huggingface.co/google-bert/bert-base-chinese (Apache-2.0).

## Results and limits

See results.json, clipping.json and wav-sha256.json. Exact int16 full-scale sample ratio is a diagnostic, not proof of audible clipping. No human pronunciation evaluation was performed. Short-sentence timings depend on cloud CPU and include first frontend initialization for Chinese; do not advertise them as phone latency. Old Piper voices with identical names can have different training/permission chains; use pinned manifest, not latest loose filenames.

Original logs and original scripts are preserved to document repaired missing Chinese dependencies. `synthesize.py` is the normalized rerunnable version. Preparation transfers about 303 MB of model resources plus small tokenizer assets, excluding Python package downloads; extraction/venv disk usage is larger.

Original metrics and WAV hashes describe the first successful run. reproduction-check.log records a later run; stochastic synthesis changes WAV hashes and durations.
