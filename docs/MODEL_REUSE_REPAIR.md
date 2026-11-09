# 会议版模型复用与国内下载回退修复

## 已确认的两个回归

`cn.3-modelscope`（`cc37a0d6`）已有 60 个精确映射的魔搭社区模型资源，下载默认魔搭优先，支持 4 MiB 分块与断点。会议版 `meeting.1`（`30e90747`）从更早的分支开发，遗漏了这套实现，仍是 GitHub 优先。会议版同时将包名由 `nie.translator.rtranslator.cn` 改成 `nie.translator.rtranslator.cn.meeting`，把旧模型隔离到了另一个应用的数据空间。模型格式本身没有因此改变。

## 本分支修复

- 在保留单机字幕和蓝牙会议代码的前提下，完整带回 `cc37a0d6` 的国内源映射、传输、分块、断点、原文件校验及相应测试。没有替换模型，也没有按名称随意拼新的模型地址。
- `cnBuild` 与 `conversationBuild` 都使用既有 CN 包名 `nie.translator.rtranslator.cn`，versionCode 32。功能标志不再创建新的应用身份。
- 下载页和模型管理页共用经过验证的文件/文件夹导入。文件夹扫描仅在用户通过系统选择器授权的目录内，限制四层/4096 项，拒绝同名冲突，只导入精确匹配官方文件名的原始文件。错误不会修改源文件。
- 下载来源、分块状态、失败原因和导入反馈使用同一日志。

## 应该从哪个文件夹导入

**你自己在浏览器/电脑下载的原始文件**：从系统文件选择器的「下载 / Download」或你实际保存的位置导入；可建立 `Download/RTranslator-models` 子文件夹，一次选择该文件夹。Whisper 保留 `.onnx` 文件原名；HY-MT 等保留原始 `.zip`，不要解压和改名。

**旧 App 自动下载并使用的模型**：本项目 `Global.USE_EXTERNAL_MEMORY_FOR_RESOURCES=false`，主要存放在旧 App 的私有 `files`，例如 `/data/user/0/nie.translator.rtranslator.cn/files`，HY-MT 等在 `files/Translation/` 内且 ZIP 可能已经删除。这不是系统文件选择器可见的 Download 目录。

旧的手动放置目录 `Android/data/nie.translator.rtranslator.cn/files/` 与私有 `files` 也不是同一个目录。Android 11 起，新包不能直接遍历其他应用私有目录或通过 SAF 选择其他应用 `Android/data`。不要宣称设置“全部文件访问”就能绕过它；不要让用户为此卸载旧 App。

## 升级发布门槛（本轮不再换包名逃避签名问题）

相同包名只是无损升级的必要条件，**还必须使用旧版兼容的签名密钥**。两次旧 CI 都使用临时 debug 签名，工作流没有持久化该私钥；从 APK 的公钥证书不能还原私钥。

本分支允许用私密环境变量 `RT_CN_KEYSTORE_PATH`、`RT_CN_STORE_PASSWORD`、`RT_CN_KEY_ALIAS`、`RT_CN_KEY_PASSWORD` 配置所有者的签名。`-PrequireCompatibleSigning=true` 在缺少密钥时立即失败；CN release 不再自动使用临时 debug 签名。发布还必须将候选 APK 的签名证书 SHA-256 与用户已装版本的证书比较。

当前构建只用于验证；**验证用 debug APK 不等于可覆盖旧版本的更新包**。不得将另一个随机签名的 APK 冒充无损更新，不得让用户删除旧模型换取安装成功。旧版还可用时继续使用旧版；仅在取得兼容密钥或完成可验证的模型导出迁移后再安装更新。

## 验证

- `:app:testDebugUnitTest` 包含既有会议、传输、导入测试以及新增魔搭优先/文件名安全/镜像映射回归测试。
- 原生 ARM64 构建与 APK 验签只证明构建产物合法，不证明与旧版本升级兼容。
- `tools/verify_cn_model_sources.py` 对六个 Whisper 文件完整下载并核验已固定 SHA-256，对其他镜像执行非零偏移范围读取。网络为云端，不能冒充中国大陆运营商真机验收。
- 尚需设备验收：SAF 文件夹授权、共享目录导入、旧版本保留数据的更新、已有解压模型复用及真实网络速度。

参考：[Android 11 存储限制](https://developer.android.com/about/versions/11/privacy/storage)、[Android 更新身份要求](https://developer.android.com/google/play/app-updates)。
