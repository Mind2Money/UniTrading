# 验证记录索引

## 当前：1.3.0 / v1.3.0

2026-11-06 发布前在 Windows、JDK 21 上核对 `7ba10fd` 加版本号改动。标签提交在此基础上只改本记录、发布说明和其他文档的文字。

- 桌面回归 592 项：broker-api 6、demo-broker 34、tradovate-broker 213、composeApp 269、rithmic-broker 70，0 失败，1 项跳过（`Pbkdf2JdkTest` 需要环境变量提供授权码）。`scripts/build-local.ps1` 关闭签名和 Telegram 执行，`rithmic-broker` 单独运行。
- 脚本测试：`test_build_local.py` 20 项、`test_release_notes.py` 5 项、`test_release_discord.py` 15 项通过。
- Android：R8 release APK 构建通过；`Medium_Phone`（API 36）模拟器上 debug 与 R8 release 设备测试各 5 项通过。
- Windows 包：`UniTrading.exe` 版本为 1.3.0，含登录 host。从仓库外目录启动，应用数据目录隔离，只放入授权记录，通过授权校验后显示登录页和 Tradovate、Rithmic 两个入口；这次检查没有连接任何账户。
- Discord：webhook 查询成功，空消息被拒收（HTTP 400），没有发送真实消息。真实发送和 commit status 写入以 v1.3.0 的 CI 结果为准。
- 未验证：真机、Tradovate 真实下单、改价和成交回执、Rithmic 平仓、iOS、MSI 安装包。

## 1.2.0 / v1.2.0

2026-11-05 发布前在 Windows、JDK 21 上核对 `4847806`。标签提交只在此基础上改动本记录与发布说明的文字。

- 桌面回归 534 项：broker-api 6、demo-broker 34、tradovate-broker 205、composeApp 246、rithmic-broker 43，0 失败，1 项跳过（`Pbkdf2JdkTest` 需要环境变量提供授权码）。`scripts/build-local.py` 关闭签名和 Telegram 执行，`rithmic-broker` 单独运行。
- 脚本测试：`test_build_local.py` 20 项、`test_release_notes.py` 5 项通过。
- Android：R8 release APK 构建通过；`Medium_Phone`（API 36）模拟器上 debug 与 R8 release 设备测试各 5 项通过。
- Windows 包：`createDistributable` 含登录 host，登录 host 自测通过，运行时含 `suggestRuntimeModules` 列出的模块，zip 约 73 MB。从仓库外目录启动打包后的 `UniTrading.exe`，恢复了已保存的 Tradovate Demo 会话，账户、行情、图表和持仓正常显示，没有发送订单。之前的分发目录不含登录 host，打包版无法打开登录窗口；这次没有在打包版里实际走完网页登录。
- Rithmic 四个入口的隐藏由桌面 UI 测试覆盖：登录页、设置、账户切换和空合约页。
- 未验证：真机、真实下单、改价和成交回执、iOS、MSI 安装包；`windows-latest` 打包与 Release job 的首次运行以 v1.2.0 的 CI 结果为准。

## 1.1.4 / 2dcf7e1

2026-10-29 核对：常规套件 127 项（broker-api 3、demo-broker 28、tradovate-broker 48、composeApp 48），全部通过。本次另用 3 个显式启用的审计探针确认指令等待/重连对账缺口；探针通过表示复现成功，不表示业务缺口已修复。详见[实现核对](implementation-audit.md)。

- Windows 可运行目录、Android 1.1.4 debug APK 已构建；模拟器 UI 已验证主题、导航、两轴缩放和 Activity 重建后的主题恢复，见[1.1.4 记录](theme-navigation-chart.md)。
- 加密会话保存、DPAPI / Keystore、篡改拒绝与恢复测试见[1.1.3 记录](saved-login-fix.md)；这些测试未覆盖真实账户长期免登录的情况。
- Tradovate Demo 真实只读账户、报价和历史证据来自[1.1.1 登录交接记录](tradovate-login-fix.md)。真实下单、改价及成交回执尚未联调。
- Android release/R8、CI 签名和 Telegram 流程有实现；其配置及测试门禁缺口见[当前状态](current-status.md)。不以 debug 测试代替 release 验收。
- iOS 同时缺少登录实现与 Mac 构建验证；真机、MSI 和本次云端发布状态未验证。

