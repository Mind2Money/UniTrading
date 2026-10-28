# 交易商插件接入

## 边界

入口为 `broker-api/src/commonMain/kotlin/com/unitrading/broker/BrokerPlugin.kt`。插件使用编译期注册；不从磁盘热加载 JAR，也不提供第三方代码沙箱。

- `BrokerPlugin` 提供交易商信息、能力、品种目录、账户列表、行情状态和历史 K 线。
- `AccountSession` 提供单个账户的不可变状态、预估与交易命令。
- UI 只读状态并提交请求；不得在插件外直接增加余额、订单或仓位。
- 交易商 ID 全局唯一，账户 ID 在交易商内唯一；同名品种不代表同一市场。

当前只实现模拟交易商。新增真实交易商时必须自行实现认证、连接生命周期、服务端账户权限、重连与订单对账；此 Demo 没有完成这些生产能力。

## 注册

新增一个依赖 `:broker-api` 的 KMP 模块并实现 `BrokerPlugin`。在应用组合入口传入插件：

```kotlin
val runtime = TradingRuntime(
    store = DesktopSnapshotStore(),
    additionalPlugins = listOf(MyBrokerPlugin()),
)
TradingScreen(runtime)
```

`TradingRuntime` 的 Demo 时钟和快照只管理内置 Demo 实例。附加插件自行管理行情更新、交易执行和持久化，不会被本地模拟撮合接管。注册时重复 ID 会被拒绝。

界面当前面向本地模拟；生产接入前必须同步替换模拟提示、确认策略与平台凭证存储，不能仅更换接口实现就宣称可用于实盘。

## 接口职责

| 接口 | 约定 |
| --- | --- |
| `descriptor` | 稳定 ID、名称、说明与模拟标识 |
| `capabilities` | 支持的订单类型、保护价修改、挂单改价、对冲模式 |
| `instruments` | 本交易商品种与完整合约规格 |
| `accounts` | 该插件所提供的账户，不跨交易商合并 |
| `market: StateFlow<MarketSnapshot>` | 报价、模拟/服务器时间、连接状态与递增 revision |
| `history(symbol, timeframe)` | 时间升序 OHLC；各周期来自同源价格，不能重新随机生成 |
| `openSession(accountId)` | 返回独立会话；未知账户应拒绝 |
| `state: StateFlow<AccountSnapshot>` | 余额、净值、保证金、仓位、挂单、已平仓历史、活动日志 |
| `preview(request)` | 纯估算，不下单、不占用资金；返回错误与当前估值 |
| `placeOrder(requestId, request)` | 执行前重新校验品种、行情、资金、数量、价格和保护规则 |
| `closePosition` / `cancelOrder` | 按当前状态执行，未知/过期实体拒绝，不串改其他账户 |
| `modifyProtection` | 包含仓位版本，`null` 表示移除对应保护价 |
| `previewOrderModification(orderId, entryPrice)` | 估算已有挂单的新入场价；不占用资金，不新增订单，满挂单容量时也允许修改原单 |
| `modifyOrder(requestId, orderId, expectedVersion, entryPrice)` | 原子修改 Limit/Stop 入场价，保留类型/数量/SL/TP，重新校验价格规则；过期、已成交、已撤销或跨账户订单拒绝 |

交易命令返回 `TradeResult`。`accepted=false` 必须有可读原因；超时不能直接解释为“未成交”，真实适配器需用 requestId 对账。相同账户相同 requestId 的重复命令不能二次成交。

## 数值单位

- `price`：价格最小十进制单位的整数，显示精度为 `priceDecimals`。
- `tickSize`：最小价格变动，占多少个上述整数单位。
- `lotsHundredths`：0.01 手为 1，1.00 手为 100。
- 金额：微美元，1 USD = 1,000,000；展示时转为分，账本保留微美元。
- `tickValueMicrosPerLot`：**一整手**变动一个 `tickSize` 的毛盈亏。
- `commissionMicrosPerLot`：一整手的**单边**手续费。

例如 EURUSD 的 1.08725 用 `108725` 表示，`priceDecimals=5`、`tickSize=1`。若每手每 tick 为 1 USD，则 0.10 手移动 100 tick 的毛盈亏为 10 USD。

`FixedPoint.kt` 提供严格十进制解析、溢出检查、盈亏、手续费及保证金计算。`Double/Float` 仅用于绘图和展示比值，不用于余额或订单账本。当前实现只支持 USD 线性合约，不能直接套用到反向合约、期权或多币种换算。

## Demo 规则

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
./gradlew :broker-api:desktopTest :demo-broker:desktopTest
```

新增适配器至少要验证：Bid/Ask 方向、数量/价格精度、账户隔离、重复请求、过期仓位版本、断连/过期行情、挂单触发、SL/TP 跳空、费用与保证金、持久化恢复。
