# RTranslator 3.0.0-beta2 国内下载兜底补丁

## 本次范围

按最终确认的简化方案，保留原有 PRDownloader、模型管理页面、下载通知和推理引擎，只为现有模型资源加入代理兜底，并恢复旧版的外部文件导入兼容。没有新增 Gradle 模块，也没有换成 12 线程分片下载引擎。

构建基于正式 `3.0.0-beta2` 标签，提交 `8414d8d0e4a6dcaf32e1da815e7f96b0c3de8701`。当前上游 `development` 和 `v3.00` 已有标签之后的改动，不能只凭 `versionName` 判断源码相同。

## 下载来源与失败兜底

每个模型保留原始官方 URL 作为下载身份。实际传输依次尝试：

1. 官方 GitHub Release 地址。
2. 在原始地址前拼接 `https://gh-proxy.org/`。
3. 在原始地址前拼接 `https://ghproxy.imciel.com/`。

覆盖两个上游资源仓库：

| 上游资源 | 内容 |
| --- | --- |
| `niedev/RTranslator` 的 `2.0.0` Release | beta2 继续使用的 Whisper ONNX 文件；保留的旧 NLLB 清单也有元数据 |
| `niedev/OnnxModelsEnhancer` 的 `v1.0.0-beta` Release | HY-MT、Madlad、Mozilla 语言包、TranslationDictionaries、Tatoeba |

固定元数据来自官方 GitHub Release API，共 62 项，其中包括应用列出的全部 46 个 Mozilla 语言包。大小使用精确字节数，不使用原界面中供进度估算的近似 KB。新版 ZIP 使用上游提供的 SHA-256；旧版 ONNX 没有公开的 Release digest，因此使用精确大小并保留原有 ONNX 加载检查。

适配器检查 HTTP 状态、返回类型、长度和断点范围；返回 HTML、错误长度或错误范围的来源不会被当作模型接受。下载中断后可以在剩余来源中续传，尝试次数有限。原始 URL、下载 ID 和断点数据库保持一致。代理仅用于这份固定的公开模型清单，不会收到应用认证头或 Cookie。

`gh-proxy.com` 没有作为第三个代理加入：2026-11-09 的小范围探测中，它返回了 HTML。`.org` 与 `.com` 也不能视为两个独立运营服务。

## 外部模型文件复用

2.x 会扫描应用外部目录，将同名模型检查后导入内部目录；beta2 原始代码取消了扫描，但仍写入“将模型放在此目录”的说明。这是旧导入方式失效的原因。

本补丁恢复官方资源的文件复用，不增加新导入页面。操作方法：

1. 启动一次 `RTranslator CN`，使应用创建自己的目录。
2. 将完整、原名的官方资源文件放入应用的外部 files 目录，通常为：

   ```text
   Android/data/nie.translator.rtranslator.cn/files/
   ```

3. 回到应用，点击相应模型的下载或重试。应用先尝试已有文件，再使用网络来源。

Whisper 文件保留原 `.onnx` 文件名。翻译模型使用原始 `HY-MT.zip`、`Madlad.zip` 或 `Mozilla_<语言代码>.zip`；辅助资源使用原始 `TranslationDictionaries.zip`、`Tatoeba.zip`。不要手动解压、改名，或使用其他项目导出的同名模型代替。

导入经过检查，并通过临时文件和原子移动写入内部目录。复制失败、取消和无效文件不会破坏源文件；成功后也保留外部原文件。2.0 的 NLLB 翻译模型不替代 3.0 的翻译模型。

## 安装包

使用 `-PcnBuild=true` 构建时：

| 项目 | 值 |
| --- | --- |
| 显示名称 | RTranslator CN |
| 包名 | `nie.translator.rtranslator.cn` |
| 版本 | `3.0.0-beta2-cn.1`，versionCode 28 |
| CPU | arm64-v8a |
| 最低系统 | Android 9 / API 28 |

此包可与官方版共存。两者的应用数据和模型目录独立。构建工具、签名和复现命令见 [BUILD_CN_APK.md](BUILD_CN_APK.md)。

## 已验证范围与实际使用边界

单元测试针对实际传输适配器和文件导入逻辑，覆盖错误响应、换源、断点数据、文件完整性及取消等边界。构建包含真实原生库，不使用替代 `.so`。最终交付同时提供 APK 校验值与构建证据。

代理探测来自当前云端出网，不能代表中国大陆每个运营商的可达性，也不构成持续带宽承诺。本补丁没有进行大陆实体设备上的完整多 GB 模型下载或翻译速度验收。需要在实际手机或平板上完成该部分确认。

## 来源

- [beta2 官方说明](https://github.com/niedev/RTranslator/discussions/211)
- [固定源码标签](https://github.com/niedev/RTranslator/tree/3.0.0-beta2)
- [2.0 模型资源元数据](https://api.github.com/repos/niedev/RTranslator/releases/tags/2.0.0)
- [beta2 新模型资源元数据](https://api.github.com/repos/niedev/OnnxModelsEnhancer/releases/tags/v1.0.0-beta)
- [GH-Proxy 使用文档](https://gh-proxy.com/docs/github-accelerator)
- [imciel 代理说明](https://ghproxy.imciel.com/)


## 2026-11-09: CN beta2.2 manual file import and source diagnosis

On the initial model-download screen:

- **从手机文件导入模型** opens Android's system document picker (multiple files). Choose unmodified model assets with their original filenames, such as `Whisper_cache_initializer.onnx`, `HY-MT.zip`, `Madlad.zip` or `Mozilla_zh.zip`. The files are validated against the exact official release size and SHA-256 where available, staged atomically, and then recognized by the existing download manager; do **not** rename or unzip them.
- **切换下载源** has three modes: **国内镜像优先** (default), **GitHub 官方优先**, or **仅使用加速镜像**. The downloader displays the current provider, file name, connection state, real transferred bytes, and failure/fallback diagnostics. **查看日志** expands chronological events; **复制日志** makes it easy to send a failure report.
- The CN priority order is `ghfast.top`, `ghproxy.net`, `gh-proxy.org`, `ghproxy.imciel.com`, then the original GitHub asset. These are third-party GitHub proxy endpoints, not official Chinese domestic copies of the models. Reachability, range support, throughput and availability vary by ISP and asset size. This version uses the existing PRDownloader transport, not a separate download engine.
- Opening the picker pauses the downloader. After selecting or cancelling, the existing download manager resumes; successful picker imports are reused before any network fetch. Invalid file names, sizes and hashes are rejected and recorded in the log.
- When a proxy supplies a chunked full-file HTTP 200 without Content-Length, the fixed official model size is used as the upper bound and expected final length; official SHA-256 is still checked for assets that publish it.
- The GitHub Actions APK is debug-signed on a fresh runner and **may not be signature-compatible** with beta2-cn.1. Back up data/models and uninstall the previous CN build before installing beta2-cn.2 if Android reports a signature conflict. This is a test build, not a production signing setup.

For a model download that stays at 0 bytes, open the log and look for `尝试来源` and `下载来源失败` entries. Copy the log without exposing device credentials.
