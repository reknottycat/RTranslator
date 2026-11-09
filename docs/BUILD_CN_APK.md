# Building the beta2 download-fallback APK

The starting point is the actual `3.0.0-beta2` tag, commit
`8414d8d0e4a6dcaf32e1da815e7f96b0c3de8701`. The development branch has newer
changes despite still identifying itself as beta2.

## Toolchain

Use JDK 17, the checked-in Gradle 8.12 wrapper, Android platform 36,
Android build tools 34.0.0 (the AGP 8.2.2 default), NDK 28.0.12674087, and CMake 3.22.1. The build
includes the real arm64 native libraries and recursively pinned submodules.
The CN build caps Ninja at two concurrent compile jobs and one link job;
Gradle's `--max-workers` option alone does not cap native compiler processes.

```sh
git submodule update --init --recursive
sdkmanager 'platforms;android-36' 'build-tools;34.0.0' 'ndk;28.0.12674087' 'cmake;3.22.1'
bash ./gradlew --no-daemon --max-workers=2 -PcnBuild=true :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Configure the local SDK via `ANDROID_HOME` or the untracked `local.properties`
file. The GitHub Actions workflow `Android CN APK` runs the same build and
uploads the APK together with its SHA-256, signing certificate, package
metadata, source commit and submodule revisions. Lint keeps upstream's
`abortOnError false` setting: its report is evidence to review, not a claim
that the upstream application is free of lint findings.

## Installing the result

`app/build/outputs/apk/debug/app-debug.apk` is signed with the build machine's
Android debug key and can be installed as a test APK. With `-PcnBuild=true`,
the application ID is `nie.translator.rtranslator.cn`, version name
`3.0.0-beta2-cn.1`, version code 28. It can coexist with the official app
because the application ID differs. Its storage is separate; models and
settings are not automatically copied from the official app.

This is a test build, not an upstream-signed release. Independent CI runners
can generate different debug keys, so APKs from different runs are not
guaranteed to update each other. A maintained release requires a persistent
private signing key, stored outside source control. Keep the signing key
from the original local build to produce compatible local updates.

To check an APK:

```sh
apksigner verify --verbose --print-certs app/build/outputs/apk/debug/app-debug.apk
sha256sum app/build/outputs/apk/debug/app-debug.apk
```

Host-side tests and a successful build do not establish real-device network
speed, mainland connectivity, or model inference correctness. Those require
device acceptance with the actual model files and network.
