# 手机布局：图表优先与折叠下单面板

初版实现日期：2026-10-29；手机下单表单重做：2026-10-30。图表占据屏幕主体，下单收进图表下方的折叠面板。代码入口：[TradingScreen.kt](../composeApp/src/commonMain/kotlin/com/unitrading/ui/TradingScreen.kt)（布局切换）、[MobileTradePanel.kt](../composeApp/src/commonMain/kotlin/com/unitrading/ui/MobileTradePanel.kt)（折叠条与下单面板）、[MobileOrderTicket.kt](../composeApp/src/commonMain/kotlin/com/unitrading/ui/MobileOrderTicket.kt)（手机专用下单表单）、[ChartOverlay.kt](../composeApp/src/commonMain/kotlin/com/unitrading/chart/ChartOverlay.kt)（单行工具条、图层菜单、紧凑图例与缩放按钮）、[TradingChart.kt](../composeApp/src/commonMain/kotlin/com/unitrading/chart/TradingChart.kt)（`ChartChrome`）。

宽度小于 900dp 或高度小于 600dp 时使用手机布局（Android、iOS 与窄窗口桌面相同）；其余窗口使用桌面多栏工作台。

## 图表页

| 区域 | 调整前 | 调整后 |
| --- | --- | --- |
| 顶部 | 应用标题栏（UniTrading、账户名、环境、“账户”按钮）+ 常驻连接状态行 | 图表工具条一行：品种搜索、当前周期（点开为下拉）、指标、图层菜单，右端固定环境徽标；连接状态行仅在交易未就绪时显示 |
| 图上悬浮 | 品种 / 收藏周期 / 图层开关三行工具条；图例含买卖按钮与成交量；“＋ － 较早 较新 重置视图” | 显示图例（标题、OHLC、涨跌、量、时间，收起按钮在标题右侧）和左下角“＋ － 复位”图标；较早/较新改由拖动平移 |
| 图下 | 手势说明行、“本品种 n 笔持仓 / 新建订单”、资金行 | 折叠下单条：持仓/浮动盈亏与下单入口一行，卖出 BID / 买入 ASK 两个等宽大按钮一行；点差收进下单表单明细 |

- 仓位、挂单、历史三个图层和 OHLC 数据表收进“图层”下拉；菜单保持打开，可连续切换。收藏周期和自定义周期仍在周期下拉中。
- 图例（含指标列表）的收起/展开状态本机保存，切到其他页面再回到图表、重启后保持；从未切换时，短屏（横屏）默认收起。
- 资金行（余额、净值、浮动盈亏、保证金、保证金比例）移到“交易”“账户”两页顶部；连接状态与“同步账户”常驻“账户”页。
- 高度不足 550dp 时，折叠条压为一行买卖报价与“下单”，省略持仓摘要和把手；从底部“交易”页查看持仓。
- 手机不再显示顶栏“账户”按钮与账户对话框，由底部“账户”页承担。其他三页保留一行精简标题栏（应用名、账户名、环境徽标）。

## 下单面板

- 展开：点“下单”，或点按、上滑把手；折叠条的卖出/买入按钮只选择方向并展开面板，不直接下单。
- 收起：点“收起”，点按、下滑把手，或系统返回键。键盘打开时先由系统关闭键盘，再次返回才收起面板；面板关闭后返回交给系统。
- 竖屏：面板默认占图表页约 70% 高度（上限 600dp），“展开”增至约 86%，“还原”回到默认高度。图表留在上方，自动价格范围包含草稿入场价/SL/TP，草稿线仍可拖动。面板打开时隐藏底部导航和图例；拖动订单线时图例临时出现并显示预估。
- 输入键盘打开时，下单表单使用键盘上方的可用空间，暂时隐藏图表；表单可滚动，底部检查按钮固定。关闭键盘后恢复图表和原面板高度。
- 横屏（宽 ≥ 600dp 且宽 > 高）：面板以 340dp 宽停靠在图表右侧，不显示把手。
- 面板头部显示“新建订单”、环境徽标、品种、账户名和结算币种。手机使用独立下单表单：订单类型分段按钮 → 买卖报价卡片 → 数量加减、输入与滑动条 → 挂单入场价（仅限价/突破显示）→ 并排 SL/TP 选项框及已启用价格 → 参考保护价/清除 → 可展开预估与合约信息。
- 数量加减和滑动条按品种最小值、最大值和步长校验，期货仍为整数张；滑动条与手动输入同步，覆盖合约允许的完整数量范围。移动滑块才更改数量，非法手动输入不会被自动修正。合约只允许一个数量时禁用滑块。
- 止损、止盈分别勾选启用。首次勾选填入参考价格并显示可拖动草稿线，拖动后回填输入框；取消勾选隐藏该输入框和草稿线，下单不携带该保护价，但草稿保留价格供再次勾选恢复。已启用但价格为空/非法时禁止提交。
- 再次点击已选方向或类型保留价格；切换方向/类型时保持勾选状态并重算已启用保护的参考价，清除未启用保护的旧价格。参考 SL/TP 按钮启用两项，“清除”同时关闭并清空两项。桌面下单表单使用相同的勾选和滑动条规则。
- 检查按钮与方向、类型、数量、预估入场摘要固定在底部；第一条校验错误显示在按钮上方，预估明细折叠时仍可见。点击报价卡片只选方向；检查后仍弹出原确认框，确认前不发送指令。
- 草稿线只在面板打开时显示；下单被受理后面板自动收起，草稿数值保留；切换账户时收起。
- 折叠条上的“持仓 n · 浮动盈亏”跳转“交易”页。

## 实现说明

