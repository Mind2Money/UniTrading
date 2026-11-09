# 本地构建与 CI

GitHub Actions 运行 Android R8 release 编译、产物上传、签名和 Telegram 发布；`v*` 标签另外打包 Windows、创建 GitHub Release 并发送 Discord 公告。
测试和 iOS 构建在本地完成。推送所有分支、PR 和手动运行仍会编译；
只有 main/master 或 v* 标签的 push 会签名和发布。没有新增路径过滤或跳过 CI 标记。

提交前由开发者运行本地测试；GitHub 不再提供测试通过的远端门禁，也不会验证本地报告。
本地失败返回非零退出码，停止后续签名和发布。

## 环境

- Python 3.10+（仅标准库）；Windows 入口需要 PowerShell 7（`pwsh`）。
- JDK 21，建议设置 `JAVA_HOME` 并把其 `bin` 加入 PATH；项目 Gradle toolchain 为 21。
- Android SDK：`local.properties` 的 `sdk.dir` 优先，其次 `ANDROID_HOME`、`ANDROID_SDK_ROOT`。
  安装 `platform-tools`、`platforms;android-37.0`、`platforms;android-37.2` 和 build-tools。`androidApp` 按 37.2 编译，这是 GeckoView 接受的最低版本。
  SDK 命令行示例：`sdkmanager --install "platform-tools" "platforms;android-37.0" "platforms;android-37.2" "build-tools;36.1.0"`。
- Windows 桌面打包需要 .NET Framework C# 编译器；现有 native-auth 构建会下载固定版本 WebView2 并运行自测。
- iOS 必须在 Apple Silicon Mac 上安装 Xcode、命令行工具和可用 iPhone 模拟器。

## 一次完成测试和编译

从仓库根目录运行：

```powershell
pwsh -File scripts/build-local.ps1
```

Windows 默认执行四个模块的 desktopTest 和桌面打包，以及 Android R8 release 编译。
仓库根目录存在 `local-build.json` 时自动读取其中的构建选项和凭据；命令行可覆盖构建选项。
未配置文件时沿用上述默认行为，签名和 Telegram 均关闭。
测试强制重新执行；Gradle 缓存和 daemon 可继续复用。脚本不运行登录诊断或连接真实交易账户。
Android 原 CI 没有 instrumentation 测试，本脚本也不会自动在设备上安装测试应用。

终端显示本地开始时间与时区、构建选项、阶段编号、当前 Gradle 任务、阶段耗时和总耗时。
交互终端使用彩色状态与单行任务进度；每 15 秒更新一次仍在执行的命令及耗时。
重定向到文件时输出普通文本，不写 ANSI 控制码；设置 `NO_COLOR` 可关闭终端颜色。
失败时显示错误摘要、失败阶段和完整日志路径；结束时列出本次产物。
后台模式的相同进度输出保存在结果目录旁的 `.console.log` 文件。

默认产物在 `artifacts/local/<时间戳>/`，包含 `build.log`、`summary.json`、测试报告、
桌面应用目录、`UniTrading-<版本>-<提交>-unsigned.apk`、`UniTrading-<版本>-<提交>-webview-unsigned.apk`、`mapping.txt`、`resources.txt`，以及内置浏览器版的 `webview-mapping.txt`、`webview-resources.txt`。
`summary.json` 记录源提交、工作区是否有未提交改动、各阶段结果及 APK SHA-256。
单个构建命令最多执行 45 分钟；Python 入口可用 `--command-timeout <秒>` 调整。
复制的测试报告可能包含其他平台历史报告，以本次 summary 的阶段状态为执行依据。
每次使用空输出目录；Android 会先执行模块 clean，避免旧测试变体或 R8 文件冒充本次产物。

