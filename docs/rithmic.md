# Rithmic 接入与验证

> 文档类型：当前实现说明。首版接入日期 2026-11-05。探针已在 Rithmic Test 与 Rithmic Paper Trading（TPT 账号）实测登录、账户、RMS、行情、历史 K 线。`RithmicBroker` 的只读流程也已在 Paper Trading 上通过：连接、`@MES` 解析、实时报价和 1 分钟图。用户已在应用内完成下单、改单和撤单，并确认行情、图表和历史配对。平仓只在离线假服务器上验证，探针的下单测试尚未运行。
>
> 应用内入口：已开启（`TradingScreen.kt` 的 `RITHMIC_LOGIN_ENABLED = true`，2026-11-06）。登录页、设置、账户切换和空合约页均可进入 Rithmic 登录。注意：Rithmic 要求接入生产系统（含 Paper Trading）前通过 conformance，UniTrading 尚未通过。

## 接入方式

Rithmic 没有网页登录，应用通过 **R | Protocol API™**（WebSocket + Google Protocol Buffers）直接登录。在登录页点击“登录 Rithmic”，或在设置 / 账户切换中选择“连接 Rithmic”：

1. 选择环境：**Rithmic Test**（开发测试系统，网关 `wss://rituz00100.rithmic.com:443`，系统列表只有 `Rithmic Test`）、**Rithmic Paper Trading** 或 **Rithmic Live**。后两者预填芝加哥生产网关 `wss://rprotocol.rithmic.com:443`：2026-11-05 用系统列表请求确认可达，列出 29 个系统，包括 `Rithmic 01`、`Rithmic 04 Colo`、`Rithmic Paper Trading` 以及 `Apex`、`TopstepTrader`、`TheTradingPit`、`Bulenox`、`Earn2Trade` 等 prop firm 系统。Rithmic 提供区域网关（公开客户端中出现过 `rprotocol-ie` / `-de` / `-sg` 等主机）时可替换。
2. 网关地址只接受 `wss://<主机>.rithmic.com[:端口]`；其他主机被拒绝，凭据不会发往别处。
3. 点击“查询”向网关请求系统列表（模板 16/17，不含凭据），确认系统名称；登录时系统名称必须在该列表内。
4. 输入 Rithmic 登录 ID 与密码。勾选“在本机加密保存密码”后，凭据与 Tradovate 会话一同写入 DPAPI / Keystore 加密文件，下次启动自动恢复；不勾选则仅本次运行有效。
5. 登录成功后账户、行情和历史由服务器提供，连接后不会自动下单。

