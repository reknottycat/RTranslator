# Isolated Qualcomm Whisper qualification (not shipped app backend)

The official Qualcomm `Whisper-Small-Quantized` w8a16 package is obtainable without
an AI Hub account using `qai_hub_models_cli==0.66.0`:

```sh
XDG_CACHE_HOME=/your/writable/cache qai-hub-models fetch Whisper-Small-Quantized \
  --runtime precompiled_qnn_onnx --precision w8a16 \
  --chipset qualcomm-snapdragon-8-elite-for-galaxy --version 0.66.0
python inspect_whisper_pack.py /path/to/extracted/pack
```

Inspected ZIP: 285116856 bytes, SHA256
`a152488f9b668ae05f118b6e69a9d551be05e112f6947af19f70f424a33cb225`.
Download source:
https://qaihub-public-assets.s3.us-west-2.amazonaws.com/qai-hub-models/models/whisper_small_quantized/releases/v0.66.0/whisper_small_quantized-precompiled_qnn_onnx-w8a16-qualcomm_snapdragon_8_elite_for_galaxy.zip

The checked package contains genuine encoder/decoder EPContext ONNX wrappers and
338 MB of context files/metadata. It targets SM8750-AC, HTP79, SoC69, with Samsung
Galaxy S25 reference. This is not Xiaomi 15 Pro validation or a statement of
incompatibility. Verify actual device/driver compatibility before activation.

Metadata requires ORT 1.30.0 and QAIRT 2.50.0.260828221209. The original RTranslator
Whisper pack is not interchangeable: this encoder takes quantized uint16
[1,80,3000]; cross/self KV caches and decoder quantization contracts differ.
A production backend still needs the official preprocessing, tokenizer, decoder
loop/cache translation and device-pack qualification, in addition to runtime
libraries. Existing CPU models and storage are untouched.

`inspect_whisper_pack.py --open-sessions /path/to/libQnnHtp.so` can verify that
matching QNN-enabled ORT loads both contexts on an eligible host. It refuses an
absent QNN provider or silent CPU-only fallback. **Session loading is not speech
recognition**; the report keeps inference_tested=false. The current cloud CPU host
cannot execute the HTP context. No mobile NPU speed or recognition result exists.

Official performance example (not our test): the current Qualcomm perf.yaml lists
Galaxy S25 QNN-context encoder 34.353ms and decoder step 4.695ms. These are component
latencies, not microphone-to-subtitle latency or a CPU speedup ratio.
https://github.com/qualcomm/ai-hub-models/blob/main/src/qai_hub_models/models/whisper_small_quantized/perf.yaml

Official ready deployment alternative: Voice AI SDK from Qualcomm Package Manager.
Read its actual SDK license before redistribution; model metadata's Apache-2.0
label does not grant every binary runtime redistribution right.
https://github.com/qualcomm/ai-hub-models/blob/main/src/qai_hub_models/models/whisper_small_quantized/README.md