下面保留初版本地 Demo 的原始记录。52 项测试、hash、模拟账户流程和“未接入真实交易商”等描述只对应当时版本，不是当前状态。

## 历史：首版本地 Demo 验证记录

日期：2026-10-28。按用户最新范围验收 Windows Desktop 与 Android，并增加 Limit/Stop 草稿及已挂单入场线拖动。iOS 仅保留工程骨架，未在 Mac/Xcode 编译或运行。

## 环境与构建

- Windows 11 x64，构建 JBR 21。
- Kotlin 2.4.20，Compose Multiplatform 1.12.0，Gradle 9.7.1，AGP 9.4.0。
- Android compileSdk 37、targetSdk 36、minSdk 26。
- Android 实测：`Medium_Phone` x86_64 模拟器，Android 16 / API 36，1080×2400，420 dpi。

```bash
./gradlew :broker-api:desktopTest :demo-broker:desktopTest :composeApp:desktopTest :androidApp:assembleDebug
```

最终构建结果：`BUILD SUCCESSFUL`，52 项自动测试通过，0 failures、0 errors、0 skipped（broker-api 3 项、demo-broker 28 项、composeApp 21 项）。APK 路径：`androidApp/build/outputs/apk/debug/androidApp-debug.apk`。该 APK 使用本地调试签名，尚未使用正式发布签名。

交付 APK 的 SHA-256：`9f54dd3164c9db2e28a87391d96800eddac93818738cb87f76cdc2165e8555a1`。

## 自动化覆盖

| 测试组 | 覆盖 |
| --- | --- |
| FixedPointTest | 严格十进制解析、整数盈亏/保证金、溢出拒绝 |
| DemoBrokerTest | 24 项领域测试：点差、费用、四向挂单、触发资金校验、保护价、跳空、幂等、版本、断连、强平、快照、随机序列和 K 线 |
| PendingModificationTest | 四种挂单改价/保留保护价、无效价格/过期版本/跨账户拒绝、重复请求、成交/撤单后拒绝、满容量原单改价与恢复 |
| TradingRuntimeTest | 损坏存档不覆盖、磁盘失败暂停、两个交易商恢复 |
| ChartGeometryTest | 坐标互转、tick 吸附、空/恒价数据、视窗边界、邻近价格标签错开 |
| OrderDraftTest | 无效价格不能变成空保护价、数量精度、参考保护方向 |
| TradingScreenTest | 下单预览/确认、SL 拖动与取消、账户/交易商切换、平仓入历史、OHLC 表、Limit/Stop 草稿拖动、挂单拖动确认、编辑中撤单阻止确认、截图和短高度布局 |

JUnit 报告在各模块 `build/reports/tests/desktopTest` 和 `build/test-results/desktopTest`。Compose UI 测试在 Desktop 宿主中派发点击、输入和拖动事件，验证界面行为。Android instrumentation 测试需单独运行。

## Android 实际操作与账本核对

通过 ADB 安装并启动实际 APK，使用 Android accessibility tree、触控事件、截图和应用私有 JSON 快照进行验证，没有使用网页替代。

