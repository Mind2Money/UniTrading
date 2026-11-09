# 桌面面板尺寸

实现日期：2026-10-30；2026-11-09 按 TradingView 重排并支持收起。桌面工作区使用可拖动分隔线分配空间，图表占用剩余区域。

从左到右依次是：图表（下方是交易区，宽度与图表相同）、下单面板、账户与行情面板、最右侧一列面板图标。下单面板和行情面板占满工作区高度。

顶部工具栏压缩为单行，连接详情收进状态按钮，操作通知使用不占布局高度的应用内浮层，见[顶部工具栏与悬浮通知](notifications.md)。底部状态栏用一行显示全部账户数字。

## 操作

| 分隔线 | 调整内容 |
| --- | --- |
| 行情面板左侧 | 账户/行情面板宽度 |
| 账户区与行情区之间 | 账户区高度，行情区使用剩余高度 |
| 下单面板左侧 | 下单面板宽度 |
| 图表与底部交易区之间 | 持仓/挂单/历史区高度 |

- 鼠标移到分隔线时显示调整方向的光标，并高亮把手；按住拖动即可调整。
- 点击分隔线使其获得焦点，使用对应方向键每次移动 8dp；双击恢复该项默认尺寸。
- 顶栏的“重置布局”图标按钮恢复全部四项默认尺寸，并展开全部面板。
- 账户区可独立滚动，缩小后仍能访问全部账户。

## 收起与展开

- 最右侧图标依次对应行情、下单、交易三个面板，点击收起或展开，展开时图标带底色。面板自身也有收起按钮：行情面板和下单面板在右上角，交易区在标题栏右端。
- 行情面板和下单面板收起后不占宽度，交易区收起后保留一条标题栏，显示持仓和挂单数量。图表占用空出的位置，图表的品种、周期和缩放不受影响。
- 收起下单面板不放弃草稿，图上的草稿线继续显示。下单面板收起时点图表图例的买入或卖出会重新展开它。
- 窗口宽度小于 1240dp 时没有下单面板，也没有对应图标，仍用顶栏“下单”打开表单。
- 收起状态与尺寸一起写入 `desktop-layout-v1`；旧版本保存的布局读取后三个面板默认展开。

## 窗口与尺寸限制

- 布局切换条件：宽度至少 900dp 且高度至少 600dp 时使用桌面工作区；宽度至少 1240dp 时下单栏固定在右侧，较窄桌面窗口通过“下单”打开下单表单。
- 行情面板宽度为 280 至 520dp，下单面板宽度为 260 至 560dp；同时保留至少 320dp 的图表宽度，收起的面板不计入。
- 账户区优先保留至少 100dp，行情区至少 140dp；底部交易区优先保留至少 128dp，图表上部区域至少 240dp。若提示栏或窗口高度使剩余空间不足，则按可用高度缩小下限，避免负尺寸或布局越界。
- 缩小窗口只限制当前显示尺寸，不改写保存的偏好；恢复窗口后重新使用偏好尺寸。切换到手机布局再切回也保留尺寸。

## 保存与实现

- 拖动实时更新内存状态，松手后写入本机 `desktop-layout-v1`；采用 dp 存储，与当前账户、下单草稿和图表缩放分开保存。
- 写入按顺序执行，完成较早的保存不会覆盖较新的界面尺寸。保存失败时当前布局仍有效，工作区显示错误及“重试保存”。
- 默认尺寸：行情面板 320dp、下单面板 296dp、账户区 260dp、底部交易区 220dp。
- 入口：[DesktopWorkspace.kt](../composeApp/src/commonMain/kotlin/com/unitrading/ui/DesktopWorkspace.kt)、[DesktopPanelGeometry.kt](../composeApp/src/commonMain/kotlin/com/unitrading/ui/DesktopPanelGeometry.kt)、[DesktopLayout.kt](../composeApp/src/commonMain/kotlin/com/unitrading/app/DesktopLayout.kt)。

## 验证

- 141 项 Compose JVM 测试通过，Android debug APK 编译通过。新增测试实际拖动四处分隔线，检查本机保存、窗口缩小/放大、手机布局往返、方向键微调、双击和全部重置，并确认账户交易状态不受影响。
- 存储测试覆盖坏文件回退、保存失败后的当前状态保留及重试、快速连续保存；布局计算测试覆盖尺寸边界和保留图表/行情区域。
- 结果：`artifacts/desktop-panels/result-2.json`、`build-2.log`。截图：`artifacts/desktop-panels-resized.png`。
- 本次未单独实测 Android 大屏桌面分栏、R8 release、iOS。

## 开发运行崩溃修复

Gradle 日志曾记录 `NoClassDefFoundError: com/unitrading/ui/TradingScreenKt$...`，由 `ClassNotFoundException` 引起。应用正在运行时，另一轮 Kotlin 编译替换了同一构建目录中的类和项目 jar，随后首次使用的界面回调无法加载。

`gradle/desktop-run.gradle.kts` 为每次 `run` 复制独立的类路径和应用资源到系统临时目录；正常退出通过 finalizer 清理，只清理当前启动创建的快照。此修复不复制或改动账户凭据目录。

- `:composeApp:verifyDesktopRunIsolation` 验证原始类文件被删除、资源被替换后，延迟加载仍取得启动快照中的类和资源。
- 实际运行 `:composeApp:run --args=--local-demo`，确认窗口与快照类路径，再运行 `:composeApp:compileKotlinDesktop --rerun-tasks`；窗口保持存活并正常关闭，`run` 以 `BUILD SUCCESSFUL` 结束，日志无类加载异常。
- 证据：`artifacts/run-isolation/result-4.json`、`direct-run.log`、`rebuild.log`。
