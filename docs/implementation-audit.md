# 代码与文档核对：1.1.4

> 以下是修复前的审计记录。当前 A1–A7 的实现与验证进度见[修复进度](remediation-progress.md)；原来的缺陷观测探针已转换为常规恢复测试。

日期：2026-10-29。业务代码基线：`main` / `2dcf7e1`。覆盖 README、全部 `docs/*.md`、native host 说明、Gradle/CI、共享 UI、图表、平台入口与 Tradovate 状态/命令流程。本次提交同步文档并保存离线诊断，以下业务缺口尚未修复。

## 已确认的代码缺口

### A1 已受理指令的等待状态缺少完整恢复（P2）

代码：[TradovateBroker.kt](../tradovate-broker/src/commonMain/kotlin/com/unitrading/tradovate/TradovateBroker.kt)，重点为 `connect`、`maintenance`、`sendWrite`、`reconcile`。

REST 返回成功后，`awaiting` 会阻止后续写指令。正常增量事件可以完成对账，但三种情况未覆盖：

1. 成功回执后没有对应实体事件：`Awaiting.started` 未用于超时判定，维护循环没有处理待确认期限。离线探针推进两分钟后仍显示“等待服务端订单/仓位更新”，保持 `tradingReady=false`。
2. 重连完整快照已含受理订单：`connect` 加载 `book.snapshot` 后仅 `rebuild`，没有调用 `reconcile`。探针看到订单 700 已进入账户列表，但等待标记仍在。
3. 平仓后服务器以 `Deleted` 删除 position：`EntityBook.event` 已删除仓位，而 `reconcile` 的 close 分支只查找仍存在的 `netPos == 0` 记录。探针看到空仓后仍无法继续写入。

影响：即使服务器状态已经更新，也可能持续锁住下单、改单、撤单和平仓，直到另一个可完成对账的事件或新会话出现。没有发现这些路径自动重发订单；缺口在确认与恢复。第三种情况为协议层合成事件复现，不宣称真实服务端一定使用该结束形态。

后续修复需保留“不确定结果不自动重发”的边界，增加完整快照后的对账、等待期限处理、明确的重新同步入口，以及净仓删除/归零的可靠确认。

### A2 iOS 缺少可用登录链路（未实现）

[MainViewController.kt](../composeApp/src/iosMain/kotlin/com/unitrading/MainViewController.kt) 只调用 `UniTradingApp(store)`；[UniTradingApp / TradingScreen](../composeApp/src/commonMain/kotlin/com/unitrading/ui/TradingScreen.kt) 的 login 默认为 null，默认注册表为空。[连接对话框](../composeApp/src/commonMain/kotlin/com/unitrading/ui/TradovateConnectionDialog.kt) 在 login 为 null 时禁用登录按钮。

iOS 除了缺少 Mac 构建验证，还缺网页认证实现和凭据存储接线；仅补一次编译验收无法使交易流程可用。

### A3 远程历史交易闭环尚未完成（部分实现）

[TradovateBroker.rebuild](../tradovate-broker/src/commonMain/kotlin/com/unitrading/tradovate/TradovateBroker.kt) 发布原始 `Execution`，未生成配对的 `ClosedTrade`；订单预估设置 `feesKnown=false` / `marginKnown=false`。[TradeTerminal](../composeApp/src/commonMain/kotlin/com/unitrading/ui/TradingPanels.kt) 的原始成交行没有点击定位回调；[TradingChart](../composeApp/src/commonMain/kotlin/com/unitrading/chart/TradingChart.kt) 的历史选中逻辑只查询 `trades`。

已实现原始成交列表和标记；未实现远程开平配对、含费用实现净盈亏及点击原始成交定位图表。旧 Demo 的完整历史能力不能直接推广到 Tradovate。

### A4 保护价编辑文案超出远程能力（P2）

[TradingScreen](../composeApp/src/commonMain/kotlin/com/unitrading/ui/TradingScreen.kt) 对所有环境显示“新止损/止盈（留空移除）”，但 [TradovateBroker.modifyProtection](../tradovate-broker/src/commonMain/kotlin/com/unitrading/tradovate/TradovateBroker.kt) 明确拒绝 null，且每次只能修改一条已有保护线。

