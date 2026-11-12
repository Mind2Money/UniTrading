# 交易商插件接入

## 边界

入口为 `broker-api/src/commonMain/kotlin/com/unitrading/broker/BrokerPlugin.kt`。插件使用编译期注册；不从磁盘热加载 JAR，也不提供第三方代码沙箱。

- `BrokerPlugin` 提供交易商信息、能力、品种目录、账户列表、行情状态和历史 K 线。
- `AccountSession` 提供单个账户的不可变状态、预估与交易命令。
- UI 只读状态并提交请求；不得在插件外直接增加余额、订单或仓位。
- 交易商 ID 全局唯一，账户 ID 在交易商内唯一；同名品种不代表同一市场。

默认注册表为空，登录成功后注册 `tradovate-broker` 或 `rithmic-broker`（R | Protocol 直连，见 [Rithmic 接入](rithmic.md)）。本地 Demo 实现保留为编译依赖与离线测试引擎，需显式启用 `includeDemoBrokers = true`。Tradovate 通过官方网页登录，账户状态以服务器为准；代码/协议测试不等于真实交易联调，边界见 [Tradovate 使用与验证](tradovate.md)。

## 注册

新增一个依赖 `:broker-api` 的 KMP 模块并实现 `BrokerPlugin`。在应用组合入口传入插件：

```kotlin
val runtime = TradingRuntime(
    store = DesktopSnapshotStore(),
    additionalPlugins = listOf(MyBrokerPlugin()),
)
TradingScreen(runtime)
```

`TradingRuntime` 的 Demo 时钟和快照仅在显式启用测试引擎时管理 Demo 实例。附加插件自行管理行情更新和交易执行；Tradovate 凭据由应用层 `SavedTradovateAccounts` 配合平台 `CredentialStore` 保存，服务器账户状态仍重新同步。构造注册表时重复 ID 被拒绝；`registry.register(plugin)` 更新连接并断开旧实例。`registry.unregister(id)` 允许移除最后一个插件，UI 回到登录页。

目录支持异步发布，但当前 UI 对“非空目录移除已选品种”的回退不完整；接入会替换/删减品种的插件前需处理[审计 A5](implementation-audit.md)。

工作台按 `BrokerEnvironment` 区分本地模拟、远程 Demo 和 Live；未知费用、保证金与资金值必须携带 `Known=false`，不能用 0 冒充已知数值。

## 接口职责