```powershell
# 仅执行指定平台；android 单独运行不包含桌面回归测试
pwsh -File scripts/build-local.ps1 -Targets android
# 明确跳过测试（不会得到测试通过结论）
pwsh -File scripts/build-local.ps1 -SkipTests
# 查看配置合并后的执行选项，不运行 Gradle，不需要签名凭据
pwsh -File scripts/build-local.ps1 -DryRun
# 忽略本地配置，使用原有默认行为
pwsh -File scripts/build-local.ps1 -NoConfig
# 后台执行；退出后只调用一次 codex queue 唤醒当前聊天
pwsh -File scripts/build-local.ps1 -Background
```

后台模式从 `CODEX_THREAD_ID` 取得当前聊天，也可传 `-NotifyThread <UUID>`。
无需 Codex 时直接运行或不传线程；回调失败保留结果和 `callback.log`，不自动重试、不轮询。
运行期间请勿让另一个构建同时清理同一工作区；不要修改正在验证的源文件。

## 本地签名与 Telegram

先生成本机配置文件：

```powershell
pwsh -File scripts/build-local.ps1 -InitConfig
```

这只创建仓库根目录的 `local-build.json`，不会构建或发送消息；文件已存在时拒绝覆盖。
文件只保存在本机，不纳入 Git。`.gitignore` 已排除根目录的 `local-build.json`、
`local-build.*.json` 和 `.local-build/`。脚本内仅有空模板，仓库不保存真实凭据。
自定义配置建议放在 `.local-build/` 或仓库外。

GitHub Secrets 的值无法从仓库或 GitHub API 反向读取，需将原有发布凭据填入本机文件的 `env` 对象。
文件使用 UTF-8 JSON（支持 BOM），不能写注释或重复键；密码中的引号和反斜杠按 JSON 规则转义。
`env` 的所有值都必须是字符串，包括 `TELEGRAM_CHAT_ID`。字段如下：

| `env` 字段 / 同名环境变量 | 用途 |
| --- | --- |
| `SIGNING_KEY_BASE64` | 与现有 CI 相同的发布 keystore 的 Base64 |
| `ALIAS` 或 `KEY_ALIAS` | 密钥别名（KEY_ALIAS 优先） |
| `KEY_STORE_PASSWORD` | keystore 密码 |
| `KEY_PASSWORD` | 可选，独立的密钥密码 |
| `TELEGRAM_BOT_TOKEN` | Telegram bot token |
| `TELEGRAM_CHAT_ID` | 接收目标 |
| `JAVA_HOME` | 可选，JDK 根目录；其 bin 会加入子进程 PATH |
| `ANDROID_HOME` / `ANDROID_SDK_ROOT` | 可选，Android SDK 路径；仍以 `local.properties` 的 `sdk.dir` 优先 |

`SIGNING_KEY_BASE64` 填已有发布 keystore 的完整 Base64 内容。`KEY_PASSWORD` 未使用时保留空字符串。
非空进程环境变量优先于文件；`KEY_ALIAS` / `ALIAS` 作为同一个字段处理，
优先级为环境 KEY_ALIAS → 环境 ALIAS → 文件 KEY_ALIAS → 文件 ALIAS。
文件中的 JDK/SDK 相对路径以配置文件所在目录为基准，不执行 shell 或环境变量插值。

`build` 对象控制流程，命令行参数优先于文件，文件优先于脚本默认值：

| `build` 字段 | 类型及含义 | 新生成文件的值 |
| --- | --- | --- |
| `targets` | 非空数组，可含 `desktop`、`android`、`ios` | Windows 为 desktop、android；Mac 另含 ios |
| `skip_tests` | 布尔值，跳过本地回归测试 | `false` |
| `sign` | 布尔值，签名并验证 Android APK | `true` |
| `notify_telegram` | 布尔值，上传已签名 APK 到 Telegram | `true` |
| `command_timeout` | 正整数，每个构建命令的超时秒数 | `2700` |

**生成的配置默认启用签名和 Telegram 发布。填写凭据后，普通运行会按配置完成测试、编译、签名和发送。**
签名要求目标包含 android，发送要求开启签名。缺少必要字段、Base64 无效或 token 格式错误时，
脚本在启动 Gradle 之前失败，并将原因写入本次 `summary.json`。未知字段和类型错误也会报错。

