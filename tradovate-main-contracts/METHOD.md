# 主力合约方法抽离与移植说明

## 来源与边界

从 UniTrading 源码基线 `67e2be9` 抽离并用 Python 重写，并纳入工作区当前已存在的固定日期窗口修正：日线范围不混用 `asMuchAsElements`。本次抽离不修改原项目券商实现。

| 原项目位置 | 独立版对应位置 |
| --- | --- |
| `tradovate-broker/.../ContinuousContracts.kt` 中 `completedSession`、`selectVolumeLeader`、`dailyVolumeRange`、`DailyVolumeHistory` | `main_contracts/core.py` |
| `TradovateBroker.kt` 中 `completedDailyVolume`、`requestMarketChart` | `main_contracts/client.py` |
| `TradovateSocket.kt` 的开场、认证、心跳、请求及事件帧 | `main_contracts/client.py` |
| `TradovateSession.kt` 的固定环境地址 | `main_contracts/client.py` |
| 原项目按已发现产品解析主力 | 独立版增加 `product/list` 全品种遍历 |

主力是此算法的**成交量主力**定义，不将 `isFront`、最近到期、持仓量最大或搜索建议顺序当成主力。输出 `@ROOT → ROOT月份年份` 映射；连续 K 线的拼接、复权、图表权限、下单和登录不在本模块中实现。

## 数据流

```text
accessToken + environment
  → GET product/list
  → 对每个 productType=Futures 的产品：
      GET contractMaturity/deps?masterid=productId
      → 过滤 expirationDate > 查询时刻 的月份
      GET contract/ldeps?masterids=月份ID列表
      → 校验全部月份都有唯一且同根代码的合约
      GET tradingSession/ldeps?masterids=月份ID列表
      → 校验全部月份的上一完整交易日相同
      对每个月份 md/getChart，读取目标交易日日线成交量，随后取消订阅
      → 唯一正最大者
      → 检查有效期
      → 输出 @产品根代码、实际合约名、成交量及证据日期
```

`product/list` 的用途是发现目录；**实际候选全集来自月份依赖查询**。不能用限制条数的 `contract/suggest` 作为候选全集。`ldeps` 每批最多 50 个月份 ID；候选月份和实际合约各最多 200 个。超过上限报错，不截断后排名。

月份必须属于当前产品，ID 不重复；每个未到期月份必须对应一个合约，合约 ID 与月份 ID 都唯一。实际合约名必须匹配 `根代码 + [FGHJKMNQUVXZ] + 1至4位年份数字`。数据缺失、混入其他产品或只返回部分月份，均无法确定主力。

## 上一完整交易日

对每个月份读取 `tradingSession`，使用：

```json
{"contractMaturityId":123,"tradeDate":{"year":2026,"month":11,"day":2},"startTime":"2026-11-01T23:00:00Z","endTime":"2026-11-02T22:00:00Z"}
```

1. `tradeDate` 必须是合法日期，`startTime < endTime`。
2. 同月份每个交易日只允许一个时段；按结束时间排序后，交易日期严格递增、时段不得重叠。
3. 在 `endTime <= now` 的时段中选结束时间最近的时段作为上一完整交易日。
4. 所有候选月份必须得到相同 `tradeDate`，再比较成交量。

不使用设备的“昨天”，不按固定北京时间或交易所时区推算假期，不用尚未结束的当日成交量。`openTime`、`closeTime` 可能在交易起止区间之外，不纳入这项起止校验。

若存在后续时段（`endTime > now`），该月份的有效期截止到最近后续时段的结束。如果没有后续时段，仅接受 `now < 上一完整时段结束 + 72小时`，标记 `calendar_tail=true`。任一月份进入尾部规则时，全部月份都要求明确实时行情权限和实时通道，且结果最多保留 15 分钟。

最终 `valid_until` 取所有月份的下一时段结束/日历尾部上限、全部候选最早到期时间的最小值；尾部再与本次开始查询时刻 + 15 分钟取最小值。查询完成后重新检查，过期不输出成功。

## 日线请求与成交量证据

以 `tradeDate=2026-11-02` 为例：

```json
{
  "symbol":"MESZ6",
  "chartDescription":{"underlyingType":"DailyBar","elementSize":1,"elementSizeUnit":"UnderlyingUnits","withHistogram":false},
  "timeRange":{"asFarAsTimestamp":"2026-11-02T00:00:00Z","closestTimestamp":"2026-11-02T23:59:59.999Z"}
}
```

