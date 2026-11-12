# Offline TTS repair and Qualcomm acceleration status

Baseline: `35a977e62234236968c8e82522040ba06b1288c3` (model-reuse repair).

## What this branch actually changes

The app uses Android's selected system TTS engine, not Whisper for speech output.
Speech selection now requires an installed, non-network voice matching the target
language. Exact region wins, otherwise another region of the same language may
be selected. A missing Russian voice is not replaced with English or a network
voice. Failed language selection/instant speak failure releases the speaking state
and restores the microphone/UI completion lifecycle. Initialization errors propagate.
Language discovery clears stale entries and excludes missing/network voices.

This does NOT install a voice pack. To use Chinese, Russian and English offline,
install a system TTS engine with those offline voices, download all three languages,
select it in Android Text to speech settings, and restart RTranslator. The existing
system settings entry is retained. A compatible optional independent engine is
[SherpaTTS](https://github.com/woheller69/ttsEngine); its GPLv3 application and every
voice's separate license must be reviewed before redistribution. Do not bundle
voices with unknown licenses (e.g. the inspected Irina model card).

## Qualcomm: not implemented / not advertised as accelerated

Current runtime is standard `onnxruntime-android:1.30.0`. The separately published
QNN Android artifact inspected was 1.29.0. Original split Whisper Small ONNX files
include dynamic shapes/cache contracts that are not a ready-to-use QNN model pack.
Merely adding a Qualcomm switch cannot make these CPU models NPU models.

Prerequisites for a real backend are a version-compatible QNN runtime; a licensed,
fixed-shape quantized model pack with the correct tokenizer/cache/input/output
contract; tested fallback after initialization and runtime errors; and actual
Snapdragon device validation. No QNN SDK/model is bundled and no always-CPU switch
is presented as acceleration. No device speed claim is made.

References:
- https://onnxruntime.ai/docs/execution-providers/QNN-ExecutionProvider.html
- https://repo.maven.apache.org/maven2/com/microsoft/onnxruntime/onnxruntime-android-qnn/maven-metadata.xml
- https://developer.android.com/reference/android/speech/tts/TextToSpeech
- https://developer.android.com/reference/android/speech/tts/Voice

## Validation and remaining gates

Eight pure Java offline-voice policy unit tests passed locally using JUnit 4.13.2
and Eclipse ECJ (cloud has a JRE but no javac/Android SDK). They verify Chinese,
Russian and English matching, exact region ranking, region fallback, low-quality
local voice eligibility, and rejection of network/missing/wrong-language/null
candidates. They do not prove Android playback or APK compilation.

Dedicated GitHub Actions workflow performs unit tests and a validation-only ARM64
build. Check its actual result; this document does not presume success.

Still required on the user's phone: record engine and voice IDs; airplane-mode
Chinese/Russian/English playback; missing-language and failed-engine error paths;
return-from-system-settings/restart behavior; existing conversation regressions.
No connected phone was assumed and no data was removed. Existing APK signer
mismatch remains an upgrade blocker: do not uninstall or clear data to install a
validation APK. No release is published by this workflow.
