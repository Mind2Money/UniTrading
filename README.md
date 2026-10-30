# UniTrading

UniTrading 是用 Kotlin Multiplatform 开发的交易终端，通过 Tradovate 登录连接服务器账户。Windows Desktop 与 Android 共用交易界面；iOS 保留工程骨架，尚未在 Mac/Xcode 编译或运行。

**Tradovate Live 模式会把用户确认的指令发送至真实账户。** Windows 端曾实测 Tradovate Demo 登录及账户、报价、历史数据的只读流程，真实下单与成交回执仍未联调。当前应用版本为 1.1.4。[当前状态](docs/current-status.md) 区分已实现、未实现和未验证能力，[代码核对](docs/implementation-audit.md) 记录已知缺口。

## 功能

- 首次启动显示登录页，默认没有交易商或账户。支持 Tradovate Demo/Live 登录，并可恢复本机加密保存的会话。
- Android 底部使用带图标的 Material 3 导航。手机图表页参考 MT5 的布局，图表占据主要空间，下单面板折叠在图表下方，见 [手机布局](docs/mobile-layout.md)。桌面使用多栏工作台。
- 实时盈亏通知显示当前账户的浮动盈亏、净值和持仓数，可在账户页关闭。Android 使用常驻通知，Android 16 起支持 Live Update / HyperOS 超级岛，状态栏显示盈亏，前台服务负责后台刷新；iOS 使用灵动岛 / 锁屏 Live Activity。详见 [实时盈亏通知](docs/live-pnl-notifications.md)。
- 今日盈亏使用 Tradovate 返回的账户总盈亏快照，按券商交易日展示，附更新时间；桌面资金栏、手机交易/账户页及图表摘要均可查看，见 [今日盈亏](docs/daily-pnl.md)。
- 支持深色、浅色、跟随系统和 TradingV（TradingView 黑色配色）主题。页面、面板、图表背景、网格、K 线、盈亏数字和保护线可分别配色，支持 RGB 选色和十六进制输入，保存后重启仍有效。
- 由插件提供品种、合约规格、Bid/Ask、历史 K 线、账户、订单及成交。
- 支持市价单、限价单、突破挂单、撤单和全量净仓平仓。新订单可附带 SL/TP；已有保护单须满足关联条件才能修改。
- 图表使用 TradingView 式周期下拉，提供分钟、小时、天预设及自定义周期。收藏周期显示在桌面工具条，手机工具条只显示当前周期；品种下拉支持搜索。虚线十字线在右轴显示价格、底轴显示时间，也可打开 OHLC 数据表。
- 图表左上角的 TradingView 式悬浮图例显示品种、周期、OHLC、涨跌、Bid/Ask 下单按钮和成交量；手机买卖按钮放在图表下方的下单条。现价标签显示合约代码和当前 K 线倒计时。上下滑动右侧价格轴缩放价格，左右滑动底部时间轴缩放时间，也可双向平移图表或重置视图。
- MA、EXPMA、BOLL 叠加在 K 线上，RSI、MACD、KDJ、ATR 各占一个副图。可添加多个指标，也可为同类指标设置不同参数；参数和每条线的颜色可自定义。指标设置、图例及指标列表的收起状态保存在本机，重启后恢复，见 [图表指标](docs/indicators.md)。
- 当前仓位和成交标记；历史列表只显示已配对交易，点击展开入场、出场的数量、价格和 UTC 时间，可从明细定位图表。Tradovate 配对依据服务器记录，费用缺失时仅显示毛盈亏；同时间买卖无法确定开平顺序时明确标注。
- Tradovate 原生父子、OCO、单层 ATM 保护关联识别；挂单与图表区分入场、TP、SL 和待确认订单。
- 持仓、未成交挂单和下单草稿的 SL/TP 线都可拖动，线上标注从各自入场价算起的预计盈亏，拖动时图例实时显示；草稿松手即回填表单，持仓与挂单松手后确认才提交。也可以使用价格输入框（挂单列表的“保护价”）。
- 图表交易线标签靠右显示，邻近标签错开并连接原价位；持仓入场标签显示按当前 Bid/Ask 计算的浮动毛盈亏，与持仓列表使用同一计算口径。无报价显示“—”。
- 新订单的止损、止盈可分别勾选启用，首次勾选生成可拖动的草稿线，拖线回填价格；取消勾选隐藏并从订单中移除该保护。数量支持滑动条、输入框，手机另有加减按钮，均遵守合约数量范围和步长。
- Limit/Stop 草稿入场线可拖动并回填表单；已提交挂单可拖动后确认改价，保持类型、数量及 SL/TP 不变。
- 下单前显示收益/风险及估算；Tradovate 使用毛盈亏，费用和预计保证金标为未知。本地 Demo 测试引擎提供完整的净盈亏、费用和保证金估算。
- 价格和数量用整数表示，金额以微美元记录，各账户相互隔离。远程命令仅在当前会话内去重，重启后不保证幂等。本地 Demo 测试引擎保存账本快照，远程状态从服务器重建。
- 桌面多栏工作台支持拖动调整左栏宽度、账户区高度、下单栏宽度和底部交易区高度，记忆尺寸并支持重置，见 [桌面布局](docs/desktop-layout.md)；手机使用单行图表工具条与 Material 3 原生下单 Sheet（横竖屏统一从底部弹出，收起保留草稿）。

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

