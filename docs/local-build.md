# 本地构建与 CI

GitHub Actions 只运行 Android R8 release 编译、产物上传、签名和 Telegram 发布。
Windows 桌面与 iOS 的测试、应用构建移到本地。推送所有分支、PR 和手动运行仍会编译；
只有 main/master 或 v* 标签的 push 会签名和发布。没有新增路径过滤或跳过 CI 标记。

提交前由开发者运行本地测试；GitHub 不再提供测试通过的远端门禁，也不会验证本地报告。
本地失败返回非零退出码，停止后续签名和发布。

## 环境

- Python 3.10+（仅标准库）；Windows 入口需要 PowerShell 7（`pwsh`）。
- JDK 21，建议设置 `JAVA_HOME` 并把其 `bin` 加入 PATH；项目 Gradle toolchain 为 21。
- Android SDK：`local.properties` 的 `sdk.dir` 优先，其次 `ANDROID_HOME`、`ANDROID_SDK_ROOT`。
  安装 `platform-tools`、`platforms;android-37.0` 和 build-tools。
  SDK 命令行示例：`sdkmanager --install "platform-tools" "platforms;android-37.0" "build-tools;36.1.0"`。
- Windows 桌面打包需要 .NET Framework C# 编译器；现有 native-auth 构建会下载固定版本 WebView2 并运行自测。
- iOS 必须在 Apple Silicon Mac 上安装 Xcode、命令行工具和可用 iPhone 模拟器。

## 一次完成测试和编译

从仓库根目录运行：

```powershell
pwsh -File scripts/build-local.ps1
```

Windows 默认执行四个模块的 desktopTest 和桌面打包，以及 Android R8 release 编译。
测试强制重新执行；Gradle 缓存和 daemon 可继续复用。脚本不运行登录诊断或连接真实交易账户。
Android 原 CI 没有 instrumentation 测试，本脚本也不会自动在设备上安装测试应用。

终端显示本地开始时间与时区、构建选项、阶段编号、当前 Gradle 任务、阶段耗时和总耗时。
交互终端使用彩色状态与单行任务进度；每 15 秒更新一次仍在执行的命令及耗时。
重定向到文件时输出普通文本，不写 ANSI 控制码；设置 `NO_COLOR` 可关闭终端颜色。
失败时显示错误摘要、失败阶段和完整日志路径；结束时列出本次产物。
后台模式的相同进度输出保存在结果目录旁的 `.console.log` 文件。

默认产物在 `artifacts/local/<时间戳>/`，包含 `build.log`、`summary.json`、测试报告、
桌面应用目录、`UniTrading-<版本>-<提交>-unsigned.apk`、`mapping.txt`、`resources.txt`。
`summary.json` 记录源提交、工作区是否有未提交改动、各阶段结果及 APK SHA-256。
单个构建命令最多执行 45 分钟；Python 入口可用 `--command-timeout <秒>` 调整。
复制的测试报告可能包含其他平台历史报告，以本次 summary 的阶段状态为执行依据。
每次使用空输出目录；Android 会先执行模块 clean，避免旧测试变体或 R8 文件冒充本次产物。

```powershell
# 仅执行指定平台；android 单独运行不包含桌面回归测试
pwsh -File scripts/build-local.ps1 -Targets android
# 明确跳过测试（不会得到测试通过结论）
pwsh -File scripts/build-local.ps1 -SkipTests
# 查看执行选项，不运行 Gradle，不需要签名凭据
pwsh -File scripts/build-local.ps1 -DryRun
# 后台执行；退出后只调用一次 codex queue 唤醒当前聊天
pwsh -File scripts/build-local.ps1 -Background
```

后台模式从 `CODEX_THREAD_ID` 取得当前聊天，也可传 `-NotifyThread <UUID>`。
无需 Codex 时直接运行或不传线程；回调失败保留结果和 `callback.log`，不自动重试、不轮询。
运行期间请勿让另一个构建同时清理同一工作区；不要修改正在验证的源文件。

## 本地签名与 Telegram

GitHub Secrets 的值无法从仓库读取，需自行在本机进程环境配置：

| 环境变量 | 用途 |
| --- | --- |
| `SIGNING_KEY_BASE64` | 与现有 CI 相同的发布 keystore 的 Base64 |
| `ALIAS` 或 `KEY_ALIAS` | 密钥别名（KEY_ALIAS 优先） |
| `KEY_STORE_PASSWORD` | keystore 密码 |
| `KEY_PASSWORD` | 可选，独立的密钥密码 |
| `TELEGRAM_BOT_TOKEN` | Telegram bot token |
| `TELEGRAM_CHAT_ID` | 接收目标 |

```powershell
# 默认仍先运行桌面测试、打包和 Android 编译
pwsh -File scripts/build-local.ps1 -Sign
# 明确发布到 Telegram；须已配置上述变量和 curl
pwsh -File scripts/build-local.ps1 -Sign -NotifyTelegram
```

签名前检查 zipalign，签名后运行 apksigner verify；禁用独立 v4 签名文件。
临时 keystore 在签名步骤结束时删除。密码不写入命令参数或日志。
Telegram 默认关闭，只上传本次签名且验证通过的 APK；API 失败使构建返回非零退出码。
本地失败写入日志，不自动向 Telegram 发送失败消息。GitHub CI 仍保留原有失败通知。
本地脚本不自动推送 Git、创建 GitHub Actions artifacts 或下载 GitHub Secrets。

## Mac / iOS

```bash
# macOS 默认包含 desktop、android、ios
python3 scripts/build-local.py
# 只运行原 CI 的 iOS 流程
python3 scripts/build-local.py --targets ios
```

iOS 执行四个模块的 Kotlin Native 模拟器测试，随后构建模拟器应用、验证嵌入的 Keychain
entitlements 与签名，安装到模拟器并运行 native platform/UI self-test。
两个原生自测均有 90 秒超时并检查 PASS 标记。日志与 Xcode DerivedData 写入本次输出目录。
只生成模拟器应用；真机签名及 iOS 发布不在原 CI 范围内。
Windows 显式指定 ios 会失败，不会跳过后报告全平台成功。

## 本次本机验证

2026-10-31 在 Windows、JDK 21 上运行本脚本：380 项 desktop 测试通过
（broker-api 6、demo-broker 34、tradovate-broker 157、composeApp 183），
0 failures、0 errors、0 skipped；Windows 应用打包及 Android R8 release 编译通过。
结果保存在 `artifacts/local-ci-validation/summary.json`，并已实际通过一次 `codex queue` 完成回调。
对应业务源码基线为 `f273f47`，当时工作区包含尚未提交的脚本和文档。
此记录不覆盖后续其他会话的业务代码改动。iOS 与真实发布凭据、Telegram 发送未在本次验证中执行。
本地签名另使用一次性测试证书通过 zipalign、apksigner sign/verify；测试证书和测试签名 APK
已随临时目录删除，结果记录在 `artifacts/local-signing-verification.json`。6 项脚本失败路径测试通过。
