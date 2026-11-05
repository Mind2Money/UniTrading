# Rithmic 接入与验证

> 文档类型：当前实现说明。首版接入日期 2026-11-05。探针已在 Rithmic Test 与 Rithmic Paper Trading（TPT 账号）实测登录、账户、RMS、行情、历史 K 线；这些是绕过 `RithmicBroker` 的逐请求检查。应用在 Paper Trading 上能登录并显示账户，行情、图表和连续合约流程仍在联调。下单 / 撤单回执只在离线假服务器上验证，探针的下单测试尚未运行。
>
> 应用内入口：已开启（`TradingScreen.kt` 的 `RITHMIC_LOGIN_ENABLED = true`，2026-11-06）。登录页、设置、账户切换和空合约页均可进入 Rithmic 登录。注意：Rithmic 对生产系统（含 Paper Trading）要求先通过 conformance，公开分发前请自行确认合规。

## 接入方式

Rithmic 没有网页登录，应用通过 **R | Protocol API™**（WebSocket + Google Protocol Buffers）直接登录。在登录页点击“登录 Rithmic”，或在设置 / 账户切换中选择“连接 Rithmic”：

1. 选择环境：**Rithmic Test**（开发测试系统，网关 `wss://rituz00100.rithmic.com:443`，系统列表只有 `Rithmic Test`）、**Rithmic Paper Trading** 或 **Rithmic Live**。后两者预填芝加哥生产网关 `wss://rprotocol.rithmic.com:443`：2026-11-05 用系统列表请求确认可达，列出 29 个系统，包括 `Rithmic 01`、`Rithmic 04 Colo`、`Rithmic Paper Trading` 以及 `Apex`、`TopstepTrader`、`TheTradingPit`、`Bulenox`、`Earn2Trade` 等 prop firm 系统。Rithmic 提供区域网关（公开客户端中出现过 `rprotocol-ie` / `-de` / `-sg` 等主机）时可替换。
2. 网关地址只接受 `wss://<主机>.rithmic.com[:端口]`；其他主机被拒绝，凭据不会发往别处。
3. 点击“查询”向网关请求系统列表（模板 16/17，不含凭据），确认系统名称；登录时系统名称必须在该列表内。
4. 输入 Rithmic 登录 ID 与密码。勾选“在本机加密保存密码”后，凭据与 Tradovate 会话一同写入 DPAPI / Keystore 加密文件，下次启动自动恢复；不勾选则仅本次运行有效。
5. 登录成功后账户、行情和历史由服务器提供，连接后不会自动下单。