已有净仓补加/删除保护未实现；OSO 只用于新入场订单携带保护。远程编辑框应提供符合该能力的说明和输入约束，目前会到提交时才拒绝“留空移除”。

### A5 异步目录移除当前品种时缺少回退（P2，静态确认）

[TradingScreen](../composeApp/src/commonMain/kotlin/com/unitrading/ui/TradingScreen.kt) 仅在 plugin 实例变化时初始化 symbol，随后使用 `plugin.instruments.first { it.symbol == symbol }`。同一插件若把 `[A,B]` 目录改为 `[B]`，空目录判断不会触发，查找 A 会抛出 `NoSuchElementException`。

这与插件文档允许异步更新目录的约定不完整。当前 Tradovate 合约缓存主要追加，尚未在真实连接观察到该崩溃；问题针对目录可删除/替换的插件路径，未运行 UI 复现。

### A6 部分图表设计要求仅实现了基础形式

- [TradingChart](../composeApp/src/commonMain/kotlin/com/unitrading/chart/TradingChart.kt) 十字线原先只画竖线和收盘点；现已补充横向虚线、右轴价格/副图指标值读数和底轴时间读数（`CrosshairUiTest`）。
- 实际开关为仓位、挂单、历史、数据表，没有独立“保护线”开关；选中仓位保护跟随仓位显示。
- K 线以序号等距排布，缺失时间段不留连续时间空档；没有合成缺失 K 线，也没有时间缺口可视化。
- Tradovate 请求和缓存限制为 2000 根，没有向更早历史翻页。支持缓存内浏览，未实现按任意成交时间回补行情。

这些是原设计与当前实现之间的差异。新增轴缩放、二维平移、主题与 Material 导航已存在，不列为缺失。

### A7 CI 缺少测试门禁，发布验证范围不完整

[build.yml](../.github/workflows/build.yml) 执行 `:androidApp:assembleRelease`、R8 输出检查、签名与通知，没有运行现有 127 项测试或 instrumentation，也没有 Windows/iOS job。PR 构建成功只能证明该构建 job 完成，不能证明回归通过。

签名与 Telegram 流程已实现，应补充构建/配置文档；secrets 是否配置、最新云端 job 结果和 release 设备运行不在本次核对证据内。

## 文档修正

| 文件组 | 原问题 | 本次处理 |
| --- | --- | --- |
| docs 首页 | 仍以本地模拟终端作为当前产品 | 重写当前入口和历史目录，补齐版本记录链接 |
| README / 插件指南 | 远程与 Demo 能力混写、依赖图遗漏 Tradovate、未说明空注册表 | 按入口、保护能力、毛/净结果及持久化职责修正 |
| 验证页 / Tradovate 指南 | 52/86 项和 1.1.1 被写成“当前/最终” | 增加当前 127 项摘要，旧结果保留为历史证据 |
| 原实施/UI/接入计划 | 默认模拟、固定深色、仅内存令牌、任务分支等旧约定 | 标记历史基线，说明被登录、主题、加密存储及 main 工作方式替代 |
| 版本修复记录 | 缺少与当前状态的入口区分 | 增加历史版本提示，保留当时数字和 hash |
| 构建 / CI | release、R8、签名/通知已实现但缺文档 | 在 current-status 中补充配置与未验证项 |

## 离线诊断与证据

本次没有连接用户账户、发送订单、操作浏览器或触发 CI/Telegram。A1 使用仓库的 `MockTradovateWire` 与合成令牌。

```powershell
./gradlew.bat :broker-api:desktopTest :demo-broker:desktopTest :tradovate-broker:desktopTest :composeApp:desktopTest --init-script docs/audit-probes/audit.init.gradle
```

原诊断已经转为默认套件中的 [ReconciliationRecoveryTest.kt](../tradovate-broker/src/commonTest/kotlin/com/unitrading/tradovate/ReconciliationRecoveryTest.kt)。上述初始化脚本现在只将报告写入 `artifacts/docs-audit/`，不再注入断言缺陷现状的测试源。

结果：原有 127 项回归结果通过，新增 3 个探针也通过，合计 130 项；验证任务保留 Gradle 增量和缓存复用。探针断言的是“等待状态仍然存在”的**缺陷现状**；将来修复后应改成正确恢复的回归测试，而非继续维持这些观测断言。报告、日志为本机生成文件，不纳入 Git。
