# 当前实现与验证状态

当前版本 1.2.0 / Android versionCode 7（2026-11-05）。下方全量核对的日期：2026-10-29，业务代码基线：`2dcf7e1`，应用 1.1.4，Android versionCode 6；原生 WebView2 登录 host 独立版本为 1.1.1。本次审计仅核对实现并更新文档。

## 功能与代码对应

| 能力 | 当前状态 | 代码入口与边界 |
| --- | --- | --- |
| 授权码激活 | 已实现；桌面单元与 UI 测试、Android 模拟器设备测试（debug / R8）通过，真机未测，iOS 未构建验证 | 三端入口用 `LicenseGate` 包住 `TradingScreen`；`LicenseController` + `OfflineLicenseVerifier` 离线校验 PBKDF2 验证值，授权记录存 DPAPI / Keystore / Keychain，连续输错逐步锁定；无服务端吊销，见[授权码激活](license-activation.md) |
| 首次启动 | 已实现并测试 | 授权后 `TradingRuntime` 默认空注册表，`TradingScreen` 转到 `LoginWelcome`；旧模拟账本不加载、不自动删除 |
| Windows / Android 网页登录 | 已实现 | `DesktopTradovateLogin`、`AndroidTradovateLogin`；Windows Demo 只读账户/行情链路曾实测，Android 完整授权账户链路未记录为通过 |
| 多用户、Demo/Live 登录恢复 | 已实现并用合成会话测试 | `SavedTradovateAccounts`；Windows DPAPI、Android Keystore AES-GCM；过期或撤销仍需认证，不保存密码字段 |
| 主题、RGB/HEX 配色 | 已实现并测试 | `ThemeController`、`TradingTheme`、`ThemeSettingsDialog`；深/浅/系统/TradingV 主题，页面、面板、图表、K 线、盈亏等分别配置 |
| Android Material 3 导航与手机布局 | 已实现，模拟器实测 | `MobileNavigation`：行情、图表、交易、设置；图表页单行工具条，下单在 `MobileTradeBar` / `MobileOrderSheet`（Material 3 `ModalBottomSheet`，本次替换尚未设备实测），资金行移至交易/设置页，交易区按持仓、挂单、历史竖排，活动移至设置子页，见[手机布局](mobile-layout.md)；设备 UI 测试注入合成账户，真机未验证 |
| 图表缩放和平移 | 已实现并测试 | `TradingChart`、`ChartViewport`、`PriceScale`；两轴分别缩放、图内双向平移、复位 |
| 服务器行情与 K 线 | 已实现，Windows Demo 曾只读实测 | `TradovateBroker.watch/marketEvent/chartEvent`；每品种/周期请求并保留最近 2000 根（`CHART_BARS`），没有更早历史分页加载；服务器对 2000 根请求的实际返回量未实测 |
| 净额持仓、挂单、原始成交 | 已实现并用协议测试覆盖 | `TradovateBroker.rebuild` 从服务器实体映射，不生成远程替代数据 |
| 市价、Limit、Stop、撤单、全量净仓平仓 | 已实现，真实交易未联调 | `AccountSession` 命令及 Tradovate REST；受理不等于成交，不支持部分平仓命令 |
| 新订单附带 SL/TP | 已实现，真实交易未联调 | 新订单通过 `order/placeoso`；不允许在已有同合约净仓上叠加括号保护 |
| 已有保护价修改 | 有条件实现 | 仅修改唯一确认且数量匹配的保护单，每次一条；已有净仓补加/删除保护、复杂 ATM/OCO 管理未实现 |
| 挂单改价 | 有条件实现并测试 | 仅可编辑入场 Limit/Stop；保护单走独立入口，部分成交等状态拒绝编辑 |
| 费用、保证金、预计净盈亏 | 远程实现有限 | Tradovate 订单预估仅算毛盈亏，费用/预计保证金为未知；账户资金快照与订单预估是不同能力 |
| 已配对平仓历史/实现净盈亏 | 仅本地 Demo 提供 | 远程输出 `Execution`，没有配对的 `ClosedTrade`；缺少手续费核算与点击远程成交定位图表 |
| 指令结果恢复 | 部分实现，有确认缺口 | HTTP 不确定结果不重发；受理后等待事件的超时/重连对账不完整，见[审计 A1](implementation-audit.md) |
| 图表指标 | 已实现并测试 | `Indicators.kt`、`ChartIndicators.kt`、`IndicatorSettingsDialog.kt`；MA/EXPMA/BOLL 叠加，RSI/MACD/KDJ/ATR 副图，多指标、参数与颜色自定义，周期上限 1000，`indicators-v1` 本机保存；无自定义公式与按品种模板，见[图表指标](indicators.md) |
| 实时盈亏通知 | 已实现；Android 设备测试在 CI 运行，iOS 未构建验证 | `LivePnlController`、`LivePnlNotifier` + `LivePnlService` dataSync 前台服务（Android 16+ 在系统允许时提升为 Live Update / 超级岛，更早版本常驻通知）、`AppleLivePnl` + `UniTradingWidgets` 扩展（iOS 16.2+ Live Activity / 灵动岛）；仅进程存活时刷新，90 秒无刷新自动过期，见[实时盈亏通知](live-pnl-notifications.md) |
| 十字线、图层及时间缺口 | 部分实现 | 悬停、点击或长按显示虚线十字线：底轴标注所指 K 线时间（UTC），右轴标注所指价格，横线落在副图内时标注该指标值；方向键移动时只保留竖线。保护线无独立开关；K 线按序号等距排布，不展示连续时间空档 |
| iOS | 缺实现且未构建验证 | `MainViewController` 未传入 `TradovateLogin` / `CredentialStore`，空注册表启动后没有可用登录路径 |
| Rithmic R \| Protocol 接入 | 已实现，应用内入口已开启（`RITHMIC_LOGIN_ENABLED=true`）；探针在 Rithmic Test 与 Paper Trading（TPT）实测登录、账户、行情、历史；`RithmicBroker` 只读流程已在 Paper Trading 上通过，用户已在应用内完成下单、改单和撤单，并确认行情、图表和历史配对；平仓未实测 | `rithmic-broker`、`RithmicConnectionDialog`、`SavedTradovateAccounts.signInRithmic`；四个 plant、静态括号单、净额持仓；验证范围见[Rithmic 接入](rithmic.md) |
| 本地 Demo | 保留实现，默认关闭 | `demo-broker` 仍是 `composeApp` 的编译依赖，未从产物完全移除；离线测试显式启用 `includeDemoBrokers = true` |