依赖关系：应用入口 → composeApp → broker-api / tradovate-broker / demo-broker；两个 broker 模块依赖 broker-api。demo-broker 默认不注册，但仍是编译依赖。界面不直接修改交易账本。

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

`run` 会先将运行所需的类、依赖与应用资源复制到系统临时目录的独立 `unitrading-run-*` 快照。应用运行期间，后续编译和测试不会替换本次启动所用的文件。正常退出后清理快照。源代码改动需重新启动应用才能生效。

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

包含领域测试、图表坐标测试与 Compose UI 交互测试。1.1.4 常规套件为 127 项；当前证据与平台限制见 [验证记录](docs/verification.md)。CI 另有 R8 release 构建、签名及 Telegram 通知配置，但尚无测试 job，见[构建与 CI](docs/current-status.md)。

### iOS（必须在 Mac 上）

在 Xcode 打开 `iosApp/iosApp.xcodeproj`，选择 `iosApp` scheme 与模拟器。项目的构建阶段会执行 `:composeApp:embedAndSignAppleFrameworkForXcode`。真机运行需自行选择签名团队。

```bash
./gradlew :broker-api:iosSimulatorArm64Test :demo-broker:iosSimulatorArm64Test :composeApp:linkDebugFrameworkIosSimulatorArm64
```

```bash
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -sdk iphonesimulator -configuration Debug CODE_SIGNING_ALLOWED=NO build
```

当前开发环境为 Windows，iOS 尚未通过编译或真机验证。

## Tradovate 登录

1. 首次启动点击“登录 Tradovate”；已连接时点击“账户 / 切换账户”→“连接 Tradovate”，选择 **Demo** 或 **Live**。
2. 点击“打开官方登录页”，只在 `trader.tradovate.com` 的内嵌浏览器中输入账号、密码及 MFA。
3. 官方网页完成签名和登录后自动返回应用。首次登录最多等待 60 秒，供浏览器保存密码；已记住的登录会更快返回，也可点击“立即返回”。无需点击“完成登录”或等待单独的行情令牌。
4. 账户与品种由服务器加载；在行情栏输入合约前缀（如 MES），点击“查询服务器合约”。只支持已加载规格的 USD 线性期货。
5. 数量单位为整数“张”；按服务器净额持仓显示，禁止将 0.01 手直接映射成期货订单。

Windows 需要 WebView2 Runtime 与系统 .NET Framework。`:composeApp:run` 会自动构建登录 host，不要求安装 .NET SDK。Android 需要较新 System WebView；自动填充依赖用户启用的系统密码/Autofill 服务，模拟器未必预装该服务。

Windows 和 Android 登录后会在本机加密保存会话，重启自动恢复仍有效的登录及上次选择的账户；续期后的会话同步更新。Windows 使用当前用户的 DPAPI，Android 使用 Keystore 密钥与 AES-GCM。支持保存多个用户及独立的 Demo/Live 登录；账户页可以重试恢复或清除全部已保存登录。会话过期或被服务器撤销后仍需重新认证。

登录与重开时，账户授权和完整快照校验通过后，在后台线程池并行加载合约规格、资金快照与行情连接。合约和资金各最多并行处理 4 项，每个连接同时在途的 REST 请求最多 4 个，保留请求间至少 750 毫秒的节流；共用合约资料复用缓存。重开最多同时恢复 3 个已保存登录，优先启动上次选择的登录，完成的连接立即显示。取消操作会取消本次并行加载；单个登录失败不阻止其他登录恢复。并行初始化完成前暂停交易。