Rithmic 要求应用在接入生产系统（Rithmic 01、Paper Trading 及各 FCM 系统）前通过 conformance 审核，且 `app_name` 需要在 Rithmic 登记；R | Protocol API 不提供给带实时行情的纸面交易试用账户（见 [rithmic.com FAQ](https://www.rithmic.com/faq)）。开发者可在 [signup.rithmic.com/sdk](https://signup.rithmic.com/sdk/sdk.html) 申请 R | Protocol 开发包。本实现默认 `app_name = UniTrading`、`app_version = 1.3.0`、`template_version = 5.55`（官方开发包 0.90.0.0 示例所用）。

## 协议实现

模块 `rithmic-broker/`，不依赖 protobuf 代码生成：

| 文件 | 职责 |
| --- | --- |
| `Protobuf.kt` | 手写 proto3 wire 编解码（varint / fixed64 / length-delimited）与 4 字节大端长度帧 |
| `RithmicProtocol.kt` | 字段编号（MNM id + 100000 偏移）、模板 id、枚举常量、返回码判定 |
| `RithmicTransport.kt` | Ktor WebSocket 二进制帧传输，网关白名单 |
| `RithmicPlant.kt` | 单个 plant 连接：`user_msg` 关联、多段回复聚合、`rp_code` 终止判定、登录、心跳、强制登出 |
| `RithmicSession.kt` | 环境、系统名称、凭据模型与 broker id |
| `RithmicDrawdown.kt` | 自动平仓规则、爆号线和账户价值（只用于显示） |
| `RithmicMapping.kt` | 参考数据 → 合约规格、订单 / 成交 / 持仓 / 资金解析、K 线时间换算 |
| `RithmicBroker.kt` | `BrokerPlugin` 实现 |

一次登录打开四个 plant：ORDER（账户、路由、订单、成交）、PNL（持仓与资金）、TICKER（参考数据、搜索、主力合约、Bid/Ask、逐笔）、HISTORY（历史与实时 K 线）。登录前先用独立连接查询系统列表并关闭，与官方说明一致。

| 能力 | 模板 | 说明 |
| --- | --- | --- |
| 账户 | 300/302/304 | `fcm_id` / `ib_id` 来自登录信息；RMS 的 `max_order_quantity` / 买卖限额作为数量上限 |
| 交易路由 | 310 | 按交易所取默认路由；缺少路由时拒绝下单 |
| 订单快照与推送 | 320、308/336，351/352/353 | 状态按 Rithmic 状态文本判定；`original_basket_id` 识别括号子单 |
| 成交 | 352 FILL、3512 | 保留最近 36 小时成交历史作为执行记录；不生成配对 `ClosedTrade` |
| 持仓与资金 | 402/400，450/451 | 净额持仓、均价、开仓盈亏、账户余额 / 现金 / 已用购买力、当日盈亏 |
| 合约规格 | 14 | 仅 USD 期货；tick 取 `min_qprice_change`（报价最小变动，行情/K 线/下单都用报价），`single_point_value` 为每报价点价值；`min_fprice_change` 是 f-price tick（ES 为 25.0，而报价 tick 为 0.25），不直接作 tick |
| 搜索与主力 | 109、113 | 搜索先按 `product_code` 过滤，无结果再用 CONTAINS；`@产品代码` 连续合约映射到服务器返回的 front month，服务器无数据（Rithmic Test 即如此）时取该产品最近未到期合约。按产品搜索不带交易所，结果可能跨交易所：优先用账户有交易路由的交易所，被拒绝时换下一个，错误信息逐个写明交易所。连接后品种列表为空时自动搜索 `MES`（与 Tradovate 适配器一致），否则应用会停在手动搜索页。登录完成后在后台加载品种列表：账户有产品风控行（306）时就用这份列表，逐个查交易所、主力和规格；没有时退回 time-of-interest 产品（111）。最多 200 个，并行 2 路，被服务器拒绝的产品直接略过，不报错。Paper Trading 列出的裸产品行（如 `MES`，名称为 `Front Month for ...`）是 front month 别名，不是合约，不进入品种列表，连续图表的历史由它回放 |
| 行情 | 100，150/151 | BBO 作为 Bid/Ask，逐笔推进正在形成的 K 线；`clear_bits` 清空一侧时撤下报价 |
| K 线 | 202、200/250 | 秒 / 分钟 / 日线回放与订阅；分钟 `marker` 为收盘时间，日线用 `YYYYMMDD`。秒级周期用 `SECOND_BAR`（类型 1）和秒数作周期，`marker` 按分钟线同样的规则当作收盘时间处理，这两点都没有在真实服务器上见过。`@产品代码` 的历史回放裸产品代码（front month 别名），服务器在别名下保留换月前的 K 线；已到期合约不能单独回放，所以客户端无法自行拼接。实时 K 线、逐笔和报价仍订阅到期合约。没有别名的系统（Rithmic Test）退回到期合约自身的历史，在状态栏说明，这份结果不写入缓存 |
| 下单 | 312 / 330 | 市价、Limit、Stop Market；带 SL/TP 时用静态括号单（tick 距离） |
| 改价、撤单、平仓 | 314、316、3504 | 仓位保护价修改作用于对应括号子单 |
| 入场后添加止损止盈 | 312 / 328 | 单条为普通 Stop/Limit，两条为 OCO；已有一条时先 316 撤销再 328 |
| 未成交入场单的括号 | 338/340、332/334 | 以 tick 档位显示和修改，每次一条 |

## 订单流分析

登录后点击桌面最右侧图标栏的“订单流面板”，或手机图表右上角的订单流图标。桌面首次展开时，订单流占原主图区域的右半边，下单、行情及下方交易区保持显示；拖动图表与订单流之间的分隔线可调宽，比例本机保存，双击分隔线恢复对半。再次点击侧栏图标或面板关闭按钮可收起。分栏后的图表较窄时使用可横向滚动的紧凑工具栏。手机显示独立分析页，系统返回键也可关闭。分析页只读，不提交交易指令。

右侧使用 Material 3 原生侧栏和独立开关，文字“行情、下单、交易、订单流”常显；各面板可同时展开，开关显示对应展开状态。

- **足迹**：按当前图表周期聚合，每个价位显示主动卖出 Bid × 主动买入 Ask；支持按 1 / 2 / 4 / 8 tick 合并价位。
- **成交量分布**：按逐笔实际成交价累计数量，显示 POC 和 70% 成交量价值区。不同于画图工具中的 K 线估算分布。
- **Delta / CVD**：主动买入量减主动卖出量，CVD 从当前保留窗口起点累计；服务端没有提供主动方向的成交独立统计，不按价格涨跌猜测。
- **逐笔成交**：显示时间、价格、数量和主动方向，可过滤最小成交量，暂停只冻结显示，后台仍继续采集。
- **DOM**：独立订阅多档价格聚合深度（模板 156，订阅位 4），按标准 BEGIN / MIDDLE / END 批次原子提交增量。无 L2 权限或服务器未提供深度时显示对应状态；买一卖一数量来自模板 151，不伪装成多档深度。`SNAPSHOT_IMAGE` 的全簿 / 单侧替换边界尚未获得原厂说明或真实 L2 联调验证，因此收到该类型后停止多档构簿，仅显示真实买一卖一，直到服务器明确清空盘口或开始新订阅。

数据范围是本次分析订阅以来、最多最近 **20,000 笔**真实成交。没有历史逐笔回补，不代表完整交易日；超过上限后统计随窗口滚动。关闭、断线重连、切合约与连续合约换月均重新采集，避免把有缺口的数据接成完整统计。未获得新成交或深度时保留明确空态。

界面工具分组参考 [ATAS 的 Delta 与 CVD 说明](https://atas.net/blog/main-advantages-of-the-atas-platform/) 和 [Tape / DOM / Footprint 说明](https://atas.net/docs/Tape_and_DOM.pdf)。协议字段依据 R | Protocol 的 `last_trade.proto`、`order_book.proto` 和 `request_market_data_update.proto` 原始定义核对；不接入 ATAS 服务，也不借用其应用登录标识。

## 交易边界

- 数量为正整数张，价格按 tick 整数表示；预估只给毛盈亏，手续费与保证金标为未知。
- 括号保护只能通过新订单附带；已有净仓不能叠加括号。
- 入场后添加止损止盈（2026-11-12 起）：止损是普通 Stop Market 单、止盈是普通 Limit 单，数量等于净仓，`user_tag` 为 `UT-P-<请求 ID>`。只加一条用 312；两条同时加用 `RequestOcoOrder`（模板 328，回复 329），每个订单字段按腿重复一次（第 0 位止损、第 1 位止盈），不用的价格字段填 0，账户字段只发一次，数量只发 4 字节 `quantity`。已有一条时再加另一条：先 316 撤掉已有那条，等订单推送显示它已结束（最多 10 秒），确认没有成交、净仓未变、报价有效、价格仍合法后，再用 328 同时下两条；其间约 1–2 秒没有那条保护（用户 2026-11-12 选定，不用 344 关联现有订单）。带此标签的非括号 Stop/Limit 与括号子单一样算作仓位保护；止损和止盈来自不同的括号或不同的请求标签时，两条不会互撤，仓位保护只读。328/329 的字段取自开源库 rithmic-rs 3.3.0 生成的 `RequestOcoOrder` / `ResponseOcoOrder`，没有对照官方开发包，也没有在真实服务器上发过；单独撤销 OCO 的一条时服务器是否连带撤销另一条未核实。
- 市价单附带 SL/TP 时，Rithmic 按实际成交价加减相同 tick 数生成保护线，实际价位可能与输入不同。
- 同一合约存在多个括号子单时仓位保护进入只读，不猜测关联。这时每个括号子单可以单独改价格（模板 314，按该子单的 basket id，2026-11-10 起）；数量不变，因为单独改括号子单的数量没有在服务器上试过。止损限价单（stop-limit）不提供单独修改，模板 314 会把它改成止损市价单。只经过离线测试。
- 受理不等于成交。请求超时、连接断开或缺少回执时结果标记为未知并暂停后续写入，等待订单 / 仓位推送或手动同步确认；不会自动重发。
- 任一 plant 断开后自动重连最多三次，重新订阅行情与 K 线，不重放交易指令；服务器强制登出（并发会话超限）同样触发。
- 日线只接受服务器 K 线，不用逐笔推进；分钟线回放一次最多 10,000 根，超出时只返回窗口内最早的 10,000 根，应用请求约四倍图表宽度的时间窗，不会超出。
- 自选品种的当前周期和收藏周期在后台回放并缓存，打开图表时只回放缓存末根之后的部分，见 [K 线缓存](candle-cache.md#后台预读)。这条路径只在离线假服务器上验证过。
- [子账户风控](account-risk-lock.md)的交易锁定在 Rithmic 登录上不可用：Rithmic 上报的当日盈亏不带交易日（`tradingDayPnl` 为 false），设置里这一行不能设置；旧文件留下的规则不生效，打开后只能停用。手数与品种限制和订单风控可以用；订单风控的金额止损止盈用在市价单上时，实际价位按上面第三条由成交价决定，下单确认框会显示 Rithmic 的这条提示。

## 账户风险度

桌面底部资金栏和账户页“总览”的“回撤与风险”卡片显示当前账户的“可回撤额度”和“危险度”，算法与 BatchTradovate 相同。

- **爆号线**是 Rithmic 上报的 `auto_liq_threshold_current_value`：登录时来自账户列表（303），之后由风控推送更新（订阅 3508，推送 356）。它对应 R | Trader 的 Auto Liquidate Threshold Value。系统不提供风控推送时登录不受影响，爆号线停在登录时的值。
- `auto_liquidate_threshold` 的含义随规则变化：Minimum Account Balance 下是余额线本身，Trailing 和 End of Day Drawdown 类规则下是回撤距离。
- End of Day Drawdown 类规则和 Multiple Simultaneous Criteria 没有当前值时，程序用实时读到的数字计算爆号线，不保存任何记录：上次收盘 = 余额 − 当日已平仓盈亏，爆号线 = 上次收盘 − 回撤额度，到 `auto_liquidate_max_min_account_balance` 封顶并显示“已锁定”。券商上报当前值后以当前值为准。
- Multiple Simultaneous Criteria 按 EOD 显示：回撤额度取 `auto_liquidate_threshold`，爆号线不低于券商的 `min_account_balance`（账户盈亏推送优先，其次风控资料 305）。
- 其他规则没有上报当前值时显示“券商未上报爆号线”，可回撤额度和危险度显示“—”，悬停或手机卡片上可以看到券商的规则名和原始数字。Loss Limit 和 Profit Limit 类规则是金额，不是余额线，没有爆号线。
- **可回撤额度** = 账户价值 − 爆号线。账户价值是余额加各持仓的 `open_position_pnl`，空仓时是余额；持仓盈亏未知时用账户级净值。余额的读法与资金栏相同，不会重复计算持仓盈亏。
- **危险度**的分母：Trailing 和 End of Day Drawdown 类规则用 `auto_liquidate_threshold`，其他规则用峰值余额减爆号线。两者都没有时只显示额度，危险度显示“—”。
- 算出的 EOD 线是计算值。真实的 EOD 线不会回落，算出的线会随亏损日回落（不低于最低余额），所以亏损日之后额度会偏大，请以券商后台或 R | Trader 的 Auto Liquidate Threshold Value 为准。
- 尚未在 UniTrading 里用真实账户核对。BatchTradovate 在真实 Paper Trading 账户上登录时没有收到当前值，也没有收到推送。

## 联调探针

`rithmic-broker` 带一个探针，用真实系统核对上面的协议假设。它做登录、列表、回放、订阅；在 `Rithmic Test` 模拟系统上默认再跑一次下单测试（见下）。设 `RITHMIC_PROBE_READONLY=1` 则只读、不发送任何下单类请求。凭据只从环境变量读取，不出现在命令行、构建日志或输出中（输出里的密码会被替换为 `[redacted]`）。

```powershell
$env:RITHMIC_GATEWAY = 'wss://rprotocol.rithmic.com:443'   # 省略则为 Rithmic Test 网关
$env:RITHMIC_SYSTEM  = 'Rithmic Paper Trading'              # 省略则只打印该网关的系统列表
$env:RITHMIC_USER    = '<登录 ID>'
$env:RITHMIC_PASSWORD = Read-Host 'Rithmic 密码' -MaskInput  # PowerShell 7；不要写在命令里，否则会明文留在终端
.\gradlew.bat :rithmic-broker:rithmicProbe -q --console=plain --no-daemon
```

可选：`RITHMIC_APP_NAME` / `RITHMIC_APP_VERSION`（默认 `UniTrading` / `1.6.0`）、`RITHMIC_PRODUCT` / `RITHMIC_EXCHANGE`（默认 `MES` / `CME`）、`RITHMIC_SYMBOL`（跳过主力查询）、`RITHMIC_ACCOUNT`（默认第一个账户）、`RITHMIC_PLANTS`（`order,pnl,ticker,history` 子集）、`RITHMIC_LISTEN_SECONDS`（实时订阅保持时间，默认 75 秒）、`RITHMIC_PROBE_READONLY=1`（跳过下单测试，只读）、`RITHMIC_CONTINUOUS_PROBE=MNQ,MGC,MCL`（只跑连续历史检查：只登录行情和历史 plant，打印合约列表、别名回放、最近 52 个月份合约的日线、分钟数据深度和回放截断情况）。

逐请求检查绕过 `RithmicBroker`，不能证明应用流程可用。所以探针最后会退出这些连接，再用 `RithmicBroker`（应用实际使用的适配器）跑约 45 秒：连接、解析 `@<产品>` 连续合约、订阅行情、加载并跟踪 1 分钟图。这一段在任何系统上都只读，不调用下单接口；应用是否可用以 `[broker] RESULT` 这一行为准。可把 `RITHMIC_LISTEN_SECONDS` 设小以缩短前面的监听时间。

登录时同步的内容：账户、账户风控、产品风控（306：单笔上限取账户与产品中更严的，手续费进入下单预估）、交易路由、挂单、括号、最近 7 天成交、持仓与资金，以及后台加载的品种列表。余额取 `account_balance`，它包含当日已实现盈亏和手续费，`cash_on_hand` 当日不变。开发包没有说明 `account_balance` 是否含浮动盈亏，按与“现金 + 已平仓 + 浮动”更接近的一种读法拆分余额和净值，浮动盈亏等于两者之差，不会重复计算。

Rithmic 没有成交配对记录，历史列表由两项服务器事实配对：括号子单的 `original_basket_id` 指向入场单，优先平掉自己的入场；其余按净额账户从最早的持仓平起。原因标为止损、止盈或平仓；止损触发后 `price_type` 变成市价，要看 `orig_price_type`。回放结果与服务器净持仓不一致时说明窗口起点落在更早的持仓中，该合约不显示配对，不猜。保证金率（306，TPT 为 0）和交易所权限（342，与交易路由一致）没有接入应用，由探针打印。

探针按顺序输出：系统列表（16）→ 四个 plant 的登录回复（含心跳间隔）→ 登录信息 300、账户 302、交易路由 310、RMS 304、挂单 320、括号 338/340、最近 7 天成交 3512 → 盈亏快照 402 → 主力合约 113、搜索 109（含不带交易所的搜索，以及其他交易所的 front month 回执）、参考数据 14 → 分钟 / 日线回放 202 → 实时 BBO、逐笔、K 线、盈亏推送。每行带 UTC 时间戳；K 线行额外打印 `marker` 的两种解读与 `marker-now`，用于确认 marker 是开盘还是收盘时间。订单、成交、持仓推送除原始字段外，还用 `parseOrderNotification` / `parseFill` / `parsePosition` 解析后一并打印，直接核对解析是否正确。

### 下单测试（仅 Rithmic Test 模拟账户）

在 `Rithmic Test` 上，探针默认下一笔测试单再撤掉，用于验证 312 下单回执、316 撤单回执与订单推送解析。不下市价单、不下括号单、不产生成交。**硬性限制**：只有系统名恰为 `Rithmic Test` 且网关为 `wss://rituz00100.rithmic.com:443` 时才执行，其他系统（含 Paper / Live）一律拒绝；设 `RITHMIC_PROBE_READONLY=1` 可关闭此测试。

- 下单：**买入 1 手 MES 限价单，价格 10**（远低于标普微型指数现价，只会挂单、不可能成交），打印 312 回执和订单推送（经 `parseOrderNotification` 解析），随后用 316 撤单并打印撤单推送。
- 价格固定为 10、方向固定为买、数量固定为 1；不随行情浮动，不读取 bid/ask。

```powershell
$env:RITHMIC_SYSTEM = 'Rithmic Test'; $env:RITHMIC_USER = '<开发包登录 ID>'; $env:RITHMIC_PASSWORD = Read-Host 'Rithmic 密码' -MaskInput
.\gradlew.bat :rithmic-broker:rithmicProbe -q --console=plain --no-daemon
# 只读、不下单：额外设 $env:RITHMIC_PROBE_READONLY = '1'
```

## 验证状态

- 离线：`rithmic-broker` 70 项测试，覆盖 wire 编码与 protoc 字节一致性、分帧、多段回复、拒绝 / 无数据 / 超时、登录拒绝、心跳、强制登出、断线重连、连接流程、合约规格、报价过期与清空、K 线形成、市价 / 括号 / 止损单请求字段、拒绝与未知结果、平仓对账、改价撤单、括号子单保护、入场单括号档位、连续合约主力与历史来源。`composeApp` 增加 Rithmic 凭据保存与恢复测试。
- 在线（不含凭据）：2026-11-05 对 `wss://rituz00100.rithmic.com:443` 与 `wss://rprotocol.rithmic.com:443` 的系统列表请求（模板 16/17）成功，分帧与字段编号在真实网关上成立；公开客户端里出现的 `ritpa11120.11.rithmic.com`（证书不含该主机名）与 `ritpz01000.01.rithmic.com`（连接超时）不可用。
- 协议依据：2026-11-05 取得官方 R | Protocol API 开发包 0.90.0.0（template version 5.55，194 个 proto，Reference Guide 0.49）。用脚本逐项核对：139 个字段编号全部与官方 proto 一致（其中 10 个常量名与官方字段名不同但编号相同），模板 id 与 Reference Guide 的表一致；回复终止判定（`rq_handler_rp_code` 表示还有后续，`rp_code` 表示结束）和登录失败判定（`rp_code` 两项：错误码与文本）与指南一致。开发包 0.90 把 4 字节数量字段标为弃用并新增 8 字节 `*_64` 字段（`quantity_64 = 159017`、`fill_size_64 = 159024`、`trade_size_64 = 159008` 等）：解析优先读 `_64`，下单同时发送两种字段，与官方示例（只发 `quantity_64`）兼容。`AccountPnLPositionUpdate` 金额字段在官方 proto 中为字符串，`InstrumentPnLPositionUpdate` 的 `day_pnl` 为 double、`open_position_pnl` 为字符串，与实现一致。括号档位修改的 `level` 取当前 tick 数，与参考客户端 `async_rithmic` 的做法相同。开发包受 Rithmic 许可协议约束，不随仓库分发。
- Rithmic Test 实测（2026-11-05，用户以开发包账号运行探针，两次）：四个 plant 登录成功（服务器 template_version 5.56，心跳 60 秒）；登录信息、账户、RMS（`max_order_quantity=40`、买卖限额 20、`status=active`）、21 条交易路由（globex/ice/simulator）、挂单、括号、成交历史（空，`rp_code` 7）、盈亏快照与各订阅均按预期返回；账户资金字段为十进制字符串，无值时为空字符串。
  - 参考数据（14，ESZ6）：`min_qprice_change=0.25`、`min_fprice_change=25.0`、`ftoq_price=0.01`、`qtof_price=100.0`、`single_point_value=50.0`。行情（BBO bid=7708.25）是报价（q）价，据此把 tick 改用 `min_qprice_change`，每 tick 价值 = 0.25 × 50 = 12.50 USD；原先误用 `min_fprice_change`（25.0）会得到 0 位小数、每 tick 1250 USD。`is_tradable` 字段此行未出现（`presence_bits` 未置位），解析按缺省通过。
  - K 线：分钟回放首根 `marker` = 窗口起点 + 60 秒 = 该分钟收盘时间，**确认 marker 为收盘时间**；日线 `marker` 为 `YYYYMMDD`（如 20261104），**确认**。实时逐笔、BBO、分钟 `time_bar`（250）均收到。
  - `front_month`（113）对 `ES@CME` 返回 7 no data；CONTAINS 搜索 `ES` 返回 927 行（名称含 FUTURES 的也命中），按 `product_code` 搜 `ES` 返回 22 行，回退取最近未到期合约得 `ESZ6`。`instrument_type` 同时出现 `Future` 与 `FUTURE`。`tp_max_session_count = 1`：同一登录只允许一个 ticker plant 会话，探针与应用不能同时连接。
- Rithmic Paper Trading 探针实测（2026-11-06，用户以 TPT 账号运行，只读）：四个 plant 登录成功，`app_name` 为 `UniTrading`，未做 conformance，fcm / ib 为 `TakeProfitTrader`。账户、RMS、交易路由（CME / CBOT / NYMEX / COMEX 均为 `simulator`）、`MES@CME` 的 front month（`MESZ6`，Test 上无此数据）、参考数据（`min_qprice_change=0.25`、`single_point_value=5.0`，每 tick 1.25 USD）、实时 BBO 与逐笔、分钟与日线回放、实时分钟 K 线均按预期返回。
- 应用首次登录 Paper Trading（2026-11-06，用户手动）：登录、账户和资金正常，`@MES` 显示“主力无法确认：permission denied”，因此没有行情和图表。探针随后查到 `MES` 同时挂在 `CME` 和 `CME-Delayed`，账户的交易路由没有后者，后者的 front month 返回 13 `permission denied`。当时的代码取搜索首行的交易所。这次探针里 `CME` 行排在前面，所以 `CME-Delayed` 排在首行这一点没有被直接复现；修复后结果不再依赖行序。同时修复了连续图表收不到实时逐笔和 K 线的问题（推送用到期合约代码，订阅键是 `@MES`），并把未确认主力的重试间隔从 6 小时改为 60 秒。
- `RithmicBroker` 只读流程实测（2026-11-06，用户运行探针，修复 3963497 之后，Paper Trading）：`[broker] RESULT OK`。`@MES` 解析到 `MESZ6`，有实时 Bid/Ask，加载 2000 根 1 分钟 K 线，最后一根随逐笔更新并按时换根，品种列表 6 项（5 个到期合约加 `@MES`）。用户随后在应用内确认 `@MES` 有行情和图表。
- 探针第三次实测（2026-11-06，Paper Trading）：产品风控返回 34 个产品，含手续费（ZW 等为每次成交 2.25）、买卖限额和单笔上限，保证金率为 0。交易所权限只有 CBOT、CME、COMEX、NYMEX，一级行情开启、二级关闭，与交易路由一致。产品代码（111）的 time-of-interest 列表解析出的品种不含 MNQ、MYM、M2K、RTY、MCL、MGC 等较新的合约，全量列表 CME 有 51765 行，不适合登录时读取。
- 用户在应用里下的一笔真实订单（2026-11-06，`MNQZ6`，订单标签 `UT-` 开头，是应用生成的）：市价卖出 1 手，模板 330 带括号，服务器接受，止损在 10 秒后成交，止盈被撤销，订单通知和成交解析正确。但应用报“交易结果未知”并暂停交易：真实回执把 `basket_id` 放在 `rq_handler_rp_code` 分段，结束分段没有，而代码只读结束分段；订单通知先到并结束等待后，未知状态也无法被同步清除。两处都已修复，假服务器改成两段式回执，尚未在真实下单上复测。成交历史的 `transaction_type` 取值为 `B` / `S`。`account_balance` 为 24930.50、`cash_on_hand` 为 25000.00，差额是已实现亏损 68.00 和手续费 1.50（每次成交 0.75）。
- 应用实测（2026-11-06，提交 b3b3e9d，窗口截图）：自动恢复保存的登录后直接进入主界面。余额和净值 24930.50、今日盈亏 -68.00 与服务器一致。`@MES`、`@NQ`、`@MNQ` 都有实时报价，`@MNQ` 图表正常，名称为服务器的 `Micro E-mini Nasdaq-100`，下单数量上限 30 张来自服务器风控。
- 回执修复后的第二笔真实订单（2026-11-06，`MNQZ6`，用户在应用内下单）：不再报结果未知，持仓和止损、止盈都正常显示。修改止盈时对话框提示“仓位已关闭或保护价已更新”：持仓的开仓时间取自每次盈亏推送，版本号随之不断变化，已修复，尚未在真实改单上复测。
- 用户截图确认（2026-11-06）：第二笔 `MNQZ6` 止盈后，历史列表显示为一条配对记录（26990.50 → 26943.50，止盈，净 +92.50），下单预估的双边手续费为 1.50，余额 25023.00 与两笔成交相符（手续费每次成交 0.75）。这笔持仓的止盈改到 26943.50 后成交，改价已在服务器生效。
- 带括号的限价入场单（`MNQZ6`，买入 26112.50）下单成功，但入场价和保护价都不可改：可改判断用状态白名单，服务器实际返回的状态不在名单内，具体文字未捕获。已改为只有改单、撤单在途才锁定，提示里显示服务器原始状态。修复后（提交 8701a64）用户确认挂单可以修改；之后的窗口截图显示挂单已清空，历史仍为 2 条，余额 25023.00 未变，说明测试单已撤销且没有成交。
- 连续历史探针（2026-11-06，用户运行，Paper Trading，只读）：`@MNQ` 图表此前只回放 `MNQZ6`，日线 198 根，从 2025-10-01 开始，早期成交量多为 1 手。裸产品代码能跨换月回放：`MNQ`、`MGC`、`MCL` 的 3 年日线窗口分别返回 754、752、748 根，都从 2023-11-07 开始，120 天 60 分钟窗口分别返回 1974、1976、1975 根。已到期月份（三个产品各往回 52 个月）全部返回 `rp_code 7 reference data not available`，仍在挂牌的月份正常。`MNQZ6` 的 30 天 1 分钟回放恰好返回 10000 根，范围是窗口最早的 10-07 到 10-16；加 `direction=LAST` 和 `user_max_count=3000` 返回最新 3000 根，时间仍为正序。这些请求绕过 `RithmicBroker`。
- 连续图表应用实测（2026-11-06，提交 6865199，用户确认效果，再核对本机缓存）：`@MNQ` 日线 1887 根，从 2019-05-07 开始，成交量低于 1000 手的 K 线为 0。别名不复权：首日收盘 7672.00；2026-09-14 及之前比 `MNQZ6` 低约 290 点，从 2026-09-15 起收盘价和成交量逐根等于 `MNQZ6`。换月处保留跳空，2024 年以来每季度约 +200～300 点，多在到期周周二或周三。Tradovate 的 `@MES` 同样不复权，换月多在到期周周一，两家的换月日不同。`@MES` 的 Rithmic 日线没有打开过，两家的逐日价格没有直接比对。
- **未验证**：平仓回执（探针的 MES@10 测试尚未运行）；添加品种列表里按 306 加载的产品；`account_balance` 是否含浮动盈亏；`is_tradable` 的字符串取值（没有数据）。

```bash
./gradlew :rithmic-broker:desktopTest
```