日线时间戳标注交易日期，查询范围用该交易日期的 UTC 零点至当日末毫秒，不能替换成交易时段实际起止时间。固定窗口只给 `asFarAsTimestamp`、`closestTimestamp`，不加 `asMuchAsElements`；官方将最近 N 根与固定日期窗口定义为互斥策略。[官方图表时间范围说明](https://partner.ninjatrader.com/connect/overview/core-concepts/web-sockets/market-data/charting-best-practices)

- 包或 bar 含 `td` 时，两个位置的日期必须一致。若时间戳恰为 UTC 零点，也必须与 `td` 同日。
- 没有 `td` 时，时间戳必须是对应日期的 UTC 零点；不能将任意盘中时间当成日线交易日期。
- 只统计目标交易日。更早的日线表示该月份最近成交在旧日期；较新的日线可能仍在形成，不参与排名。
- 目标日的 `upVolume` 和 `downVolume` 都必须存在且为非负整数，使用精确整数求和。
- 必须收到明确的 `eoh: true` 才能完成历史采集；目标日最终仅允许一根唯一日线。
- 只有成功、明确 `RealTime` 的订阅可以把完整空历史或仅更早日线视为零。若只有较新日线，没有目标日或更早日线，不能推断目标日为零。
- 普通日历允许 ACK 缺少 `mode`，但拒绝明确的非 `RealTime`；缺少 `mode` 时空历史不能计零。日历尾部要求 `mode=RealTime` 且存在实时通道。
- 在读完至取消订阅期间若目标日成交量发生修正，使本次结果失效。

全部候选取量成功后，只返回成交量大于零且唯一的最大者。任何候选查询失败不能当成零跳过；全零、并列、缺字段、超时、无结束标志均失败。

## 网络协议与资源释放

REST 使用 Bearer token，固定官方域名：

| 环境 | REST | 行情 WebSocket |
| --- | --- | --- |
| demo | `https://demo.tradovateapi.com/v1` | `wss://md-demo.tradovateapi.com/v1/websocket` |
| live | `https://live.tradovateapi.com/v1` | `wss://md.tradovateapi.com/v1/websocket` |

客户端拒绝自定义目标地址与 HTTP 跳转。REST 仅允许本方法使用的只读端点。

Socket 收到 `o` 后发送 `authorize\n请求ID\n\nTOKEN`；业务请求为 `路由\n请求ID\n\nJSON`。服务端 ACK 用 `i` 关联请求、`s` 表示状态、`d` 承载数据；事件来自 `a[...]` 内的 `e=chart` 帧，也支持裸对象帧。连接期间发送 `[]` 心跳，间隔 2.5 秒。

必须缓冲先于 ACK 的 chart 事件，注册 `historicalId` 后重放。缓冲有界，溢出报错；不能丢包后仍将空历史视为零。通道 ID 保留原始值供取消，同时按规范化键匹配，兼容整数、小数、字符串以及历史/实时共享 ID。

每次请求结束，无论成功失败都尝试取消两个通道，去重共享 ID。无法确认取消或 ACK 超时则关闭连接，释放可能未知的订阅。REST 读取前关闭已有行情连接，避免同步 REST 阻塞期间漏发心跳；之后按需重新连接。同一品种各月份之间可复用连接。

服务端返回同时图表数量上限时最多按 2 秒、4 秒退避；返回 `p-ticket` 时仅接受无 captcha 且 `p-time` 为 0–30 秒，等待后携带 ticket 重试一次。仍受限或要求 captcha 则失败。不能无限重试、无限等待或把限流当成零成交量。

## 独立版的明确差异

- 原项目每品种最多并发 3 次取量；独立版串行取量，方便移植和控制图表订阅数。
- 原项目持久化已完成日成交量、复用主力结果，并针对日历尾部复查缓存领先者；独立版不保存跨调用缓存，每次重新读取所有月份。因此无需缓存修正重排逻辑。
- 原项目尾部结果最多缓存到确认完成后 15 分钟；独立版保守地用开始查询时刻 + 15 分钟。
- 原项目 UI 保留上次主力的报价显示；独立版失败就输出错误，没有上次结果回退。
- 原项目工具发现并显示 USD 线性期货规格；本算法本身不依赖币种或 tick value，独立版按请求遍历全部可见 Futures 产品。
- 原项目自动维护账户和行情连接；独立版只消费调用方提供的 token，不刷新凭据。

若在其他语言重写，必须保留候选完整性、服务端完整交易日、日线证据结束、唯一正最大以及有效期校验。是否增加并发、缓存和续期由接入项目决定；增加缓存时需要同时迁移缓存失效和修正检测，不能只缓存合约名。

## 资料与验证边界

- [Tradovate 官方 API](https://api.tradovate.com/)：`product/list`、合约依赖、交易日历和行情协议。
- [Tradovate 官方 JavaScript 示例](https://github.com/tradovate/example-api-js)：连接、认证及行情请求示例。
- [websocket-client 官方文档](https://websocket-client.readthedocs.io/en/latest/)：Python socket 接口。

算法细节以以上源代码基线为抽离依据；官方接口资料用于核对目录端点及连接方式。独立版附离线回归测试，未以真实账户执行全品种扫描，不宣称所有目录品种均有可用日线权限。
