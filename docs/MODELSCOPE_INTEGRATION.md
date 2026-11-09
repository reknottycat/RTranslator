# ModelScope-first CN test build

Verified exact mirrors: 60. All six Whisper files were fully downloaded and hashed against official GitHub assets.

Other mapped archives matched the upstream SHA-256 and exact length in ModelScope metadata and passed a nonzero HTTP byte-range check. The app checks every completed download against a pinned whole-file SHA-256 before use. These are community mirrors, not upstream-endorsed model repositories.

Priority: verified ModelScope URL, gh-proxy.org, ghproxy.imciel.com, gh-proxy.com, official GitHub. Official-first and mirrors-only remain optional. Failed domains from the user report are excluded.

4 MiB chunks, 14 default / 28 optional workers, 8 attempts per chunk, durable chunks.done, SHA-256 before atomic publish. Total concurrent chunk HTTP connections across model groups are capped at 28. Small files retain the tested PRDownloader transport.

A system file picker and live source/error logs are on the setup screen. Model files stay in their original form and filenames; ZIPs must not be manually extracted. No device-specific speed or inference acceptance is claimed.

Unmatched manifest assets: Mozilla.zip, Mozilla_zh_hant.zip. Those use proxy fallback, never a guessed mirror URL.

Evidence: reports/verified-modelscope.json and audit runs 42647267376 / 42647994639. Measured on Azure eastus, not a mainland Android device.

Debug-signed ARM64 test APK. Fresh CI debug keys may prevent in-place upgrades. Back up app data and models before uninstalling an old build.
