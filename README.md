# UniTrading

Kotlin Multiplatform 交易终端，包含本地 Demo 与 Tradovate 插件。**Windows Desktop / Android 共用交易界面；iOS 保留工程骨架，尚未在 Mac/Xcode 编译或运行。**

**本地 Demo 不使用真实资金；Tradovate Live 模式会把用户确认的指令发送至真实账户。** 本轮已验证离线协议与平台构建，真实登录、行情授权和服务器成交仍需用获授权账户联调。

## 功能

- 接口注册式交易商：两组本地 Demo（各两个账户），以及 Tradovate 网页登录接入。
- 由插件提供品种、合约规格、Bid/Ask、历史 K 线、账户、订单及成交。
- 市价、限价、突破挂单；撤单、全量平仓、独立仓位 SL/TP。
- M1/M5/M15/H1 图表、十字线、缩放/浏览、OHLC 数据表。
- 当前仓位和历史开/平仓标记；选中历史可查看连线。
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

1. 点击“账户 / 切换账户”→“连接 Tradovate”，选择 **Demo** 或 **Live**。
2. 点击“打开官方登录页”，只在 `trader.tradovate.com` 的内嵌浏览器中输入账号、密码及 MFA。
3. 官方网页完成签名与登录；应用自动接收本窗口的账户/行情令牌。可先处理保存密码提示，再点击“完成登录”。
4. 账户与品种由服务器加载；在行情栏输入合约前缀（如 MES），点击“查询服务器合约”。只支持已加载规格的 USD 线性期货。
5. 数量单位为整数“张”；按服务器净额持仓显示，禁止将 0.01 手直接映射成期货订单。

Windows 需要 WebView2 Runtime 与系统 .NET Framework。`:composeApp:run` 会自动构建登录 host，不要求安装 .NET SDK。Android 需要较新 System WebView；自动填充依赖用户启用的系统密码/Autofill 服务，模拟器未必预装该服务。

登录令牌只在内存和进程管道/受限 WebMessage 中传递。Windows 记住密码使用 WebView2 自有 profile；Android 使用系统密码服务。不会把密码或 token 写进 Demo 存档、命令行或日志。

**限制**：网页登录成功不保证 API 或实时行情权限；受理不等于成交。超时结果未知时不自动重发，当前会话阻止继续写入，需要先在官网核对。已有保护价仅在服务器括号/OCO 关系和数量唯一匹配时允许修改；首次添加保护通过服务端 OSO；复杂、部分匹配或增删保护请在官网处理。

详细边界和证据见 [Tradovate 使用与验证](docs/tradovate.md)。

## 演示操作

1. 从交易商/账户区域切换账号，在行情列表选择品种。
2. 在下单面板选择方向、类型、数量；可设置参考 SL/TP，再自行调整。
3. 查看预计净结果和图表草稿，点击“检查并模拟买入/卖出”，确认后才执行。
4. 拖动 Limit/Stop 草稿入场线可回填价格；已提交挂单从图表或挂单列表“入场价”修改，确认后才生效。
5. 在持仓列表选择仓位，拖动图表 SL/TP 或点击“保护价”；确认前原保护价不变。
6. 平仓后在历史中点击成交，定位到图表；其他账户不能看到该笔持仓/历史。
7. 模拟时钟每真实秒前进 15 秒，可暂停。退出期间不模拟真实市场，也不补造离线成交。

## 保存与限制

- Windows 默认数据：`%LOCALAPPDATA%/UniTrading/demo-v1`。
- Android：应用私有 `files/demo-v1`；iOS：应用 UserDefaults。
- 交易操作后保存，行情约每 5 秒保存；桌面正常退出时再保存。
- 自动化演示可设置 `UNITRADING_DATA_DIR` 指向独立目录，不污染正常用户存档。
- 保存失败会暂停行情并显示警告；损坏存档不会静默覆盖，重置需要确认。
- 重置只影响本地模拟数据。首次演示仓位和历史来自同一模拟撮合引擎，恢复存档时不重复注入。
- 本地 Demo 只支持 USD 线性合约与对冲持仓；Tradovate 使用服务器净额期货仓位，费用/保证金未知时明确显示未知。均未实现外币换算。
- 挂单不预占保证金，触发时再次校验；跳空按实际模拟报价执行，止损结果不保证等于预估。

## 文档

- [实施计划](docs/implementation-plan.md)
- [UI 与图表交互计划](docs/ui-and-chart-design.md)
- [交易商插件接入](docs/plugin-guide.md)
- [构建与验证记录](docs/verification.md)
- [Tradovate 接入计划](docs/tradovate-integration-plan.md)
- [Tradovate 使用与验证](docs/tradovate.md)
