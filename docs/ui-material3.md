# Material 3 界面规范

2026-11-06 按 Material Design 3 重做全部界面。本文记录审计结论、现行规范和验证结果；改界面前先读“规范”一节。

## 规范

### 颜色

- 所有 M3 颜色角色由 `tradingColorScheme` 从调色板推导，用户自定义颜色会带动全部容器色。组件取 `MaterialTheme.colorScheme`；涨跌、盈亏、止损止盈这些行情语义取 `TradingColors`。
- `AlertDialog`、菜单、Sheet 使用组件默认的容器角色，不再手动指定背景。
- 破坏性操作用 `error`。方向用 `Up` / `Down`：下单按钮、确认按钮、最小化面板的提交箭头都随买卖方向变色，强调色不表示方向。
- Live 环境徽标使用 `error`，Demo 使用 `tertiary`，本地模拟使用 `primary`（`EnvironmentBadge`）。

### 字阶

- 使用 `TradingTypography`（M3 基线 15 级），全部开启等宽数字（`tnum`），价格和金额天然对齐。
- 不写 `fontSize`。例外只有两处：图表画布的轴标签（`labelStyle`），行情价格为保证长价格完整显示而动态计算字号。
- 最小 11sp（`labelSmall`）。

### 形状与间距

- 只用 `MaterialTheme.shapes`：4 / 8 / 12 / 16 / 28dp。
- 间距走 4dp 栅格，页面左右边距 16dp。

### 图标

- 所有界面图标来自 `AppIcons`，禁止 Unicode 字符、Canvas 手绘和手写 path。文字中的 `→`、`·` 属于排版，不算图标。
- `AppIcons` 是官方 Material Icons（Apache-2.0）的子集。完整依赖 `material-icons-extended` 在桌面是 37.7MB 的 jar，所以只内置用到的字形。新增图标时，把官方 path 数据加进该文件。
- 只有图标的按钮用 `ActionIcon`：48dp 触控区、悬停或长按显示 Tooltip、读屏文字。图表内紧凑控件可缩小外框，但保留 Tooltip 和读屏文字。

### 共用组件（`Components.kt`）

| 组件 | 用途 |
| --- | --- |
| `PageTopBar` | 手机每页唯一的标题，副标题放账户；可带返回和操作 |
| `ActionIcon` | 图标按钮 |
| `StatusBadge` / `EnvironmentBadge` | 状态与环境标签 |
| `SegmentedChoice` | 2–5 个互斥的短选项 |
| `FormDialog` | 字段多的表单：手机全屏，宽屏居中，标题栏放关闭和保存 |
| `Metric` | 带标题的数字 |
| `ListSubheader` / `EmptyState` / `StatusDot` / `AppLogo` | 列表分组、空状态、状态点、品牌标识 |
| `ReorderableColumn` / `ReorderGrip` | 可拖动排序的列表，项目高度可以不同 |

### 布局与导航

| 窗口 | 布局 |
| --- | --- |
| 宽度 < 600dp | 底部 `NavigationBar`（64dp） |
| 600dp ≤ 宽度，且未进入桌面布局 | 左侧 `NavigationRail`，横屏手机和竖屏平板使用 |
| 宽度 ≥ 900dp 且高度 ≥ 600dp | 桌面多栏工作台 |

- 手机每页一条顶栏，页面名是标题。图表页由工具条代替顶栏。
- 桌面顶栏只留品牌、环境、账户、连接状态和图标操作。账户切换在左侧栏，其余都在设置页。

### 交互

- 确认按钮写动作本身（买入 / 卖出 / 平仓 / 撤单 / 重置），不写“确认”。
- 订单类型统一叫 市价 / 限价 / 突破。止损止盈统一叫“止损/止盈”或 SL / TP。
- 只有打开新页面的设置行带右箭头。
- 列表排序用拖动，不用上下箭头：鼠标按住整个抓取区，触屏按住左侧手柄，抓取区其余位置留给滚动。

## 审计结论

