# 手机布局：图表优先与可最小化下单面板

> 2026-11-06 界面按 Material 3 重做：顶栏、导航、下单表单和术语以 [Material 3 界面规范](ui-material3.md) 为准；下单界面（上滑面板、停靠下单栏、独立弹窗）的状态以 [手机下单界面](mobile-order-sheet.md) 为准。下文保留各次调整的背景和验证记录。

初版实现日期：2026-10-29；手机下单表单重做：2026-10-30；按钮入口、草稿会话与 Material 3 Sheet：2026-10-31；下单界面可在设置里选上滑面板、停靠下单栏或独立弹窗：2026-11-12。图表占据屏幕主体，下单默认使用 Material 3 `BottomSheetScaffold` 上滑面板。代码入口：[TradingScreen.kt](../composeApp/src/commonMain/kotlin/com/unitrading/ui/TradingScreen.kt)（布局切换与下单界面选择）、[MobileTradePanel.kt](../composeApp/src/commonMain/kotlin/com/unitrading/ui/MobileTradePanel.kt)（下单条、上滑面板、停靠下单栏与最小化条）、[OrderForm.kt](../composeApp/src/commonMain/kotlin/com/unitrading/ui/OrderForm.kt)（手机三种界面与桌面共用的下单表单）、[TradingPanels.kt](../composeApp/src/commonMain/kotlin/com/unitrading/ui/TradingPanels.kt)（独立弹窗和桌面下单面板的 `OrderTicket`）、[ChartOverlay.kt](../composeApp/src/commonMain/kotlin/com/unitrading/chart/ChartOverlay.kt)（单行工具条、图层菜单、紧凑图例与缩放按钮）、[TradingChart.kt](../composeApp/src/commonMain/kotlin/com/unitrading/chart/TradingChart.kt)（`ChartChrome`）。

宽度小于 900dp 或高度小于 600dp 时使用手机布局（Android、iOS 与窄窗口桌面相同）；其余窗口使用桌面多栏工作台。

## 图表页

| 区域 | 调整前 | 调整后 |
| --- | --- | --- |
| 顶部 | 应用标题栏（UniTrading、账户名、环境、“账户”按钮）+ 常驻连接状态行 | 图表工具条一行：品种搜索、当前周期（点开为下拉）、指标、图层菜单，右端固定环境徽标；连接状态行仅在交易未就绪时显示 |
| 图上悬浮 | 品种 / 收藏周期 / 图层开关三行工具条；图例含买卖按钮与成交量；“＋ － 较早 较新 重置视图” | 显示图例（标题、OHLC、涨跌、量、时间，收起按钮在标题右侧）和左下角“＋ － 复位”图标；较早/较新改由拖动平移 |
| 图下 | 手势说明行、“本品种 n 笔持仓 / 新建订单”、资金行 | 单行账户摘要与“下单”按钮；编辑中的草稿可最小化为方向、类型、数量与恢复入口 |

- 仓位、挂单、历史三个图层和 OHLC 数据表收进“图层”下拉；菜单保持打开，可连续切换。收藏周期和自定义周期仍在周期下拉中。
- 图例（含指标列表）的收起/展开状态本机保存，切到其他页面再回到图表、重启后保持；从未切换时，短屏（横屏）默认收起。
- 资金（余额、净值、今日盈亏、浮动盈亏、保证金、保证金比例）和“回撤与风险”在账户页的“总览”页签；连接状态与“同步账户”常驻“设置”页，图表工具条的状态圆点也可展开详情。操作回执使用[悬浮通知](notifications.md)，不挤压图表。
- 底部入口统一为单行，保留持仓浮盈亏、券商今日盈亏与“下单”按钮；买卖报价卡片放在展开后的表单内。
- 手机不再显示顶栏“账户”按钮与账户对话框，由底部“设置”页承担。其他三页各有一条顶栏，标题是页面名，副标题是账户名，右侧是环境徽标和连接状态。

## 下单面板