```powershell
# 填写 local-build.json 后，按配置发布；需要 curl
pwsh -File scripts/build-local.ps1
# 后台测试、构建、签名、发送；完成后只回调当前聊天一次
pwsh -File scripts/build-local.ps1 -Background
# 使用另一个本机配置文件
pwsh -File scripts/build-local.ps1 -Config .local-build/release.json
# 只编译 Android 并发布（不执行 desktop 回归测试）
pwsh -File scripts/build-local.ps1 -Targets android
# 覆盖配置，本次不签名、不发送；仍执行测试与编译
pwsh -File scripts/build-local.ps1 -Sign:$false -NotifyTelegram:$false
# 忽略文件，继续支持原有环境变量发布方式
pwsh -File scripts/build-local.ps1 -NoConfig -Sign -NotifyTelegram
```

Python 入口对应 `--init-config`、`--config <路径>`、`--no-config`、`--no-sign`、
`--no-notify-telegram`、`--no-skip-tests` 和 `--command-timeout <秒>`。
PowerShell 可用 `-SkipTests:$false` 恢复测试、`-CommandTimeout <秒>` 覆盖超时。

签名前检查 zipalign，签名后运行 apksigner verify；禁用独立 v4 签名文件。
临时 keystore 在签名步骤结束时删除。密码不写入命令参数或日志。
Telegram 只上传本次签名且验证通过的标准版 APK（内置浏览器版超过 Bot API 的 50 MB 上限）；API 或 HTTP 失败使构建返回非零退出码。
token 通过 curl 标准输入传入，不写命令参数或日志；服务端返回成功时摘要记录 `telegram.status: sent`，
不保存 Telegram 原始响应。整个配置对象不写入构建摘要或日志。
本地失败写入日志，不自动向 Telegram 发送失败消息。GitHub CI 仍保留原有失败通知。
本地脚本不自动推送 Git、创建 GitHub Actions artifacts 或下载 GitHub Secrets。

## Android 的两个 APK

`:androidApp:assembleRelease` 同时构建两个 flavor，只有 Tradovate 登录窗里的浏览器不同：

| flavor | 文件名 | 登录窗使用 | 体积 |
| --- | --- | --- | --- |
| `standard` | `UniTrading-<版本>-<提交>.apk` | 手机的 Android System WebView | 约 3.4 MB |
| `gecko` | `UniTrading-<版本>-<提交>-webview.apk` | APK 自带的 GeckoView，只打 arm64-v8a | 约 95 MB |

两个 APK 包名和签名相同，可以互相覆盖安装并保留数据，不能同时安装。应用内更新只更新到同一 flavor 的 APK。GitHub Release 附带两个 APK，Telegram 只推标准版。

Gradle 任务名带 flavor，例如 `:androidApp:installStandardDebug`、`:androidApp:connectedStandardDebugAndroidTest`、`:androidApp:connectedGeckoDebugAndroidTest`。x86_64 模拟器运行内置浏览器版要加 `-PgeckoAbi=x86_64`；发布包不加这个参数。

`geckoLoginExtensions` 任务把 `TradovateLoginScript` 按 Demo、Live 各生成一份扩展，放进 `assets/tradovate-login/`；登录窗打开哪个环境就安装哪一份。

