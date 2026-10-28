# UniTrading

Kotlin Multiplatform 交易终端，通过 Tradovate 登录连接服务器账户。**Windows Desktop / Android 共用交易界面；iOS 保留工程骨架，尚未在 Mac/Xcode 编译或运行。**

**本地 Demo 不使用真实资金；Tradovate Live 模式会把用户确认的指令发送至真实账户。** Windows 的 Demo 登录、账户、报价及历史已通过只读实测；没有发送订单，真实下单与成交回执仍未联调。

## 功能

- 默认无交易商和账户，首次启动直接提示登录；支持 Tradovate Demo/Live 登录及加密会话恢复。
- Android 使用 Material 3 图标底部导航；桌面保留多栏工作台。
- 深色、浅色、跟随系统主题；可单独配置页面/面板/图表背景、网格、K 线、盈亏数字和保护线颜色，RGB 选色与十六进制输入，保存后重启恢复。
- 由插件提供品种、合约规格、Bid/Ask、历史 K 线、账户、订单及成交。
- 市价、限价、突破挂单；撤单、全量平仓、独立仓位 SL/TP。
- M1/M5/M15/H1 图表、十字线、OHLC 数据表；右侧价格轴纵向滑动缩放价格，底部时间轴横向滑动缩放时间，图表自由双向平移与重置视图。
- 当前仓位和历史开/平仓标记；选中历史可查看连线。
- Tradovate 原生父子、OCO、单层 ATM 保护关联识别；挂单与图表区分入场、TP、SL 和待确认订单。
- 拖动 SL/TP 先预览，再确认提交；也可以使用价格输入框。
- Limit/Stop 草稿入场线可拖动并回填表单；已提交挂单可拖动后确认改价，保持类型、数量及 SL/TP 不变。
- 下单前显示预计净盈亏、收益/风险、保证金和双边手续费。
- 整数价格/数量/微美元账本、账户隔离、请求幂等、快照恢复。
- 桌面多栏工作台和手机导航/下单面板。

## 工程

```text
broker-api/    共享模型、定点计算、BrokerPlugin / AccountSession
demo-broker/   模拟报价、K线聚合、撮合、账户账本和快照
tradovate-broker/  登录会话、REST/WS 协议、净额期货账户与服务器订单
native-auth/   Windows WebView2 登录 host 与安全策略测试
composeApp/    Compose 共享界面、图表、桌面/iOS 入口
androidApp/    Android Activity 和应用配置
iosApp/        SwiftUI 容器、Xcode 工程
docs/          计划、插件接入与验证记录
```

依赖关系：应用入口 → composeApp → broker-api / demo-broker；demo-broker → broker-api。界面不直接修改交易账本。

## 构建要求

- JDK 21；设置 `JAVA_HOME`，或在 IDE 中选择相应 Gradle JDK。
- Kotlin 2.4.20、Compose Multiplatform 1.12.0。
- Gradle Wrapper 9.7.1，发行包 SHA-256 校验已固定。
- Android Gradle Plugin 9.4.0，Android SDK API 37；targetSdk 36，minSdk 26。
- iOS 16+，arm64 设备或 Apple Silicon 模拟器；需 macOS 与兼容的 Xcode。

Android SDK 使用 Android Studio 管理。将 SDK 路径写入本机 `local.properties` 的 `sdk.dir`，或设置 `ANDROID_HOME`；该文件不提交。首次构建需要从 Google Maven/Maven Central 下载依赖。

### Windows 桌面

在项目根目录运行；Windows PowerShell 可用 `gradlew.bat` 替代 `./gradlew`。

```bash
./gradlew :composeApp:run
```

### Android 调试 APK

```bash
./gradlew :androidApp:assembleDebug
```

输出：`androidApp/build/outputs/apk/debug/androidApp-debug.apk`。

已启动模拟器或连接设备时：

```bash
./gradlew :androidApp:installDebug
```

### 测试

```bash
./gradlew :broker-api:desktopTest :demo-broker:desktopTest :tradovate-broker:desktopTest :composeApp:desktopTest
```

包含领域测试、图表坐标测试与 Compose UI 交互测试。实际通过状态和平台限制见 [验证记录](docs/verification.md)。

### iOS（必须在 Mac 上）

在 Xcode 打开 `iosApp/iosApp.xcodeproj`，选择 `iosApp` scheme 与模拟器。项目的构建阶段会执行 `:composeApp:embedAndSignAppleFrameworkForXcode`。真机运行需自行选择签名团队。

```bash
./gradlew :broker-api:iosSimulatorArm64Test :demo-broker:iosSimulatorArm64Test :composeApp:linkDebugFrameworkIosSimulatorArm64
```