| 接口 | 约定 |
| --- | --- |
| `descriptor` | 稳定 ID、名称、说明与模拟标识 |
| `capabilities` | 支持的订单类型、保护价修改、挂单改价、对冲模式；`tradingDayPnl` 表示账户快照的当日盈亏（`DailyPnl`）带券商交易日，默认 false，只有它为 true 时应用才提供子账户的交易锁定，见[子账户风控](account-risk-lock.md) |
| `instruments` | 本交易商品种与完整合约规格 |
| `accounts` | 该插件所提供的账户，不跨交易商合并 |
| `catalog: StateFlow<BrokerCatalog>` | 异步发布当前账户与品种目录；允许空列表，不生成本地替代数据 |
| `searchInstruments` / `watch` / `disconnect` | 搜索服务器合约、订阅所选行情/周期、关闭连接；本地 Demo 可为空实现 |
| `market: StateFlow<MarketSnapshot>` | 报价、模拟/服务器时间、连接状态与递增 revision |
| `history(symbol, timeframe)` | 时间升序 OHLC；各周期来自同源价格，不能重新随机生成 |
| `preloadHistory(series)` | 按优先级给出要在后台读全并缓存的序列，每次调用替换上一份列表；不改变当前图表，不发交易指令。历史在本地生成的插件可为空实现，见 [K 线缓存](candle-cache.md#后台预读) |
| `refreshDailyPnl(accountId)` | 重新获取一个账户的当日盈亏快照，默认空实现。设了交易锁定的子账户约每 20 秒调用一次（快照不到 20 秒时跳过，最多等 12 秒），未选中的子账户也会调用；Tradovate 用 `cashBalance/getcashbalancesnapshot`，与交易指令共用请求节流 |
| `openSession(accountId)` | 返回独立会话；未知账户应拒绝 |
| `state: StateFlow<AccountSnapshot>` | 余额、净值、保证金、仓位、挂单、已平仓历史、活动日志 |
| `preview(request)` | 纯估算，不下单、不占用资金；返回错误与当前估值 |
| `placeOrder(requestId, request)` | 执行前重新校验品种、行情、资金、数量、价格和保护规则 |
| `closePosition` / `cancelOrder` | 按当前状态执行，未知/过期实体拒绝，不串改其他账户 |
| `modifyProtection` | 包含仓位版本；本地 Demo 接受 `null` 移除保护。Tradovate 仅改一条已有保护价，拒绝增加/移除；接口当前没有单独表达这些细粒度能力 |
| `previewOrderModification(orderId, entryPrice)` | 估算已有挂单的新入场价；不占用资金，不新增订单，满挂单容量时也允许修改原单 |
| `modifyOrder(requestId, orderId, expectedVersion, entryPrice)` | 修改 Limit/Stop 入场价，保留类型/数量/SL/TP；Demo 为本地原子操作，远程需等待服务器回执，版本仅做客户端陈旧编辑检查 |

挂单（`PendingOrder`）的两项字段影响[子账户风控](account-risk-lock.md)怎样计算持仓总量和减仓单：

- `ocoGroup`：同属一组括号单的止损腿和止盈腿给同一个值，表示其中一条成交后券商撤掉其余（多目标括号把止损减量）；默认 null，表示券商没有说明。应用一组只按止损腿合计和止盈腿合计中较大的一个计，没有组的单按全部数量计。只有券商确实这样撤单时才能填，填错会少算可能反手的数量。Tradovate 填在 OCO 关联的两条腿和单层 ATM 的两条腿上，Rithmic 填同一入场单的子单（值为入场单的 basket id），本地模拟不填（止损止盈在持仓上）。
- 入场单的 `stopLossOrderId` / `takeProfitOrderId`：指向挂在它上面的止损单和止盈单。入场单还没有任何成交时，应用不把这两张单计入持仓总量：它们要等入场成交才生效，之后也只平掉入场加上的仓位。所以只能填入场成交前不会成交的括号单。目前只有 Tradovate 填写（OSO 括号的子单，入场成交前为 Suspended）；Rithmic 不填，未成交入场单的括号按跳数档位记在入场单上，没有单独的单号。

止损止盈的价格规则在 [ProtectionPrices.kt](../broker-api/src/commonMain/kotlin/com/unitrading/broker/ProtectionPrices.kt)，插件直接调用，不要各写一份。`entryProtectionError` 用于未成交订单：按订单方向与入场价比较，市价单要越过整个买卖价差。`positionProtectionError` 用于已有持仓：只校验改动的那条线，只和当前可平仓价比较，不看入场价，这样推保本和把止损推过入场价都不受影响。

交易命令返回 `TradeResult`。`outcome` 区分 ACCEPTED、REJECTED、UNKNOWN；`accepted=true` 在远程插件中只表示已受理，不等于成交。结果未知时，不能据此判断未成交，也不能自动重发。示例远程插件按会话去重，同请求 ID 不重复发送；重新登录后应先核对服务器状态，内存中的去重记录不保证跨会话有效。

## 数值单位

- `price`：价格最小十进制单位的整数，显示精度为 `priceDecimals`。
- `tickSize`：最小价格变动，占多少个上述整数单位。
- `lotsHundredths`：内部百分之一数量单位。Demo 的 1.00 手和期货的 1 张均表示为 100；实际输入/显示由 `quantityUnit` 与 `quantityDecimals` 决定。
- 金额：微美元，1 USD = 1,000,000；展示时转为分，账本保留微美元。
- `tickValueMicrosPerLot`：**一整手**变动一个 `tickSize` 的毛盈亏。
- `commissionMicrosPerLot`：一整手的**单边**手续费。

例如 EURUSD 的 1.08725 用 `108725` 表示，`priceDecimals=5`、`tickSize=1`。若每手每 tick 为 1 USD，则 0.10 手移动 100 tick 的毛盈亏为 10 USD。

`FixedPoint.kt` 提供严格十进制解析、溢出检查、盈亏、手续费及保证金计算。`Double/Float` 仅用于绘图和展示比值，不用于余额或订单账本。当前实现只支持 USD 线性合约，不能直接套用到反向合约、期权或多币种换算。Tradovate 价格内部保留六位小数，`displayPriceDecimals` 控制显示；远程费用/订单保证金尚无完整估算，不得套用 Demo 杠杆与费用。

## 离线 Demo 引擎规则（默认不注册）

- 市价买入按 Ask、卖出按 Bid；多头退出看 Bid，空头退出看 Ask。
- Limit 按可成交报价执行且不劣于限价；Stop 越过触发价后按实际报价执行。
- SL/TP 根据实际有序 tick 触发，跳空时不承诺按保护价格成交。
- 挂单不预占保证金；触发时再次检查保证金与保护价。
- 多仓位对冲，不提供净额、部分成交或部分平仓。
- 保护价相对当前退出报价校验；盈利中的仓位允许把 SL 调到入场价有利一侧。
- 强平阈值为 50%，优先关闭当前最亏损仓位；是本地演示规则。
- 同一引擎的两个配置分别注册为两个交易商；新增交易商无需改动图表或下单组件。

## 测试入口

```bash
./gradlew :broker-api:desktopTest :demo-broker:desktopTest :tradovate-broker:desktopTest :composeApp:desktopTest
```

新增适配器至少要验证：Bid/Ask 方向、数量/价格精度、账户隔离、重复请求、过期仓位版本、断连/过期行情、挂单触发、SL/TP 跳空、费用与保证金、持久化恢复。
