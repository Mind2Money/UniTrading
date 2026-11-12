# 今日盈亏：券商交易日口径

当前账户的今日盈亏显示在桌面底部资金栏、账户页“总览”的资金卡片（手机底部“账户”，桌面“账户总览”），以及手机图表下方的下单条。快照时间写在资金栏和资金卡片里；点下单条上的今日盈亏会进入账户页的“交易”页签（持仓与订单），那里不显示快照时间。短屏单行报价条仍省略账户摘要。

## 数据口径

- Tradovate 的数值直接来自 `cashBalance/getcashbalancesnapshot` 返回的 `totalPnL`，不以净值减余额、已加载成交列表、出入金变动或 `realizedPnL + openPnL` 代替。
- 交易日读取同一账户 USD `cashBalance.tradeDate`。交易日可能与设备自然日不同；服务器未提供有效日期时不自行推算。
- 资金栏标明“今日盈亏 USD（券商）”，并显示券商交易日（若提供）和资金快照获取时间（UTC）。数值来自券商资金快照，不保证随每个行情 tick 实时更新，是否已扣除全部费用尚未确认。
- 已实现和未实现分项随快照保存在模型中，当前主界面显示券商总额；原“浮动盈亏”继续独立显示。
- 缺少 `totalPnL`、数值非法或接口返回 `errorText` 时显示“—”，不以零或部分历史成交的累加值填充。实际为零时正常显示 `0.00`。
- 本地 Demo 和其他未提供此字段的插件同样显示“—”。本地模拟交易不会被当作 Tradovate 的当日交易数据。

## 刷新

- 沿用已有账户资金刷新流程：登录、重新同步账户，以及持仓/余额/成交服务端事件后的合并刷新。
- 设有[交易锁定](account-risk-lock.md#交易锁定)的 Tradovate 子账户（包括没有选中的）另外定时刷新：应用运行、登录连接且交易就绪时约每 20 秒取一次快照，快照不到 20 秒时跳过，每次最多等 12 秒。这些请求与下单、改单、撤单共用同一条 REST 队列，见 [Tradovate](tradovate.md#子账户交易锁定)。没有规则的子账户不定时刷新。
- 全量重新同步或相关实体变化时先清除旧资金快照，再接收新快照，避免旧数值与新交易日混用。各账户分别保存，切换账户不复用其他账户的数字。
- 请求期间再次收到相关服务端事件时，按修订号丢弃过期响应，并在合并延迟后重新获取，避免把旧请求结果与新交易日绑定。
- 不为每个报价额外请求资金接口。手动“同步账户”可重新获取快照；网络中断期间可根据显示的快照时间识别旧数据。

## 交易锁定使用的数据

[子账户风控](account-risk-lock.md)的交易锁定用这里的总额判断是否触发，用交易日判断锁定何时结束，所以只在报告交易日的券商上提供（`BrokerCapabilities.tradingDayPnl`，目前只有 Tradovate）。Rithmic 上报的当日盈亏不带交易日，资金栏照常显示，但不能用于交易锁定。快照超过 2 分钟或缺少交易日时，设了交易锁定的子账户暂停开仓，等新快照到达。

## 来源与实现

- [Tradovate 资金快照接口](https://partner.tradovate.com/api/rest-api-endpoints/accounting/get-cash-balance-snapshot)：`totalPnL`、`realizedPnL`、`openPnL`，以及避免高频反复调用的说明。
- [Tradovate Cash Balance](https://partner.tradovate.com/api/rest-api-endpoints/accounting/cash-balance-list)：`tradeDate` 与账户/币种归属。
- [Tradovate 报表说明](https://partner.tradovate.com/resources/admin-dashboards/reports)：Trade Date 是交易会话日期。
- 代码：[DailyPnlMapping.kt](../tradovate-broker/src/commonMain/kotlin/com/unitrading/tradovate/DailyPnlMapping.kt)、[Models.kt](../broker-api/src/commonMain/kotlin/com/unitrading/broker/Models.kt)、[TradingPanels.kt](../composeApp/src/commonMain/kotlin/com/unitrading/ui/TradingPanels.kt)、[MobileTradePanel.kt](../composeApp/src/commonMain/kotlin/com/unitrading/ui/MobileTradePanel.kt)。

## 验证

- 全部 265 项 JVM 测试通过：broker-api 6、demo-broker 34、tradovate-broker 82、composeApp 143；无失败、无跳过。Android debug APK 构建通过。
- 新增映射及协议测试覆盖负数/零值/缺失/非法金额、错误响应、券商交易日与自然日不同、账户隔离、服务端事件更新、重新同步缺失值、请求期间交易日变化及过期响应丢弃。
- UI 测试覆盖盈亏符号、券商交易日与 UTC 更新时间、未知值占位、手机浮动盈亏和今日盈亏分开显示，以及点击进入交易页。
- 证据：`artifacts/daily-pnl/result-2.json`、`build-2.log`；截图：`artifacts/daily-pnl/summary.png`。协议和截图测试使用合成账户数据。
- 本次未进行 Tradovate 真实账户现场核对、Android 真机、iOS 或 R8 release 验证。
