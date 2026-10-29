# 手机布局：图表优先与折叠下单面板

实现日期：2026-10-29。参考 MT5 手机版图表页：图表占据屏幕主体，下单收进图表下方的折叠面板。代码入口：[TradingScreen.kt](../composeApp/src/commonMain/kotlin/com/unitrading/ui/TradingScreen.kt)（布局切换）、[MobileTradePanel.kt](../composeApp/src/commonMain/kotlin/com/unitrading/ui/MobileTradePanel.kt)（折叠条与下单面板）、[ChartOverlay.kt](../composeApp/src/commonMain/kotlin/com/unitrading/chart/ChartOverlay.kt)（单行工具条、图层菜单、紧凑图例与缩放按钮）、[TradingChart.kt](../composeApp/src/commonMain/kotlin/com/unitrading/chart/TradingChart.kt)（`PhoneChrome`）。

宽度小于 900dp 或高度小于 600dp 时使用手机布局（Android、iOS 与窄窗口桌面相同）；桌面多栏工作台不变。

## 图表页

| 区域 | 调整前 | 调整后 |
| --- | --- | --- |
| 顶部 | 应用标题栏（UniTrading、账户名、环境、“账户”按钮）+ 常驻连接状态行 | 图表工具条一行：品种搜索、当前周期（点开为下拉）、指标、图层菜单，右端固定环境徽标；连接状态行仅在交易未就绪时显示 |
| 图上悬浮 | 品种 / 收藏周期 / 图层开关三行工具条；图例含买卖按钮与成交量；“＋ － 较早 较新 重置视图” | 只剩图例（标题、OHLC、涨跌、量、时间，收起按钮在标题右侧）和左下角“＋ － 复位”图标；较早/较新改由拖动平移 |
| 图下 | 手势说明行、“本品种 n 笔持仓 / 新建订单”、资金行 | 折叠下单条：卖出价/买入价按钮、点差、账户持仓数与浮动盈亏、“下单” |

- 仓位、挂单、历史三个图层和 OHLC 数据表收进“图层”下拉；菜单保持打开，可连续切换。收藏周期和自定义周期仍在周期下拉中。
- 资金行（余额、净值、浮动盈亏、保证金、保证金比例）移到“交易”“账户”两页顶部；连接状态与“同步账户”常驻“账户”页。
- 手机不再显示顶栏“账户”按钮与账户对话框，由底部“账户”页承担。其他三页保留一行精简标题栏（应用名、账户名、环境徽标）。

## 下单面板

- 展开：点“下单”，或点按、上滑把手；折叠条的卖出/买入按钮只选择方向并展开面板，不直接下单。
- 收起：点“收起”，点按、下滑把手，或系统返回键。面板关闭后返回键交给系统，不被图表页拦截。
- 竖屏：面板占图表页约 60% 高度，图表留在上方，自动价格范围包含草稿入场价/SL/TP，草稿入场线仍可拖动。面板打开时隐藏底部导航和图例，避免图例盖住订单线标签；拖动订单线时图例临时出现并显示预估。
- 横屏（宽 ≥ 600dp 且宽 > 高）：面板以 340dp 宽停靠在图表右侧，不显示把手。
- 面板头部显示“新建订单”、环境徽标、品种、账户名和结算币种；表单与桌面下单票据相同。提交仍先弹出确认框，确认前不发送指令。
- 草稿线只在面板打开时显示；下单被受理后面板自动收起，草稿数值保留；切换账户时收起。
- 折叠条上的“持仓 n · 浮动盈亏”跳转“交易”页。

## 实现说明

- `TradingChart` 原有 30 个参数。直接再加两个参数后，D8 生成的 dex 被 ART 校验拒绝（`VerifyError`，Android debug 包进入图表页即崩溃；桌面 JVM 正常）。手机选项因此合并为一个 `PhoneChrome` 参数，参数数量保持 30。以后给该函数增加参数，须在 Android 设备或模拟器上实际运行验证，桌面测试发现不了这类问题。
- 系统返回键使用 `androidx.navigationevent` 的 `NavigationBackHandler`。该库已经通过 Compose UI 间接引入，这里显式声明 `navigationevent-compose` 1.1.0 以便在共享代码中编译。

## 验证

- `MobileLayoutTest`（Compose Desktop，7 项）：400×800dp 竖屏图表高度 ≥ 66%，无应用标题栏与资金行；快捷卖出展开面板并选中卖出，导航和图例隐藏，收起后恢复，持仓入口跳转交易页；把手上滑、下滑、点按；注入导航返回事件可收起面板，面板关闭后返回交给系统；确认下单后自动收起；面板打开时拖动草稿入场线显示预估并回填；周期下拉、指标、图层菜单与数据表；860×400dp 横屏面板停靠右侧。截图：`artifacts/mobile-chart-first.png`、`mobile-order-panel.png`、`mobile-landscape-order.png`。
- Android 模拟器（API 36，1080×2400，420dpi）：`AppearanceUiTest` 新增步骤：点“下单”展开面板，底部导航隐藏，系统返回收起面板且应用未退出。debug 包与 release（R8，`-PinstrumentationBuildType=release`）包的 4 项设备测试均通过。
- JVM 常规测试 184 项通过（broker-api 6、demo-broker 28、tradovate-broker 56、composeApp 94）。
- 未验证：真机（含 HyperOS）、iOS，以及 Tradovate 真实账户数据下的手机布局。
