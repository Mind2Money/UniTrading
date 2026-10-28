# Tradovate 接入计划

日期：2026-10-28。状态：官方网页登录、受限令牌交接、Tradovate 插件及工作台适配已实现；73 项自动测试与 native host 40 项策略断言通过。双端官方登录页可打开；未使用真实账户登录或提交订单，账户联调未完成。

## Context

在现有 Windows Desktop / Android KMP 交易终端中增加 Tradovate 交易商插件，保留本地 Demo。用户提供的参考为 `PROPFIRM-copytrading-10.0.4-src (1).zip`，应复用其已观察到的协议事实，以独立 Kotlin 实现接入，不执行或直接集成浏览器注入脚本。

本次边界是交易终端接入，不增加跨账户自动跟单，不自动下测试单。按用户授权，只从本应用弹出的官方 Tradovate 登录窗口接收本次会话信息，不扫描其他浏览器的 profile、cookie 或密码。用户在官方网页内输入凭据，不要求粘贴到对话中。

## 1. 参考检查结果

- 压缩包 SHA-256：`4d9c3ef977e9bf24347366a1d0960db080f97f165f145d2a0bb41e2b30159b37`。
- 39 个条目，解压后约 1.43 MB；路径检查未发现目录穿越。检查直接读取 ZIP 成员，未执行其中的程序。
- `manifest.json` 表明该参考是 Manifest V3 Chrome 扩展，并非独立 API 文档。
- `page/ws-account.js:12–30`：SockJS 风格 WebSocket，`o` 开启、`a[...]` 消息、`h` 心跳；发送 `authorize\n<id>\n\n<token>`、`user/syncrequest`，接收实体快照及 `props` 更新。
- `page/ws-account.js:208–337`：净额仓位来自 `netPos/netPrice/contractId`；挂单依赖 `order + orderVersion`；成交来自 fill。现金、净值和浮盈亏不可照抄扩展中的占位值。
- `page/copy-trader.js:33–55`：当前版本明确关闭原生 WS 写入分支，实际走 TradingView broker REST；不能仅按旧版本注释认定写入路径。
- `page/copy-trader.js:1028–1050,1139–1182`：原生订单参考包含 `orderQty`、Limit 的 `price`、Stop 的 `stopPrice`，以及 cancel/modify/liquidate；括号单不能把修改保护价误当作普通改单。
- `page/tick-bridge.js:948–978,1080–1088,1239–1255`：账户与行情端点分开；行情 token 有独立授权限制，订阅使用实际合约 ID。缺行情权限不能生成替代行情冒充成功。
- `background/service-worker.js:410–449`：存在网页客户端专用 appId/cid 与签名参数；它们不是本项目已获分配的 API 凭据，不直接照搬为独立应用的默认登录方案。
- 包内未发现 LICENSE/README。计划仅将协议交互作为参考，独立实现，不复制第三方授权/支付、遥测、页面注入或多标签页修改逻辑。
- 官方站点首页仅包含 JS 引导壳；随后以静态方式解析其内嵌 OpenAPI，补核订单、元数据和行情字段，未执行下载的文档 JS。

## 2. 已确定的登录方案

- 用户要求：客户端弹出 Tradovate 网页，用户登录后自动获取会话信息，同时支持记住账号密码和自动填充。
- 官方网页自己执行签名/登录；应用从成功响应及本窗口 WS 授权消息接收 `accessToken` / `mdAccessToken`，无需把网页客户端签名密钥硬编码进 Kotlin。
- Windows 使用 WebView2 独立原生登录窗（系统 WebView2 Runtime + .NET Framework WinForms 小型 host）。开启密码保存/自动填充；由浏览器提示用户保存，profile 按环境独立。
- Android 使用 WebView + AndroidX WebKit 文档起始脚本和限定 origin 的消息监听。保存/自动填充由用户选择的 Android Autofill 服务处理；无服务时明确提示配置，不声称 WebView 自带密码库。
- 登录桥只接收官方 HTTPS 主文档，白名单核对响应/WS 地址与环境；不读取密码字段、不给不可信页面开放本机方法。
- 令牌通过进程管道（Desktop）或 origin-scoped WebMessage（Android）交接，只存内存，不经命令行、日志、剪贴板或 Demo JSON。
- 保留官方登录流程中的验证码/MFA，失败显示真实原因；不绕过授权、不切换第三方中转站。

Demo/Live 显式选择，默认 Demo；获取会话后自动连接只同步数据，不自动提交交易。用户点击确认的订单由原生 Tradovate API 执行；参考扩展选择 TV 代理的理由是跨账户跟单路径一致性，本项目不做自动跟单。

协议依据：已静态解析官方站点 JS 内嵌 OpenAPI，确认 `product.valuePerPoint/tickSize`、`contractMaturity`、`order/placeoso` 服务端 OCO、`order/liquidateposition` 撤单并平仓、`md/getChart` 历史/实时订阅。参考：
- https://api.tradovate.com/
- https://github.com/tradovate/example-api-faq
- https://learn.microsoft.com/en-us/dotnet/api/microsoft.web.webview2.core.corewebview2settings.ispasswordautosaveenabled
- https://developer.android.com/identity/autofill/autofill-optimize