主要源目录：[应用运行时](../composeApp/src/commonMain/kotlin/com/unitrading/app/)、[共享 UI](../composeApp/src/commonMain/kotlin/com/unitrading/ui/)、[图表](../composeApp/src/commonMain/kotlin/com/unitrading/chart/)、[Tradovate 插件](../tradovate-broker/src/commonMain/kotlin/com/unitrading/tradovate/)。

## 构建与 CI

- 共享 JVM 编译使用 JDK 21 toolchain；Kotlin 2.4.20、Compose Multiplatform 1.12.0、Gradle 9.7.1、AGP 9.4.0。
- Android compileSdk 37、targetSdk 36、minSdk 26。CI 安装包标识为 `platforms;android-37.0`；本机 SDK 路径放在不提交的 `local.properties`。
- Windows 打包需要完整 JDK 的 `jpackage.exe`。本机 JBR 21 缺少该工具，1.1.3/1.1.4 分发目录使用 Microsoft JDK 25.0.2 打包；JBR 可用于编译，但缺少打包工具。

```powershell
./gradlew.bat :composeApp:run
./gradlew.bat :composeApp:createDistributable
./gradlew.bat :androidApp:assembleDebug
./gradlew.bat :androidApp:assembleRelease
./gradlew.bat :broker-api:desktopTest :demo-broker:desktopTest :tradovate-broker:desktopTest :composeApp:desktopTest
```

Android debug 使用调试签名；本机构建 release 默认为未签名 APK。release 已启用 R8 代码压缩和资源裁剪，debug 编译成功不等同于 release 运行通过。

[GitHub workflow](../.github/workflows/build.yml) 的现有流程：

