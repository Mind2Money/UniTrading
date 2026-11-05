# Rithmic 接入与验证

> 文档类型：当前实现说明。首版接入日期 2026-11-05。协议实现只用离线假服务器验证，**尚未连接任何 Rithmic 系统**；真实账户、行情、下单链路均未实测。

## 接入方式

Rithmic 没有网页登录，应用通过 **R | Protocol API™**（WebSocket + Google Protocol Buffers）直接登录。在登录页点击“登录 Rithmic”，或在设置 / 账户切换中选择“连接 Rithmic”：

1. 选择环境：**Rithmic Test**（开发测试系统，网关 `wss://rituz00100.rithmic.com:443`，系统列表只有 `Rithmic Test`）、**Rithmic Paper Trading** 或 **Rithmic Live**。后两者预填芝加哥生产网关 `wss://rprotocol.rithmic.com:443`：2026-11-05 用系统列表请求确认可达，列出 29 个系统，包括 `Rithmic 01`、`Rithmic 04 Colo`、`Rithmic Paper Trading` 以及 `Apex`、`TopstepTrader`、`TheTradingPit`、`Bulenox`、`Earn2Trade` 等 prop firm 系统。Rithmic 提供区域网关（公开客户端中出现过 `rprotocol-ie` / `-de` / `-sg` 等主机）时可替换。
2. 网关地址只接受 `wss://<主机>.rithmic.com[:端口]`；其他主机被拒绝，凭据不会发往别处。
3. 点击“查询”向网关请求系统列表（模板 16/17，不含凭据），确认系统名称；登录时系统名称必须在该列表内。
4. 输入 Rithmic 登录 ID 与密码。勾选“在本机加密保存密码”后，凭据与 Tradovate 会话一同写入 DPAPI / Keystore 加密文件，下次启动自动恢复；不勾选则仅本次运行有效。
5. 登录成功后账户、行情和历史由服务器提供，连接后不会自动下单。

Rithmic 要求应用在接入生产系统（Rithmic 01、Paper Trading 及各 FCM 系统）前通过 conformance 审核，且 `app_name` 需要在 Rithmic 登记；R | Protocol API 不提供给带实时行情的纸面交易试用账户（见 [rithmic.com FAQ](https://www.rithmic.com/faq)）。开发者可在 [signup.rithmic.com/sdk](https://signup.rithmic.com/sdk/sdk.html) 申请 R | Protocol 开发包。本实现默认 `app_name = UniTrading`、`app_version = 1.1.4`、`template_version = 5.55`（官方开发包 0.90.0.0 示例所用）。

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
| 合约规格 | 14 | 仅 USD 期货；`min_fprice_change` 为 tick，`single_point_value` 为每点价值 |
| 搜索与主力 | 109、113 | `@产品代码` 连续合约映射到服务器返回的 front month |
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

## 只读联调探针

`rithmic-broker` 带一个只读探针，用真实系统核对上面的协议假设。它只做登录、列表、回放、订阅和登出，**不会发送任何下单、改单、撤单、括号档位或平仓请求**（模板 312–335、3504 不在代码路径中）。凭据只从环境变量读取，不出现在命令行、构建日志或输出中（输出里的密码会被替换为 `[redacted]`）。

```powershell
$env:RITHMIC_GATEWAY = 'wss://rprotocol.rithmic.com:443'   # 省略则为 Rithmic Test 网关
$env:RITHMIC_SYSTEM  = 'Rithmic Paper Trading'              # 省略则只打印该网关的系统列表
$env:RITHMIC_USER    = '<登录 ID>'
$env:RITHMIC_PASSWORD = '<密码>'
.\gradlew.bat :rithmic-broker:rithmicProbe -q --console=plain --no-daemon
```

可选：`RITHMIC_APP_NAME` / `RITHMIC_APP_VERSION`（默认 `UniTrading` / `1.1.4`）、`RITHMIC_PRODUCT` / `RITHMIC_EXCHANGE`（默认 `ES` / `CME`）、`RITHMIC_SYMBOL`（跳过主力查询）、`RITHMIC_ACCOUNT`（默认第一个账户）、`RITHMIC_PLANTS`（`order,pnl,ticker,history` 子集）、`RITHMIC_LISTEN_SECONDS`（实时订阅保持时间，默认 75 秒）。

探针按顺序输出：系统列表（16）→ 四个 plant 的登录回复（含心跳间隔）→ 登录信息 300、账户 302、交易路由 310、未签协议 500、RMS 304、挂单 320、括号 338/340、最近 7 天成交 3512 → 盈亏快照 402 → 主力合约 113、搜索 109、参考数据 14 → 分钟 / 日线回放 202 → 实时 BBO、逐笔、K 线、盈亏推送。每行带 UTC 时间戳；K 线行额外打印 `marker` 的两种解读与 `marker-now`，用于确认 marker 是开盘还是收盘时间。

## 验证状态

- 离线：`rithmic-broker` 40 项测试，覆盖 wire 编码与 protoc 字节一致性、分帧、多段回复、拒绝 / 无数据 / 超时、登录拒绝、心跳、强制登出、断线重连、连接流程、合约规格、报价过期与清空、K 线形成、市价 / 括号 / 止损单请求字段、拒绝与未知结果、平仓对账、改价撤单、括号子单保护、入场单括号档位、连续合约主力。`composeApp` 增加 Rithmic 凭据保存与恢复测试。
- 在线（不含凭据）：2026-11-05 对 `wss://rituz00100.rithmic.com:443` 与 `wss://rprotocol.rithmic.com:443` 的系统列表请求（模板 16/17）成功，分帧与字段编号在真实网关上成立；公开客户端里出现的 `ritpa11120.11.rithmic.com`（证书不含该主机名）与 `ritpz01000.01.rithmic.com`（连接超时）不可用。
- 协议依据：2026-11-05 取得官方 R | Protocol API 开发包 0.90.0.0（template version 5.55，194 个 proto，Reference Guide 0.49）。用脚本逐项核对：139 个字段编号全部与官方 proto 一致（其中 10 个常量名与官方字段名不同但编号相同），模板 id 与 Reference Guide 的表一致；回复终止判定（`rq_handler_rp_code` 表示还有后续，`rp_code` 表示结束）和登录失败判定（`rp_code` 两项：错误码与文本）与指南一致。开发包 0.90 把 4 字节数量字段标为弃用并新增 8 字节 `*_64` 字段（`quantity_64 = 159017`、`fill_size_64 = 159024`、`trade_size_64 = 159008` 等）：解析优先读 `_64`，下单同时发送两种字段，与官方示例（只发 `quantity_64`）兼容。`AccountPnLPositionUpdate` 金额字段在官方 proto 中为字符串，`InstrumentPnLPositionUpdate` 的 `day_pnl` 为 double、`open_position_pnl` 为字符串，与实现一致。括号档位修改的 `level` 取当前 tick 数，与参考客户端 `async_rithmic` 的做法相同。开发包受 Rithmic 许可协议约束，不随仓库分发。
- **未验证**：任何真实 Rithmic 系统的登录、行情、历史与下单；需在 Rithmic Test 上确认的语义：分钟 `marker` 为收盘时间、日线索引 `YYYYMMDD`、`is_tradable` 与成交历史 `transaction_type` 的字符串取值、生产系统是否接受未登记的 `app_name`。

```bash
./gradlew :rithmic-broker:desktopTest
```