## 3. 预定模块划分

```text
composeApp → broker-api ← tradovate-broker
           → demo-broker（保留）
tradovate-broker → 可替换 HTTP / WebSocket transport
```

- 新增 `tradovate-broker` KMP 模块：认证/连接、协议帧、REST/WS 客户端、状态归并、合约解析、行情/历史、订单适配。
- 使用共享 Ktor 网络层，Desktop/Android 使用适配引擎；测试通过 mock transport，不需要真实账户。
- 端点按环境固定白名单，不接受任意 URL + token 组合；日志和异常清除密码、token、授权头。
- 用户选择记住密码时，由 WebView2 / Android 系统密码服务保存；应用自身只在内存中保留会话 token，不将敏感字段写入现有 JSON Demo 快照。
- 生命周期包含连接中、已连接、认证失效、行情未授权、数据过期、断线/重连、数据重新同步；退出时关闭连接和取消订阅。

## 4. 共享契约需要补充

已有可复用接口：`broker-api/.../BrokerPlugin.kt`、`Models.kt`、`FixedPoint.kt`；不改变本地 Demo 的现有撮合规则。

- 交易商信息/账户/品种目录需要支持异步更新；空目录不能在 `TradingScreen` 内直接 `first()`。
- 环境和能力：区分本地模拟、远程 Demo、Live，支持净额账户以及保护价/订单修改的能力限制。
- 数量：支持期货整数张数；沿用整数内部精度时以 100 表示 1 张、步长 100，界面根据合约单位显示整数，不能向服务器发送 0.01 张。
- 价格：tickSize 和每 tick 合约价值来自服务器合约/产品元数据；净持仓均价可能不落在最小报价 tick 上，不能强制按可下单报价校验均价。
- 资金与预估：服务端缺失的保证金、费用或净值标为未知/不可估，不能把 Demo 杠杆计算或 0 值当作权威信息。仅在合约价值/币种可确认时给出预计毛盈亏，并标明费用是否包含。
- 持仓：按账户 + 合约映射净持仓，方向反转/部分成交需合并服务端事实；不制造独立对冲仓位。
- 历史：支持原始成交标记与列表；无法可靠配对的开/平仓、费用、已实现盈亏不能捏造为完整 ClosedTrade。
- 命令结果：区分拒绝、受理/等待同步、结果未知。超时后的订单不能自动重发；通过关联 ID 与后续同步对账。
- 版本：本地递增版本只能防止陈旧 UI 提交，不宣称服务端支持 CAS。修改前再次核对远端订单状态，最终以回执/推送为准。

## 5. 工作台接线

主要修改：`TradingRuntime.kt`、`TradingScreen.kt`、`TradingPanels.kt`、`TradingChart.kt` 和平台入口。

- 增加 Tradovate 连接配置、环境选择、登录/断开和明确连接状态。
- 交易商注册支持动态连接后的目录更新，账户切换清除旧草稿、历史选择和编辑框。
- 去掉对所有插件一律显示“本地模拟/模拟行情/无真实资金”的硬编码，Demo 时钟和重置按钮只作用于本地 Demo。
- 行情与 K 线仅显示插件的数据；无行情权限/历史不足时显示原因。
- 市价/Limit/Stop、撤单、平仓、挂单改价、SL/TP 继续使用统一工作流。保护价须关联服务端保护订单；无法确认 OCO/括号关系时禁用相应动作并说明，不用两个独立反向订单冒充安全保护。
- 下单确认展示交易商、环境、账号、实际合约月份、方向、整数张数、价格及估算限制。
- Android 增加网络权限；Desktop/Android 复用领域和协议逻辑，iOS 仍不作为本机验证结果。

## 6. 实施顺序

1. 确定用户可用鉴权/交易链路，补全端点和协议证据，更新本计划。
2. 扩展共享模型及状态，确保现有 52 项测试继续通过；补测净额/整数合约/未知资金/结果未知。
3. 实现并离线测试认证、WS framing、快照+增量、合约元数据、行情与历史映射。
4. 实现订单提交/修改/撤销/平仓与保护关系校验，不自动重发结果未知的写请求。
5. 接入配置和交易工作台，完成桌面 UI 与 Android 布局/构建验证。
6. 更新插件文档、验收记录并按用户要求本地 commit；不擅自推送。

## 7. 验证门槛

- Mock transport：鉴权失败/过期、心跳、重连、先增量后快照、重复事件、乱序订单版本、断线与行情授权缺失。
- 财务映射：整数合约、不同 tickSize、平均价精度、净额反转、部分成交、不完整费用/资金字段不误报为 0。
- 写入安全：超时不自动重复下单，重连不自动重放命令；挂单已成交时拒绝修改；净额平仓不转换为未经核对的反向加仓。
- 回归：本地 Demo 原有交易/恢复/图表拖动行为不变。
- Desktop tests + Android assembleDebug；真连接、实时报价、服务器订单只在有有效授权并由用户明确操作时验证。
- 交付准确区分“代码/协议测试通过”和“实账户联调通过”，不使用离线 mock 冒充 Tradovate 真实接入成功。
