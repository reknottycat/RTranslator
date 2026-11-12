# Separate Android hardware qualification APK

Not RTranslator, not a production ASR backend, and no fake NPU setting. Uses a
separate package so it cannot replace/delete the user's app or model data. Has no
network/microphone permission and no launcher UI. Instrumentation runs synthetic
encoder and decoder inputs; it does not report a transcription or end-to-end speed.

Build: `./gradlew -PqnnProbe=true :qnn-probe:assembleDebug :qnn-probe:assembleDebugAndroidTest`

On an authorized, verified Android device, install the two test APKs. Import the
verified official pack from `tools/qnn/README.md` into this package's own external
files `whisper-pack` folder. Run `connectedDebugAndroidTest` for this module.
Never install over RTranslator CN or uninstall the user's app to work around signing.

The probe requires both contexts to execute QNN nodes in their actual profiles.
Initialization/model/runtime incompatibility must fail, never count CPU as NPU.
C++ supports the official UINT16 input/output tensors that Java's tensor creation
API does not directly expose. ORT-QNN 1.29 + QAIRT2.50 compatibility is deliberately
under test, not asserted; official model export lists ORT1.30. No phone is assumed.

Runtime dependency: official Maven `com.qualcomm.qti:qnn-runtime:2.50.0` under
https://softwarecenter.qualcomm.com/api/download/software/licenses/ai_model_hub/v1/LICENSE.pdf
Download/use is subject to those terms. Runtime object code may be incorporated in
an application, not redistributed standalone. No runtime binary is committed here.