- 交易线标签在主图右边、价格轴左边对齐，适用于桌面和手机。近价标签避让图例与其他标签，必要时换行并用连接线指回真实价位；空间耗尽时保留水平价线和价格轴读数。可编辑标签本身也能拖动，偏移后的标签起拖不会让价格跳到标签所在高度。
- 持仓入场标签包含方向、数量及单位、浮动毛盈亏、仓位编号。浮动盈亏沿用 `positionPnl`：多仓取 Bid，空仓取 Ask，按当前报价刷新；不扣除手续费，无报价显示“—”。止损止盈标签继续显示触发价对应的预估结果。
- 启用草稿保护时，自动价格轴包含该保护价；若手动价格轴将新保护线排除在可见范围外，仅扩展纵向范围，保留横向视窗。拖线过程中不因勾选状态重新缩放。已有仓位/挂单的保护线与新订单选项框独立。
- `TradingChart` 原有 30 个参数。直接再加两个参数后，D8 生成的 dex 被 ART 校验拒绝（`VerifyError`，Android debug 包进入图表页即崩溃；桌面 JVM 正常）。手机选项因此合并为一个参数（现为 `ChartChrome`，也承载图例收起状态），参数数量保持 30。以后给该函数增加参数，须在 Android 设备或模拟器上实际运行验证，桌面测试发现不了这类问题。
- 系统返回键使用 `androidx.navigationevent` 的 `NavigationBackHandler`。该库已经通过 Compose UI 间接引入，这里显式声明 `navigationevent-compose` 1.1.0 以便在共享代码中编译。

## 保护选项框与数量滑动条验证（2026-10-30）

- `:composeApp:desktopTest`：133 项全部通过。新增保护开关独立启用/禁用、参考价生成、保留价格后重新启用、已启用空价格阻止提交、方向与类型变化、数量滑动条整数步长、上下界和固定数量测试。
- Compose UI 实际拖动数量滑块，验证输入框与滑块同步；勾选生成草稿止损后拖线回填；取消止损但保留止盈，再确认本地模拟挂单，断言请求中的止损为空、止盈为所填价格。未向远程账户提交订单。
- Android debug 与测试 APK 编译通过，API 36 的 `AppearanceUiTest` 综合流程通过：触控滑动条更改数量、勾选两项显示价格输入、取消两项隐藏价格输入，并继续验证软键盘、返回与视窗保持。
- 截图检查：`artifacts/order-controls/protection-controls-android.png`、`mobile-protection-controls.png`、`mobile-order-panel.png`。Android 截图可见新增的两条草稿保护线，以及按启用价格扩展后的手动价格轴。
- 报告：`artifacts/order-controls/result.json`、`build.log`、`device.log`、`instrumentation.txt`；桌面 JUnit 报告位于 `composeApp/build/test-results/desktopTest/`。
- 本轮未验证 R8 release、真机和 iOS。

## 面板重做与标签调整验证（2026-10-30）

- `:composeApp:desktopTest`：127 项全部通过，其中 `MobileLayoutTest` 10 项；覆盖常规竖屏、320×640dp / 1.3 倍字体、860×400dp 横屏、固定提交区、展开/还原、数量步长与边界、重复点击保留草稿、无效输入禁止提交、下单前确认和图表拖线。
- 新增 `OrderLabelLayoutTest` 验证标签靠右、避让图例与邻近标签、窄图不进入价格轴；`OrderLinesTest` 验证多仓按 Bid、空仓按 Ask 计算浮盈亏、报价更新及无报价占位。
- Android debug APK 与 instrumentation APK 编译通过；API 36 模拟器的 `AppearanceUiTest` 通过（1 项综合流程）。强制开启软键盘，验证检查按钮可见、返回先关闭键盘、图表视窗不变、数量加减与再次返回收起面板。仅使用隔离的 `.credentialtest` 包与本地模拟数据。
- 已检查截图：`artifacts/mobile-order-panel.png`、`mobile-chart-first.png`、`mobile-small-order-details.png`、`mobile-landscape-order.png`、`desktop-workstation.png`，以及 `artifacts/android-appearance/files/android-order-keyboard.png`、`android-order-panel.png`。
- 构建与设备结果：`artifacts/mobile-redesign/result-5.json`、`build-5.log`、`device-5.log`，设备完整输出 `artifacts/android-appearance-instrumentation.txt`。桌面 JUnit 报告位于 `composeApp/build/test-results/desktopTest/`。
- 本次未验证：R8 release、真机、iOS，以及 Tradovate 真实下单。下面的 release 设备测试记录属于初版，不能作为本次改动的验证结果。

## 初版验证（2026-10-29）

- `MobileLayoutTest`（Compose Desktop，8 项）：400×800dp 竖屏图表高度 ≥ 66%，无应用标题栏与资金行；收起图例（含指标列表）后切页再回来仍收起，并已写入本机设置；快捷卖出展开面板并选中卖出，导航和图例隐藏，收起后恢复，持仓入口跳转交易页；把手上滑、下滑、点按；注入导航返回事件可收起面板，面板关闭后返回交给系统；确认下单后自动收起；面板打开时拖动草稿入场线显示预估并回填；周期下拉、指标、图层菜单与数据表；860×400dp 横屏面板停靠右侧。截图：`artifacts/mobile-chart-first.png`、`mobile-order-panel.png`、`mobile-landscape-order.png`。
- Android 模拟器（API 36，1080×2400，420dpi）：`AppearanceUiTest` 新增步骤：点“下单”展开面板，底部导航隐藏，系统返回收起面板且应用未退出。debug 包与 release（R8，`-PinstrumentationBuildType=release`）包的 4 项设备测试均通过。
- JVM 常规测试 184 项通过（broker-api 6、demo-broker 28、tradovate-broker 56、composeApp 94）。
- 未验证：真机（含 HyperOS）、iOS，以及 Tradovate 真实账户数据下的手机布局。