- 展开：点“下单”，打开「设置 → 交易 → 下单界面」选定的界面：上滑面板（默认，Material 3 `BottomSheetScaffold`）、停靠下单栏或独立弹窗，各自的状态、尺寸和收起方式见[手机下单界面](mobile-order-sheet.md)。已最小化或收起时，点草稿摘要或再点“下单”恢复同一订单。报价卡片只选择方向，不直接下单。
- 最小化：上滑面板和停靠下单栏点头部的“最小化下单面板”或按系统返回键，上滑面板还可以下滑；独立弹窗点“×”、系统返回、点对话框外面或“到图表调整”收起。键盘打开时先关闭键盘，再次返回才最小化。最小化后可再次操作图表，方向、类型、数量、保护开关和价格全部保留，图上的草稿入场价/SL/TP 仍可拖动并回填。
- 完全关闭：上滑面板和停靠下单栏的“×”（无障碍标签为“关闭并放弃本次下单”），独立弹窗的“取消订单”。关闭后移除草稿线并重置本次草稿和待确认请求；再次打开为新订单。提交处理中禁用关闭。关闭草稿不撤销已经发往交易商的订单。
- 在同一账户内，切换页面、品种后返回，恢复该品种的草稿与面板状态；切换账户清除本地草稿会话，避免误用于其他账户。最小化状态下按系统返回：上滑面板和停靠下单栏关闭并放弃草稿，独立弹窗交给平台。
- 上滑面板覆盖图表、没有遮罩，最小化和展开之间图表尺寸不变；停靠下单栏占用自己的位置，图表随之变矮，横屏展开时变窄。
- 表单字段可滚动，底部检查按钮固定，背景图表保持组合，不随下单界面重建。
- 上滑面板和停靠下单栏展开时，头部写品种和账户名（停靠下单栏的连续合约写成“@MES · MESZ6”）；独立弹窗的标题是“新建订单”，下面写账户、品种和结算币种。手机三种界面与桌面共用下单表单 `OrderForm`：买卖报价卡片 → 数量加减与输入（停靠下单栏的滑动条在“更多设置”里，其他界面紧接着显示滑动条）→ 挂单入场价（仅限价/突破显示）→ 并排 SL/TP 选项框及已启用价格 → 参考保护价/清除 → 可展开预估与合约信息。订单类型分段按钮在上滑面板和停靠下单栏的头部，在独立弹窗和桌面面板的表单顶部。子账户开启订单风控时，表单多一行自动止损止盈价格或算出的数量，见[子账户风控](account-risk-lock.md#订单风控)。
- 数量加减和滑动条按品种最小值、最大值和步长校验，期货仍为整数张；滑动条与手动输入同步，覆盖合约允许的完整数量范围。移动滑块才更改数量，非法手动输入不会被自动修正。合约只允许一个数量时禁用滑块。
- 止损、止盈分别勾选启用。首次勾选填入参考价格并显示可拖动草稿线，拖动后回填输入框；取消勾选隐藏该输入框和草稿线，下单不携带该保护价，但草稿保留价格供再次勾选恢复。已启用但价格为空/非法时禁止提交。
- 再次点击已选方向或类型保留价格；切换方向/类型时保持勾选状态并重算已启用保护的参考价，清除未启用保护的旧价格。参考 SL/TP 按钮启用两项，“清除”同时关闭并清空两项。桌面下单表单使用相同的勾选和滑动条规则。
- 检查按钮与方向、类型、数量、预估入场摘要固定在底部（停靠下单栏的摘要在“更多设置”展开时才显示）；第一条校验错误显示在按钮上方，预估明细折叠时仍可见。点击报价卡片只选方向；检查后仍弹出原确认框，确认前不发送指令。
- 草稿线在展开和最小化状态均生效；下单被受理后结束该草稿会话并关闭下单界面。提交失败则保留草稿，错误提示显示在下单界面内，可直接阅读和关闭。
- 下单条上的“持仓 n · 浮动盈亏”和“今日盈亏 · 券商”跳转账户页的“交易”页签。

## 实现说明

- 交易线标签按 TradingView 样式排成一行格子，在主图右边、价格轴左边对齐并落在价格线上，适用于桌面和手机。近价标签避让图例与其他标签，必要时用连接线指回真实价位；宽度不够时先去掉 TP、SL 按钮，再去掉数量和类型，仍放不下才只保留水平价线和价格轴读数。可编辑标签本身也能拖动，偏移后的标签起拖不会让价格跳到标签所在高度。
- 持仓标签依次为方向、浮动毛盈亏、缺少的 TP / SL、数量、×；挂单和草稿依次为方向、TP / SL、数量、类型、×；止损止盈依次为 SL 或 TP、预估结果、数量、×。浮动盈亏沿用 `positionPnl`：多仓取 Bid，空仓取 Ask，按当前报价刷新；不扣除手续费，无报价显示“—”。止损止盈标签显示触发价对应的预估结果，数量小于持仓的单只按自身数量估算。
- 标签按钮的手指热区比画出来的格子高 8dp。TP、SL 按钮不响应单击，按住拖到目标价才添加，松手后的处理和拖动已有线相同：草稿立即回填表单，持仓和挂单走确认。这两个按钮只出现在交易商支持新增保护的线上：Tradovate 和 Rithmic 的已有持仓、挂单没有，下单草稿有。× 与右键菜单走交易列表的确认流程。
- 启用草稿保护时，自动价格轴包含该保护价；若手动价格轴将新保护线排除在可见范围外，仅扩展纵向范围，保留横向视窗。拖线过程中不因勾选状态重新缩放。已有仓位/挂单的保护线与新订单选项框独立。
- `TradingChart` 原有 30 个参数。直接再加两个参数后，D8 生成的 dex 被 ART 校验拒绝（`VerifyError`，Android debug 包进入图表页即崩溃；桌面 JVM 正常）。手机选项因此合并为一个参数（现为 `ChartChrome`，也承载图例收起状态），参数数量保持 30。以后给该函数增加参数，须在 Android 设备或模拟器上实际运行验证，桌面测试发现不了这类问题。
- 上滑面板和停靠下单栏用 `NavigationBackHandler` 处理系统返回，独立弹窗由对话框的 `onDismissRequest` 收起；设置内的活动记录返回同样用 `NavigationBackHandler`。

## Material 3 Sheet 验证（2026-10-31）

- 移除手写高度动画、拖拽阈值与横屏停靠逻辑，使用 `ModalBottomSheet` 的默认把手、遮罩和状态动画。
- 定向桌面回归 37 项通过：`MobileLayoutTest` 14 项、`TradingScreenTest` 17 项、`NotificationUiTest` 6 项。覆盖下滑/遮罩/无障碍收起与草稿保留、明确关闭重置、模拟确认下单、拒单提示在 Sheet 内显示、图表尺寸保持、小屏大字体滚动与固定提交区。
- `MobileLayoutTest` 的测试窗口采用实际手机像素尺寸，Sheet 与页面使用相同窗口约束；已检查竖屏、横屏和小屏大字体截图。
- Android debug APK 构建通过；隔离测试包的 `compileDebugAndroidTestKotlin` 编译通过。设备测试已适配模态窗口与输入法窗口的可见性读取，但本次未执行。
- 日志：`artifacts/sheet-final-checks.log`、`artifacts/sheet-regression-4.log`；JUnit 报告：`composeApp/build/test-results/desktopTest/`；APK：`androidApp/build/outputs/apk/debug/androidApp-debug.apk`。
- 本次未验证：Android 设备/模拟器上的键盘与返回手势、R8 release、iOS。以下设备验证属于旧面板，不能作为本次替换的验证结果。

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

## 交易列表与活动记录

- 交易区取消持仓、挂单、历史、活动标签，使用一个列表按持仓 → 挂单 → 历史纵向排列。空分区保留标题与提示。
- 挂单使用独立角色徽标区分入场、SL 止损、TP 止盈及关联待确认；保留改单、保护价和撤单操作。
- 底部第四项改为“设置”，保留账户功能，新增“活动记录”子页；活动按最新在前显示，页面返回与系统返回均回到设置。
- 桌面共用交易列表，顶栏“设置”也提供活动记录入口。