```bash
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -sdk iphonesimulator -configuration Debug CODE_SIGNING_ALLOWED=NO build
```

**当前开发环境为 Windows；iOS 工程的存在不等于已通过编译或真机验证。**

## Tradovate 登录

1. 首次启动点击“登录 Tradovate”；已连接时点击“账户 / 切换账户”→“连接 Tradovate”，选择 **Demo** 或 **Live**。
2. 点击“打开官方登录页”，只在 `trader.tradovate.com` 的内嵌浏览器中输入账号、密码及 MFA。
3. 官方网页完成签名与登录后自动交接。首次最多保留 60 秒供浏览器保存密码；已记住的登录会更快返回，也可点击“立即返回”。不再强制点击“完成登录”或等待单独行情令牌。
4. 账户与品种由服务器加载；在行情栏输入合约前缀（如 MES），点击“查询服务器合约”。只支持已加载规格的 USD 线性期货。
5. 数量单位为整数“张”；按服务器净额持仓显示，禁止将 0.01 手直接映射成期货订单。

Windows 需要 WebView2 Runtime 与系统 .NET Framework。`:composeApp:run` 会自动构建登录 host，不要求安装 .NET SDK。Android 需要较新 System WebView；自动填充依赖用户启用的系统密码/Autofill 服务，模拟器未必预装该服务。

Windows 和 Android 登录后会在本机加密保存会话，重启自动恢复仍有效的登录及上次选择的账户；续期后的会话同步更新。Windows 使用当前用户的 DPAPI，Android 使用 Keystore 密钥与 AES-GCM。支持保存多个用户及独立的 Demo/Live 登录；账户页可以重试恢复或清除全部已保存登录。会话过期或被服务器撤销后仍需重新认证。

Windows 记住密码使用 WebView2 自有 profile；Android 使用系统密码服务。密码和令牌不会写入 Demo 存档、命令行或日志。旧版本从未保存应用会话，升级到 1.1.3 后需要登录一次，之后才能自动恢复。

**限制**：网页登录成功不保证 API 或实时行情权限；受理不等于成交。超时结果未知时不自动重发，当前会话阻止继续写入，需要先在官网核对。已有保护价仅在服务器括号/OCO 关系和数量唯一匹配时允许修改；首次添加保护通过服务端 OSO；复杂、部分匹配或增删保护请在官网处理。

详细边界和证据见 [Tradovate 使用与验证](docs/tradovate.md)；网页登录后卡住的修复及只读实测见 [登录交接修复](docs/tradovate-login-fix.md)。

## 外观与图表

- 登录页或账户页打开“外观与主题”，桌面也可使用顶栏“主题”。选择深色、浅色或跟随系统；点击颜色圆点使用 RGB 滑块，或直接输入 `#RRGGBB`。保存应用，取消丢弃本次编辑。
- 页面、面板、图表和网格独立配色；上涨/下跌 K 线与盈利/亏损数字分别设置，不强制使用同一组颜色。主题设置保存在本机，切换账户不会重置。
- 在右侧价格标尺上下滑动缩放价格范围；在底部时间标尺左右滑动改变 K 线密度。拖动图表空白区域可以横向浏览历史、纵向移动价格，并留出最新 K 线右侧空间；“重置视图”回到最新 80 根并恢复自动价格范围。
- 拖动现有 SL/TP、挂单或草稿线仍走价格编辑流程，需确认的交易不会由平移/缩放手势提交。

## 数据与验证边界

- 默认运行不注册本地模拟券商，不加载、生成或覆盖旧模拟账本；未登录不会显示假账户或假行情。`demo-broker` 模块只用于离线回归测试，测试须显式启用。
- Windows 主题设置沿用本机数据目录的 `theme-v1.json`；Android 使用应用私有文件。`UNITRADING_DATA_DIR` 可将桌面测试数据隔离到其他目录。
- 已保存登录与主题分开存储；清除登录不清除主题。旧模拟文件不会被自动删除。
- 真实交易遵循 Tradovate 服务器授权与状态，图表视图及配色变化不会提交交易。
- iOS 保留工程骨架，尚未实测构建或运行。

## 文档

- [实施计划](docs/implementation-plan.md)
- [UI 与图表交互计划](docs/ui-and-chart-design.md)
- [交易商插件接入](docs/plugin-guide.md)
- [构建与验证记录](docs/verification.md)
- [主题、导航与图表交互更新](docs/theme-navigation-chart.md)
- [Tradovate 接入计划](docs/tradovate-integration-plan.md)
- [Tradovate 使用与验证](docs/tradovate.md)