| 阶段 | 实际配置 |
| --- | --- |
| 构建 | branch push / PR / 手动执行 `:androidApp:assembleRelease`，检查 mapping/resources 输出并上传 unsigned APK |
| 签名 | main/master 或 `v*` tag 的 push 使用 secrets 签名，再上传 signed APK |
| Windows 包 | 仅 `v*` tag：`windows-latest` 执行 `:composeApp:createDistributable`，检查 exe 版本和登录 host 后压缩为 `UniTrading-<版本>-windows-x64.zip` |
| GitHub Release | 仅 `v*` tag：tag 须等于 `v<versionName>`；两个包就绪后用 `docs/release-notes.md` 的小节和提交列表创建 Release，上传 signed APK、Windows zip 与 R8 mapping |
| 通知 | 对上述发布 push 调用 Telegram；构建失败时也报告，取消运行时停止；未配置 Telegram secrets 时跳过 |
| 签名 secrets | `SIGNING_KEY_BASE64`、`ALIAS`、`KEY_STORE_PASSWORD`；`KEY_PASSWORD` 可选 |
| Telegram secrets | `TELEGRAM_BOT_TOKEN`、`TELEGRAM_CHAT_ID` |
| 测试门禁 | 全部测试移到本地；运行 `pwsh -File scripts/build-local.ps1` 执行四模块 desktop 测试、桌面打包及 Android release 构建。CI 无测试门禁，签名只依赖 Android 编译；没有 Android instrumentation job |
| iOS | 本地 Apple Silicon Mac 运行 ARM64 模拟器 Kotlin 测试及 Xcode 平台/UI 自检，见[本地构建](local-build.md)；不再启动 macOS hosted runner |
| 并发 | 同一 PR 的新运行取消旧运行；push 和手动运行按 run ID 独立分组，旧运行不会串行阻塞后续构建 |
| 超时 | Android 构建与 Windows 打包各 35 分钟，签名 / 通知 / Release 各 10 分钟；Telegram 步骤 5 分钟，连接 15 秒，上传 APK 180 秒、消息 60 秒；本地单个构建命令默认 45 分钟 |

上述 job 超时从 runner 开始执行后计时，不能限制 GitHub 分配 runner 前的排队时间。各次 push 可并行执行，仍受账户 runner 并发额度限制，通知可能按完成顺序到达。

2026-10-31 在隔离工作树验证 `fb9110d` 与本次 CI 配置修改：368 项 desktop 测试通过，0 failures、0 errors、0 skipped；Windows `createDistributable` 和 Android `assembleRelease` 成功，已检查 Windows 可执行文件、未签名 release APK、R8 mapping 和资源压缩报告。`actionlint` 及工作流 Bash 脚本语法检查通过。结果保存在本地 `artifacts/ci-fix-verification/result.json`。

上述验证在 Windows 本机执行，未验证 iOS、云端签名或 Telegram 发布。仓库目前仅使用 main；workflow 中兼容 master 的条件不代表存在 master 分支。

## 验证证据及限制

- 1.2.0 发布前回归 534 项，模拟器设备测试 debug / R8 各 5 项，打包后的 Windows 应用已实际启动，见[验证记录](verification.md)。以下条目是 2026-10-29 核对的结果。
- 1.1.4 常规测试为 **127 项**：broker-api 3、demo-broker 28、tradovate-broker 48、composeApp 48，0 失败、0 跳过。
- 本次审计重跑上述测试，并额外运行 3 个显式启用的诊断探针，合计 130 项；探针确认 A1 的缺陷现状，**通过不表示缺陷已修复**。命令及用例见[审计](implementation-audit.md)。
- Android 1.1.4 已有模拟器触摸 UI 证据：导航、主题保存/Activity 重建、两轴缩放；1.1.3 另有 Keystore 加密、篡改拒绝和跨进程读取测试。本次未重新操作设备。
- Windows Tradovate Demo 只读证据来自 1.1.1：2 个授权账户、2 个合约、155 根历史 K 线，订单请求 0。这些结果仅验证该版本的只读流程，未验证最新版本的真实交易或保存会话后的免登录流程。
- Windows 可运行目录、Android 1.1.4 debug APK 曾构建成功。真机、真实下单/改价/成交回执、iOS、Windows MSI、release APK 的设备运行均未在本次核对中验证。
- 日志、截图、报告、APK 位于被 Git 忽略的 `artifacts/` / `build/`，克隆源码不会自带这些文件。历史 APK hash 只对应当时的产物。
