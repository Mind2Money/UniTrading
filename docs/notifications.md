# 顶部工具栏与悬浮通知

桌面工作区将原来的应用标题、常驻连接状态和交易通知三条区域合并为一条 48dp 工具栏。工具栏显示应用名、交易环境、账户、连接状态和图标操作；不重复罗列交易商、账户和环境的长串文字。

## 连接信息

- 桌面工具栏显示“已连接”“交易未就绪”或“已断开”。点击状态按钮展开完整连接说明和“同步账户”。
- 手机图表工具条右侧显示连接状态圆点，点击查看相同详情；其他手机页面在顶栏提供该入口。“设置”页仍保留完整连接信息与同步操作。
- 断开连接时同步按钮禁用，处理操作期间也禁用。显示方式不改变原有交易就绪校验。

## 通知

- 操作回执显示在应用内悬浮卡片中：桌面右上角、手机顶部。通知出现或关闭不改变图表及其他面板尺寸，也不遮罩整个工作区。
- 普通回执默认 8 秒后消失，鼠标悬停时暂停计时，移开后重新计时；无障碍设置可延长显示时间。同样文字的新回执重新开始计时。
- 交易被拒绝、结果未知或操作失败时，提示保留到手动关闭。“已受理”仍展示原服务器回执，不改写成“已成交”。
- 登录状态也使用悬浮卡片，可手动关闭；应用存储失败保留“重试保存”，直到问题解决。
- 卡片覆盖处拦截点击，避免误触后方下单控件；卡片之外的图表和工作区可继续操作。高度不足时通知区域可滚动。
- 交易确认对话框沿用原流程。本功能使用应用内浮层，不创建系统置顶窗口或申请系统悬浮窗权限。

代码入口：[FloatingNotice.kt](../composeApp/src/commonMain/kotlin/com/unitrading/ui/FloatingNotice.kt)、[ConnectionStatusControl.kt](../composeApp/src/commonMain/kotlin/com/unitrading/ui/ConnectionStatusControl.kt)、[TradingScreen.kt](../composeApp/src/commonMain/kotlin/com/unitrading/ui/TradingScreen.kt)。

## 验证范围

- `NotificationUiTest` 的 5 项测试覆盖：单行工具栏及通知出现/关闭前后图表尺寸不变、连接详情与同步、错误通知保持显示、手机导航可用、普通通知超时及重复消息重新计时、卡片阻止点击穿透而其他区域可操作。
- 首轮完整 Compose 套件运行 148 项，146 项通过。两项无关断言失败留给并行改动处理：`RemoteWorkbenchTest.emptyRemoteCatalogDoesNotCrashOrCreateMockInstruments` 仍匹配旧文案；`pairedHistoryExpandsLegsAndLocatesChartWithoutShowingRawExecutions` 匹配到了图例和历史明细中两个相同时间。原报告保存在 `artifacts/floating-notices/full-suite-RemoteWorkbenchTest.xml`。
- 后续通知专项验证仅排除上述两项，未改写或提交对应测试及成交历史实现。
- 第二轮其余 146 项 Compose JVM 测试全部通过，Android debug 和测试 APK 编译通过。API 36 模拟器的 `AppearanceUiTest` 综合流程通过，实际确认本地模拟订单、看到悬浮回执、核对图表边界不变并点击关闭。
- 结果：`artifacts/floating-notices/result-2.json`、`build-2.log`、`device-2.log`、`instrumentation-2.txt`。已检查截图：同目录的 `desktop.png`、`mobile.png`、`android.png`。
- 本次未验证真机、iOS 或 R8 release；通知测试使用合成账户和本地模拟订单，没有向真实交易账户发送指令。