| 流程 | 已观察结果 |
| --- | --- |
| 交易商切换 | Meridian 显示 BTCUSD，Atlas 显示 EURUSD；旧目录没有残留 |
| 账户切换 | 策略账号显示自己的 GBPUSD 仓位；主账号的 EURUSD 交易隔离 |
| 市价下单 | 先显示预计入场、SL/TP 净结果和费用，确认后增加 0.10 手买单；余额扣除单边 0.35 USD |
| SL 拖动取消 | 松手进入确认框；取消前后仓位完全一致 |
| SL 拖动确认 | 仅 SL 更新、版本递增，TP 不变 |
| TP 拖动确认 | 仅 TP 更新、版本递增，SL 不变 |
| Limit 草稿拖动 | 价格回填订单表单，账本没有增加订单 |
| Limit 挂单拖动 | 确认前原订单不变；确认后保留订单 ID/类型/数量/SL/TP，只改入场价与版本，余额不变 |
| Stop 草稿/挂单拖动 | 同样完成草稿回填与确认改价，不自动改变订单类型 |
| 全量平仓 | 仓位数减少、历史增加；实现净盈亏包含点差及双边手续费，与余额变化一致 |
| 撤单 | 指定 Limit 订单移除，另一笔 Stop 订单保留 |
| 进程重启 | force-stop 后冷启动，原持仓/挂单/历史恢复；没有重新注入示例交易 |
| 崩溃日志 | 验收期间未发现 `com.unitrading.demo` 崩溃记录 |
| 横竖屏 | 最终 APK 完成横屏/竖屏切换，横屏图表画布高度 503px；下单按钮、导航和账户资金均可访问 |

上表逐步核对了运行中的 UI 与落盘账本。测试数据只存在于模拟器的本地 Demo 账户中。

## Desktop 实际运行

- 已通过 `:composeApp:run` 启动真实 JVM 窗口，观察了工作台和保护价编辑界面。
- 两个交易商的 JSON 快照实际落盘，可读取独立账户状态。
- Desktop 自动测试覆盖最新的入场线拖动及确认行为，并生成工作台截图。
- 正常关闭时保存实现已接入；此前桌面进程以 `BUILD SUCCESSFUL` 正常结束。没有声称完成 MSI 安装器验收。

## 本轮发现并修复

- SL/TP 拖动原来按越过触摸阈值后的位置命中，可能漏判；改为原始按下位置命中并在拖动期间固定价格映射。
- 交易商切换时旧账户状态可能残留一帧，导致旧品种查不到；按交易商和会话 key 隔离 StateFlow 收集。
- 新增挂单版本与原子改价接口，过期/已成交/已撤销订单不能被拖动编辑重新写回。
- 同时显示现价和邻近入场价时标签遮挡；价格轴标签改用间隔排列与引导线，价格线本身不移动。
- Android 高 DPI 标签背景高度改为实际字体测量；深色状态栏/导航栏改用浅色图标。
- Android 横屏空间不足时图表被上下工具栏挤扁；短高度布局将下单移到顶栏，资金行移到账户页，保留图表空间。
- 初始暂停行情后可能还没有自动保存文件；验收脚本在首笔交易完成后读取快照，不把尚未保存的文件当作账本损坏。
- Gradle/AGP/compileSdk 已调整为可实际编译的组合；无效跨平台悬停 API 改用共享 pointer input。

## 产物与证据

- `artifacts/desktop-workstation.png`：Compose Desktop 工作台截图，包含仓位与 Limit/Stop 订单。
- `artifacts/android-order-preview.png`：Android 下单前预估。
- `artifacts/android-limit-entry-preview.png`、`artifacts/android-stop-entry-preview.png`：Android 挂单入场价确认界面。
- `artifacts/android-trading-final.png`、`artifacts/android-trade-history.png`：Android 图表与成交历史。
- 自动测试报告、截图、模拟存档和验收脚本为生成文件，均不纳入 Git；最终 APK 单独交付。

## 限制与未验证项

- Android 真机、不同厂商 ROM、全部屏幕尺寸和最低 API 26 设备未实测；本次实际设备环境为 Android 16 模拟器。
- iOS 没有在 Mac/Xcode 构建、签名或运行。
- Windows MSI 与 Android 正式签名/商店发布未执行。
- 只支持本地 USD 线性合约模拟；没有真实交易商认证、真实资金、网络对账或生产级风控。
- 无部分成交/平仓、多币种换算、EA、技术指标库和净额持仓。
- 每品种保留最多 11,521 tick、每账户 1,000 笔已平仓交易及 200 条事件；旧成交超出图表历史窗口时不补造行情。
- 幂等记录随使用增长，不自动过期；显式重置会清除记录，重置前 requestId 不能继续保证去重。
- iOS host target、未启用的 Android host tests 与旧 Compose dependency accessor 会出现提示；实际构建结果仍以命令退出码和测试报告为准。