GeckoView 的版本写在 `androidApp/build.gradle.kts`，内置内核不会随系统更新，发版时升到 [Mozilla Maven](https://maven.mozilla.org/maven2/org/mozilla/geckoview/geckoview-arm64-v8a/maven-metadata.xml) 的最新稳定版。新版本可能要求更高的 compileSdk。

## 发布版本

1. 把改动写进 [docs/release-notes.md](release-notes.md) 的 `# vX.Y.Z` 小节，`## 简体中文` 和 `## English` 都要有；没有小节时 Release job 失败。
2. 同时修改 `androidApp/build.gradle.kts` 的 `versionName`、`versionCode`，`composeApp/build.gradle.kts` 的 `packageVersion`，以及 `RithmicSession.DEFAULT_APP_VERSION`。
3. 本地回归。本机 `local-build.json` 默认会签名并发 Telegram，回归时关闭：

   ```powershell
   pwsh -File scripts/build-local.ps1 -Sign:$false -NotifyTelegram:$false
   ./gradlew.bat :rithmic-broker:desktopTest
   python scripts/test_release_notes.py
   python scripts/test_release_discord.py
   ```

4. 提交 `chore: release vX.Y.Z`，创建附注标签 `git tag -a vX.Y.Z -m vX.Y.Z`。
5. 先推 `main`，确认 workflow 能解析、Android 构建通过，再推标签。两次 push 各自触发一次 CI，Telegram 会收到两条消息。

标签 CI 构建并签名两个 APK、打包 Windows 和 macOS，全部成功后创建 GitHub Release，附带两个签名 APK、Windows zip、macOS zip 和两份 R8 mapping。
标签必须等于 `v<versionName>`，否则 Android 构建失败，不会创建 Release。重跑失败的 job 时，已有 Release 的正文和附件会被覆盖更新。

`createDistributable` 只打包 `build/appResources/{common,windows,windows-x64}`。Tradovate 登录 host 放在 `appResources/windows/`，
打包后的应用从 `app/resources/` 读取。JDK 模块由 `nativeDistributions.modules` 声明，新增依赖后用 `:composeApp:suggestRuntimeModules` 检查。

Compose 按字节复制 `appResources`，不保留可执行位，macOS 登录 host 由 `createDistributable` 的收尾步骤恢复。

macOS 包只有 Apple Silicon，ad-hoc 签名，未公证，只在推 `v*` 标签时打包。`build-macos` 失败会阻止 Release；Windows 回归覆盖不到 macOS 代码，这些改动要到标签运行才会被编译和测试。

### Discord 公告

Release 成功后，`notify-discord` job 用 `DISCORD_RELEASE_WEBHOOK` secret 发一条消息：版本号、Release 链接和该版本的 `## 简体中文` 小节，不带英文和提交记录。
内容超过 Discord 上限时发节选，完整说明作为附件。发版前可以预览：

```powershell
python scripts/release-discord.py vX.Y.Z --dry-run
```

未配置 secret 或 Discord 拒收（4xx）时 job 失败，已发布的 Release 不受影响；修好后重跑这个 job。

仓库公开，Release 附件人人可见，所以发送记录存成标签提交上的 commit status（context `discord-announcement/vX.Y.Z`），不上传回执文件：

| 状态 | 含义 | 重跑时 |
|------|------|--------|
| `success` | 已发送，description 含消息 ID | 跳过 |
| `failure` | Discord 拒收，消息未发出 | 重新发送 |
| `pending` | 已开始发送但未确认，消息可能已到频道 | 报错，不发送 |

`pending` 要先看频道：已有公告就把状态写成 `success`，没有就写成 `failure` 再重跑：

```bash
gh api --method POST "repos/Mind2Money/UniTrading/statuses/$(git rev-parse 'vX.Y.Z^{commit}')" -f state=failure -f context=discord-announcement/vX.Y.Z -f description='Checked the channel by hand'
```

记录跟随提交：同一版本号的标签改打到新提交后，会再发一次公告。

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
macOS 上的 desktop 目标需要 Xcode 命令行工具（`swiftc`）来编译登录组件。
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

同日新增本地 JSON 配置后，20 项脚本测试通过，覆盖配置优先级、无效配置、缺失凭据、
签名失败清理及模拟 Telegram 成功/失败响应。PowerShell 的配置加载、关闭开关和忽略配置预览已验证。
空凭据的真实入口检查在编译前返回失败，记录在 `artifacts/local-config-preflight/summary.json`；
此检查未运行 Gradle、真实签名或 Telegram 发送。根目录本机配置已通过 Git 忽略与未跟踪检查。