Rithmic 要求应用在接入生产系统（Rithmic 01、Paper Trading 及各 FCM 系统）前通过 conformance 审核，且 `app_name` 需要在 Rithmic 登记；R | Protocol API 不提供给带实时行情的纸面交易试用账户（见 [rithmic.com FAQ](https://www.rithmic.com/faq)）。开发者可在 [signup.rithmic.com/sdk](https://signup.rithmic.com/sdk/sdk.html) 申请 R | Protocol 开发包。本实现默认 `app_name = UniTrading`、`app_version = 1.2.0`、`template_version = 5.55`（官方开发包 0.90.0.0 示例所用）。

## 协议实现

模块 `rithmic-broker/`，不依赖 protobuf 代码生成：

| 文件 | 职责 |
| --- | --- |
| `Protobuf.kt` | 手写 proto3 wire 编解码（varint / fixed64 / length-delimited）与 4 字节大端长度帧 |
| `RithmicProtocol.kt` | 字段编号（MNM id + 100000 偏移）、模板 id、枚举常量、返回码判定 |
| `RithmicTransport.kt` | Ktor WebSocket 二进制帧传输，网关白名单 |
| `RithmicPlant.kt` | 单个 plant 连接：`user_msg` 关联、多段回复聚合、`rp_code` 终止判定、登录、心跳、强制登出 |
| `RithmicSession.kt` | 环境、系统名称、凭据模型与 broker id |
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
| 搜索与主力 | 109、113 | 搜索先按 `product_code` 过滤，无结果再用 CONTAINS；`@产品代码` 连续合约映射到服务器返回的 front month，服务器无数据（Rithmic Test 即如此）时取该产品最近未到期合约。按产品搜索不带交易所，结果可能跨交易所：优先用账户有交易路由的交易所，被拒绝时换下一个，错误信息逐个写明交易所。Paper Trading 列出的裸产品行（如 `MES`，名称为 `Front Month for ...`）是 front month 别名，不是合约，不进入品种列表 |
| 行情 | 100，150/151 | BBO 作为 Bid/Ask，逐笔推进正在形成的 K 线；`clear_bits` 清空一侧时撤下报价 |
| K 线 | 202、200/250 | 分钟 / 日线回放与订阅；分钟 `marker` 为收盘时间，日线用 `YYYYMMDD` |
| 下单 | 312 / 330 | 市价、Limit、Stop Market；带 SL/TP 时用静态括号单（tick 距离） |
| 改价、撤单、平仓 | 314、316、3504 | 仓位保护价修改作用于对应括号子单 |
| 未成交入场单的括号 | 338/340、332/334 | 以 tick 档位显示和修改，每次一条 |

## 交易边界

- 数量为正整数张，价格按 tick 整数表示；预估只给毛盈亏，手续费与保证金标为未知。
- 括号保护只能通过新订单附带；已有净仓不能叠加括号，已有保护只能修改不能增删。
- 市价单附带 SL/TP 时，Rithmic 按实际成交价加减相同 tick 数生成保护线，实际价位可能与输入不同。
- 同一合约存在多个括号子单时仓位保护进入只读，不猜测关联。
- 受理不等于成交。请求超时、连接断开或缺少回执时结果标记为未知并暂停后续写入，等待订单 / 仓位推送或手动同步确认；不会自动重发。
- 任一 plant 断开后自动重连最多三次，重新订阅行情与 K 线，不重放交易指令；服务器强制登出（并发会话超限）同样触发。
- 日线只接受服务器 K 线，不用逐笔推进；分钟线回放一次最多 10,000 根，应用请求约四倍图表宽度的时间窗。

## 联调探针

`rithmic-broker` 带一个探针，用真实系统核对上面的协议假设。它做登录、列表、回放、订阅；在 `Rithmic Test` 模拟系统上默认再跑一次下单测试（见下）。设 `RITHMIC_PROBE_READONLY=1` 则只读、不发送任何下单类请求。凭据只从环境变量读取，不出现在命令行、构建日志或输出中（输出里的密码会被替换为 `[redacted]`）。

```powershell
$env:RITHMIC_GATEWAY = 'wss://rprotocol.rithmic.com:443'   # 省略则为 Rithmic Test 网关
$env:RITHMIC_SYSTEM  = 'Rithmic Paper Trading'              # 省略则只打印该网关的系统列表
$env:RITHMIC_USER    = '<登录 ID>'
$env:RITHMIC_PASSWORD = '<密码>'
.\gradlew.bat :rithmic-broker:rithmicProbe -q --console=plain --no-daemon
```

可选：`RITHMIC_APP_NAME` / `RITHMIC_APP_VERSION`（默认 `UniTrading` / `1.2.0`）、`RITHMIC_PRODUCT` / `RITHMIC_EXCHANGE`（默认 `MES` / `CME`）、`RITHMIC_SYMBOL`（跳过主力查询）、`RITHMIC_ACCOUNT`（默认第一个账户）、`RITHMIC_PLANTS`（`order,pnl,ticker,history` 子集）、`RITHMIC_LISTEN_SECONDS`（实时订阅保持时间，默认 75 秒）、`RITHMIC_PROBE_READONLY=1`（跳过下单测试，只读）。

逐请求检查绕过 `RithmicBroker`，不能证明应用流程可用。所以探针最后会退出这些连接，再用 `RithmicBroker`（应用实际使用的适配器）跑约 45 秒：连接、解析 `@<产品>` 连续合约、订阅行情、加载并跟踪 1 分钟图。这一段在任何系统上都只读，不调用下单接口；应用是否可用以 `[broker] RESULT` 这一行为准。可把 `RITHMIC_LISTEN_SECONDS` 设小以缩短前面的监听时间。

探针按顺序输出：系统列表（16）→ 四个 plant 的登录回复（含心跳间隔）→ 登录信息 300、账户 302、交易路由 310、RMS 304、挂单 320、括号 338/340、最近 7 天成交 3512 → 盈亏快照 402 → 主力合约 113、搜索 109（含不带交易所的搜索，以及其他交易所的 front month 回执）、参考数据 14 → 分钟 / 日线回放 202 → 实时 BBO、逐笔、K 线、盈亏推送。每行带 UTC 时间戳；K 线行额外打印 `marker` 的两种解读与 `marker-now`，用于确认 marker 是开盘还是收盘时间。订单、成交、持仓推送除原始字段外，还用 `parseOrderNotification` / `parseFill` / `parsePosition` 解析后一并打印，直接核对解析是否正确。

### 下单测试（仅 Rithmic Test 模拟账户）

在 `Rithmic Test` 上，探针默认下一笔测试单再撤掉，用于验证 312 下单回执、316 撤单回执与订单推送解析。不下市价单、不下括号单、不产生成交。**硬性限制**：只有系统名恰为 `Rithmic Test` 且网关为 `wss://rituz00100.rithmic.com:443` 时才执行，其他系统（含 Paper / Live）一律拒绝；设 `RITHMIC_PROBE_READONLY=1` 可关闭此测试。

- 下单：**买入 1 手 MES 限价单，价格 10**（远低于标普微型指数现价，只会挂单、不可能成交），打印 312 回执和订单推送（经 `parseOrderNotification` 解析），随后用 316 撤单并打印撤单推送。
- 价格固定为 10、方向固定为买、数量固定为 1；不随行情浮动，不读取 bid/ask。

```powershell
$env:RITHMIC_SYSTEM = 'Rithmic Test'; $env:RITHMIC_USER = '<开发包登录 ID>'; $env:RITHMIC_PASSWORD = '<密码>'
.\gradlew.bat :rithmic-broker:rithmicProbe -q --console=plain --no-daemon
# 只读、不下单：额外设 $env:RITHMIC_PROBE_READONLY = '1'
```

## 验证状态

- 离线：`rithmic-broker` 47 项测试，覆盖 wire 编码与 protoc 字节一致性、分帧、多段回复、拒绝 / 无数据 / 超时、登录拒绝、心跳、强制登出、断线重连、连接流程、合约规格、报价过期与清空、K 线形成、市价 / 括号 / 止损单请求字段、拒绝与未知结果、平仓对账、改价撤单、括号子单保护、入场单括号档位、连续合约主力。`composeApp` 增加 Rithmic 凭据保存与恢复测试。
- 在线（不含凭据）：2026-11-05 对 `wss://rituz00100.rithmic.com:443` 与 `wss://rprotocol.rithmic.com:443` 的系统列表请求（模板 16/17）成功，分帧与字段编号在真实网关上成立；公开客户端里出现的 `ritpa11120.11.rithmic.com`（证书不含该主机名）与 `ritpz01000.01.rithmic.com`（连接超时）不可用。
- 协议依据：2026-11-05 取得官方 R | Protocol API 开发包 0.90.0.0（template version 5.55，194 个 proto，Reference Guide 0.49）。用脚本逐项核对：139 个字段编号全部与官方 proto 一致（其中 10 个常量名与官方字段名不同但编号相同），模板 id 与 Reference Guide 的表一致；回复终止判定（`rq_handler_rp_code` 表示还有后续，`rp_code` 表示结束）和登录失败判定（`rp_code` 两项：错误码与文本）与指南一致。开发包 0.90 把 4 字节数量字段标为弃用并新增 8 字节 `*_64` 字段（`quantity_64 = 159017`、`fill_size_64 = 159024`、`trade_size_64 = 159008` 等）：解析优先读 `_64`，下单同时发送两种字段，与官方示例（只发 `quantity_64`）兼容。`AccountPnLPositionUpdate` 金额字段在官方 proto 中为字符串，`InstrumentPnLPositionUpdate` 的 `day_pnl` 为 double、`open_position_pnl` 为字符串，与实现一致。括号档位修改的 `level` 取当前 tick 数，与参考客户端 `async_rithmic` 的做法相同。开发包受 Rithmic 许可协议约束，不随仓库分发。
- Rithmic Test 实测（2026-11-05，用户以开发包账号运行探针，两次）：四个 plant 登录成功（服务器 template_version 5.56，心跳 60 秒）；登录信息、账户、RMS（`max_order_quantity=40`、买卖限额 20、`status=active`）、21 条交易路由（globex/ice/simulator）、挂单、括号、成交历史（空，`rp_code` 7）、盈亏快照与各订阅均按预期返回；账户资金字段为十进制字符串，无值时为空字符串。
  - 参考数据（14，ESZ6）：`min_qprice_change=0.25`、`min_fprice_change=25.0`、`ftoq_price=0.01`、`qtof_price=100.0`、`single_point_value=50.0`。行情（BBO bid=7708.25）是报价（q）价，据此把 tick 改用 `min_qprice_change`，每 tick 价值 = 0.25 × 50 = 12.50 USD；原先误用 `min_fprice_change`（25.0）会得到 0 位小数、每 tick 1250 USD。`is_tradable` 字段此行未出现（`presence_bits` 未置位），解析按缺省通过。
  - K 线：分钟回放首根 `marker` = 窗口起点 + 60 秒 = 该分钟收盘时间，**确认 marker 为收盘时间**；日线 `marker` 为 `YYYYMMDD`（如 20261104），**确认**。实时逐笔、BBO、分钟 `time_bar`（250）均收到。
  - `front_month`（113）对 `ES@CME` 返回 7 no data；CONTAINS 搜索 `ES` 返回 927 行（名称含 FUTURES 的也命中），按 `product_code` 搜 `ES` 返回 22 行，回退取最近未到期合约得 `ESZ6`。`instrument_type` 同时出现 `Future` 与 `FUTURE`。`tp_max_session_count = 1`：同一登录只允许一个 ticker plant 会话，探针与应用不能同时连接。
- Rithmic Paper Trading 探针实测（2026-11-06，用户以 TPT 账号运行，只读）：四个 plant 登录成功，`app_name` 为 `UniTrading`，未做 conformance，fcm / ib 为 `TakeProfitTrader`。账户、RMS、交易路由（CME / CBOT / NYMEX / COMEX 均为 `simulator`）、`MES@CME` 的 front month（`MESZ6`，Test 上无此数据）、参考数据（`min_qprice_change=0.25`、`single_point_value=5.0`，每 tick 1.25 USD）、实时 BBO 与逐笔、分钟与日线回放、实时分钟 K 线均按预期返回。
- 应用首次登录 Paper Trading（2026-11-06，用户手动）：登录、账户和资金正常，`@MES` 显示“主力无法确认：permission denied”，因此没有行情和图表。已知差异是应用按产品搜索时不带交易所并取第一行的交易所，探针固定 `CME`；是否为此原因待探针的不带交易所搜索结果确认。同时修复了连续图表收不到实时逐笔和 K 线的问题（推送用到期合约代码，订阅键是 `@MES`），并把未确认主力的重试间隔从 6 小时改为 60 秒。修复只经离线测试验证，尚未在 Paper Trading 上复测。
- **未验证**：应用在 Paper Trading 上的连续合约、行情与图表；任何系统上的真实下单 / 改单 / 撤单回执与成交推送（探针的 MES@10 测试尚未运行）；成交历史 `transaction_type` 与 `is_tradable` 的字符串取值（均无数据）。

```bash
./gradlew :rithmic-broker:desktopTest
```