| # | 问题 | 处理 |
| --- | --- | --- |
| A1 | M3 颜色角色只映射约 15 个，`AlertDialog` 落回库默认的紫灰色 | 映射全部角色 |
| A2 | 258 处硬编码字号，最小 9sp | 改用字阶；保留的例外见上 |
| A3 | Unicode 字符、Canvas 手绘、手写 path 三套图标 | 统一 `AppIcons` |
| A4 | 圆角 10 种，无共用组件 | 形状刻度与 `Components.kt` |
| A5 | Android 无应用图标，桌面窗口无图标 | 自适应图标、窗口图标、Windows 安装包图标 |
| B1 | 手机非图表页双重标题 | `PageTopBar` |
| B2 | 同一订单类型有 `市价`/`市场执行`、`突破`/`买入止损` 两套叫法，“止损”还同时指 SL | 统一术语 |
| B3 | 类型栏和报价卡都能改方向 | 类型用分段按钮；最小化时加方向分段，展开时由报价卡负责 |
| B4 | 手机、桌面两套下单表单，功能已分叉 | 合并为 `OrderForm` |
| B5 | 账户入口 3 处，“切换账户”弹窗是设置页子集 | 删除弹窗 |
| B6 | 确认按钮不区分动作和方向 | 动词化并按后果配色 |
| B7 | “保护价”“接口插件 · 独立账户”等开发用语 | 改为用户用语 |
| B8 | 交易页资金条横向滚动，部分字段在屏外 | `AccountSummary` 摘要卡片，随列表滚动 |
| B9 | 设置里“连接”“断开”分属两组，图标与含义不符 | 重新分组 |
| B10 | 横屏底部占 157dp | `NavigationRail` |
| C1 | Rithmic 弹窗手机上 Live 选项被挤出屏幕 | 分段按钮，有回归测试 |
| C2 | 持仓编号显示 `#ition:9` | `shortId` 取 ID 末段 |
| C3 | 图表线标签混入 `LIMIT` | 用中文类型名 |
| C4 | 同排按钮字号不一 | 统一 |
| C5 | 工具条、图例按钮触控区低至 22×16dp | M3 chip 与 `ActionIcon` |
| C6 | 主题弹窗 13 个大输入框 | 分组紧凑行，色块打开取色器 |
| C7 | 常驻手势说明，桌面底部两行状态 | 删除说明，合并为一行 |
| C8 | 行情每行重复标签，涨跌用强调色，三角角标 | 表头一次，涨跌色，状态标签 |

B3 和 C8 改变了之前按 MT5 录像做的设计：5 项类型栏拆成类型和方向两组控件；三角角标换成文字标签，含义和数字不变。

## 实现注意

- `ActionIcon` 的 Tooltip 不可获得焦点（`focusable = false`），否则悬停出现的 Tooltip 会吞掉按钮点击。Tooltip 显示期间，点击它以外的位置会被吞掉，指针移开时它会关闭，人工操作碰不到；测试需先移开指针，见 `MarketWatchUiTest`。
- `selectTab` 用 lambda，不用本地函数引用。本地函数引用捕获了切换账户前的 `showActivity`，切换账户后底部导航回不到设置页。
- 下单按钮固定在表单下方，不在滚动区内，测试不要对 `review-order` 调 `performScrollTo`。
- `TradingChart` 的参数数量仍受 `ChartChrome` 注释所述的 ART 限制，新增图表选项继续放进 `ChartChrome`。

## 视觉回归

`UiScreenshotTour` 走完全部页面（手机、横屏、平板、桌面，深色、浅色、TradingV），输出到本机 `artifacts/ui-tour/`：

```bash
./gradlew.bat :composeApp:desktopTest --tests com.unitrading.ui.UiScreenshotTour
```

## 验证

- 桌面：composeApp 266 项、tradovate-broker 205 项、rithmic-broker 66 项、demo-broker 34 项、broker-api 6 项，共 577 项全部通过。
- Android：API 36 模拟器 `Medium_Phone` 上 debug 和 R8 release 的设备测试各 5 项全部通过；已检查许可页、图表、下单展开/最小化/键盘、通知、设置和主题的设备截图。
- Windows：`createDistributable` 打包成功，打包程序以 `--local-demo` 启动后检查了实际窗口，窗口和 exe 图标正确。
- 未验证：iOS（需要 Mac）；真实账户下的新界面。截图和测试全部使用合成数据，没有向真实账户发送指令。