Windows 记住密码使用 WebView2 自有 profile；Android 使用系统密码服务。密码和令牌不会写入 Demo 存档、命令行或日志。旧版本从未保存应用会话，升级到 1.1.3 后需要登录一次，之后才能自动恢复。

**限制**：网页登录成功不保证 API 或实时行情权限；订单受理后仍须等待成交确认。超时导致结果未知时，应用不会自动重发，并暂停当前会话的后续交易指令，需先到官网核对。已受理后缺失事件、重连或仓位删除的对账仍有[已确认缺口](docs/implementation-audit.md)。已有保护价仅在服务器关系和数量唯一匹配时允许修改；OSO 用于新入场订单携带保护，已有净仓补加/删除保护及复杂关联需在官网处理。

详细边界和证据见 [Tradovate 使用与验证](docs/tradovate.md)；网页登录后卡住的修复及只读实测见 [登录交接修复](docs/tradovate-login-fix.md)。

## 外观与图表

- 登录页或账户页打开“外观与主题”，桌面也可使用顶栏“主题”。选择深色、浅色、跟随系统或 TradingV（TradingView 黑色配色：页面 `#000000`、图表 `#0F0F0F`、强调 `#2962FF`、K 线 `#089981` / `#F23645`）；点击颜色圆点使用 RGB 滑块，或直接输入 `#RRGGBB`。保存应用，取消丢弃本次编辑。
- 页面、面板、图表和网格独立配色；上涨/下跌 K 线与盈利/亏损数字分别设置，不强制使用同一组颜色。主题设置保存在本机，切换账户不会重置。
- 在右侧价格标尺上下滑动缩放价格范围；在底部时间标尺左右滑动改变 K 线密度。拖动图表空白区域可以横向浏览历史、纵向移动价格，并留出最新 K 线右侧空间；“重置视图”回到最新 80 根并恢复自动价格范围（手机上为图表左下角的圆形箭头按钮）。
- 拖动现有 SL/TP、挂单或草稿线仍走价格编辑流程，需确认的交易不会由平移/缩放手势提交。

## 实时盈亏通知

- 账户页「实时盈亏通知」默认开启，关闭后立即移除通知；设置保存在本机 `live-pnl-v1`。文本变化才刷新，最快每秒一次，无变化时每 20 秒重发心跳。
- Android：Android 13+ 首次有账户可显示时请求通知权限，拒绝后静默；Android 16 起在系统允许时申请提升为 Live Update（小米 HyperOS 显示为超级岛，需在通知设置中允许本应用的实况通知）。通知由 dataSync 前台服务持有，应用进入后台仍持续刷新；Android 15+ 该类服务每日最多 6 小时，超时后通知移除，重新打开应用恢复。
- iOS：需要 iOS 16.2 及用户允许实时活动；只在应用前台时刷新，锁屏与灵动岛在 90 秒无刷新后标记为过时。iOS 工程仍未在 Mac/Xcode 构建验证。

## 数据与验证边界

- 默认运行不注册本地模拟券商，不加载、生成或覆盖旧模拟账本；未登录不会显示假账户或假行情。`demo-broker` 保留为编译依赖和离线测试引擎，测试须显式启用。
- Windows 主题设置沿用本机数据目录的 `theme-v1.json`；Android 使用应用私有文件。`UNITRADING_DATA_DIR` 可将桌面测试数据隔离到其他目录。
- 已保存登录与主题分开存储；清除登录不清除主题。旧模拟文件不会被自动删除。
- 真实交易遵循 Tradovate 服务器授权与状态，图表视图及配色变化不会提交交易。
- iOS 保留工程骨架，尚未接入网页登录和凭据存储；默认无交易商时没有可用登录路径，也尚未实测构建或运行。

## 文档

- [文档索引](docs/README.md)
- [当前实现与验证状态](docs/current-status.md)
- [代码与文档核对](docs/implementation-audit.md)
- [实施计划](docs/implementation-plan.md)
- [UI 与图表交互计划](docs/ui-and-chart-design.md)
- [交易商插件接入](docs/plugin-guide.md)
- [构建与验证记录](docs/verification.md)
- [主题、导航与图表交互更新](docs/theme-navigation-chart.md)
- [实时盈亏通知](docs/live-pnl-notifications.md)
- [Tradovate 接入计划](docs/tradovate-integration-plan.md)
- [Tradovate 使用与验证](docs/tradovate.md)
