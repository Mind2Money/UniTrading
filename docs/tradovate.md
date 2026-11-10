# Tradovate 使用与验证

## 接入方式

当前应用 1.4.0 默认不注册本地模拟券商。首次启动点击“登录 Tradovate”；已有连接时从“账户 / 切换账户”选择“连接 Tradovate”，选 Demo 或 Live，再打开官方登录页。完整功能与验证矩阵见[当前状态](current-status.md)。

- 页面为 `https://trader.tradovate.com/welcome`，由官方网页执行签名与登录，MFA/验证码保持原流程。
- 只读取本应用弹出的登录窗口返回的 `accessToken`、`mdAccessToken` 等会话字段；不扫描 Chrome/Edge 的用户资料，不从密码输入框读取账号密码。
- 认证与续期使用共享 `live.tradovateapi.com`；账户和行情仍连接用户选择的 Demo/Live 环境。认证域名不代表交易环境。
- 部分机构的环境由 Tradovate 迁到了独立主机，登录回复用未公开的 `apiHosts` 字段指明（Tradovate 网页端也按它切换）。例如 Lucid 的 Demo 在 `demo-api.lucid.ninjatrader.com` 和 `md-demo-api.lucid.ninjatrader.com`，默认 Demo 主机对这些用户的请求返回 HTTP 307。登录按回复选择该环境的交易主机（`demo`/`live`：REST 和账户流）和行情主机（`mdDemo`/`mdLive`），缺项或为空时用默认主机，主机随登录加密保存。规则见 [`TradovateHosts.kt`](../tradovate-broker/src/commonMain/kotlin/com/unitrading/tradovate/TradovateHosts.kt)：只接受 `*.tradovateapi.com` 和 `*.ninjatrader.com` 的小写纯主机名；其他值或另一环境的交易主机会拒绝登录并说明字段名，不回退到默认主机。
- HTTP 跳转一律不跟随，主机不取自跳转目标。旧版本保存的登录没有主机信息，读取账户列表收到跳转时，只采用认证主机续期回复里的 `apiHosts` 并重试一次；回复没有给出主机时提示重新登录。运行中的会话不换主机，续期回复里的不同主机到下次连接才生效。
- 取得有效账户会话后自动返回应用，无须等待额外的 `mdAccessToken` 或点击“完成登录”。行情使用账户访问令牌连接对应 md 网关，是否有权限以服务器响应为准。
- 仅从 Tradovate 默认主机或登录回复指定且通过校验的主机（HTTPS/WSS）读取数据和提交用户确认的命令，不经过压缩包内第三方服务。
- Native API 权限仍由 Tradovate 决定。官方网页登录成功不保证该账户可使用 API、实时行情或某种订单。

## 记住密码和自动填充

### Windows Desktop

使用 WebView2 自有密码管理功能，开启 `IsPasswordAutosaveEnabled` 和 `IsGeneralAutofillEnabled`。是否保存由浏览器提示和用户选择决定；应用不复制密码到自己的数据库。

独立 profile 位于 `%LOCALAPPDATA%/UniTrading/browser/demo` 与 `.../live`。该目录不是用户已有的 Edge/Chrome profile。首次登录保留最多 60 秒供浏览器保存密码，并显示倒计时；只观察密码库文件的大小/修改时间，不读取内容。保存完成、已记住的登录或续期会话在短暂等待后自动返回，也可点击“立即返回”。明确点击“取消登录”仍会放弃本次交接。

需要系统 Microsoft Edge WebView2 Runtime 和 Windows .NET Framework 4.x。`:composeApp:run` 会自动构建/准备 native host；无需 .NET SDK。

### macOS

登录窗口是独立的 Swift + WKWebView 程序（`native-auth/TradovateLogin.swift`，Apple Silicon，macOS 13 及以上），与 Windows host 用同一套管道协议。Demo 和 Live 各用一份网站数据（macOS 14 起；13 上两者共用）。WKWebView 没有密码管理器，账号密码每次手动输入或粘贴；收到会话后约 1.5 秒自动返回。

### Android

使用 Android WebView 的独立 Demo/Live profile 与系统 Autofill 框架。收到会话后自动提交保存上下文、等待系统提示处理并返回主界面。**自动保存/填充依赖用户启用一个系统密码服务**；WebView 不自带独立密码管理器。未启用时登录窗会显示说明和系统设置入口。

标准版要求 System WebView 支持文档起始脚本和受限 WebMessage。缺少时登录窗写明缺哪一项和 WebView 的包名、版本，不降级成对所有网页暴露 JavaScriptInterface 的方案。多 profile 是可选的：不支持时 Demo 和 Live 共用网站登录状态（与 macOS 13 相同），登录窗会显示说明。

内置浏览器版（文件名以 `-webview.apk` 结尾）用 APK 自带的 GeckoView 代替 System WebView，给系统 WebView 不满足要求的设备使用，例如鸿蒙。只支持 arm64-v8a。Demo 和 Live 由各自的 contextId 隔离。登录脚本与其他平台相同，由内置扩展在页面脚本之前注入，经 `nativeMessagingFromContent` 把消息交回应用，应用再校验来源和主框架。保存和填充密码同样走系统 Autofill。

2026-11-10 在 API 36 x86_64 模拟器上验证：GeckoView 159 打开真实登录页，语言下拉和键盘输入可用；用只在本机打的临时补丁让真实页面收到模拟的登录回复，Demo、Live、Demo 依次交回对应环境的会话。强制关闭多 profile 后标准版能打开登录页并显示说明。未验证：真实账号登录、MFA 和验证码、鸿蒙真机、GeckoView 下系统密码服务的保存和填充。

同日抓包只看连接目标、未解密内容：内置浏览器首次打开登录页时，GeckoView 自身额外连接 Mozilla 的 `firefox.settings.services.mozilla.com`、`firefox-settings-attachments.cdn.mozilla.net`、`content-signature-2.cdn.mozilla.net`（配置下载）和 `location.services.mozilla.com`（按 IP 判断地区）。页面引用的分析脚本（如 LaunchDarkly、Heap）由 Tradovate 的网页决定，使用系统 WebView 时同样会加载。

## 交易能力与限制

- 服务器提供账户、实际到期合约、产品 tickSize/valuePerPoint、Bid/Ask、历史和实时 K 线。
- 当前支持 USD 线性期货，数量为正整数张；均价保留六位十进制，允许均价不在报价 tick 上。
- 数量滑块上限通过 `order/dryrun` 只读预校验查找：同时受服务器当前可用资金、保证金、持仓/挂单占用及账户风控限制约束，按账户、合约、方向和订单价格分别计算，不再固定为 1000 张。查询会验证上限及下一张的边界，不发送真实订单。显示采用先缓存后核对：按账户、合约、方向保存上次结果（本机 `order-capacity-v1`），立刻显示，后台再查服务器，只有服务器返回不同的上限才更新，查询失败不改动，也不再按时间过期。切换方向、订单类型、价格或合约，以及持仓和挂单变化时核对一次，余额更新不触发；已知上限时只校验上限和下一张两次，变化了才完整查找。没有缓存时显示“正在确认可下单数量”，查询失败可点击“刷新”；服务器没有给出正数上限（查询中、不可用或为 0）时，加减按钮只受合约最大数量限制，提交仍按服务器校验为准。查询期间只有该账户的持仓、挂单或现金余额变化才作废结果，合约资料和历史成交的加载不影响。滑动或手动编辑数量不触发查询，不自动改写超限的输入；已知超限时不能进入确认。预校验不保证稍后的订单获准，最终以服务器下单校验为准。
- 数量查询遵守 `dryrun` 至少 1 秒的调用间隔，本连接保留每小时 450 次的预算；达到预算或服务器拒绝访问时显示未知。真实服务器可能省略成功响应的 `rejectReason`：此时必须存在完整、有效的风险计算结果才接受；空响应、缺字段或显式拒绝不会视为成功。
- 数量预校验使用服务器的风控结果，不依赖本地 Bid/Ask 在 15 秒内更新；休市或行情安静时仍可查询。实际交易预览和交易指令仍要求有效实时报价。
- Windows 可执行 `./gradlew :composeApp:tradovateReadOnlyCapacityProbe -PconfirmTradovateReadOnly=true`，用本机加密保存且当前选中的 Demo 会话验证数量上限和触摸拖动。此诊断只允许读取、行情订阅和 `order/dryrun`，硬性拦截下单、改单、撤单和平仓，比较诊断前后的服务器订单记录；日志仅输出汇总，不输出令牌或账户名称。该任务不会在普通测试中自动运行。
- 净额仓位按账户 + 合约显示，外部增减仓/反向成交以服务器推送更新。
- 原始成交按服务器 fill 显示。无法可靠配对或缺少费用数据时，已实现净盈亏保持未知。
- 远程暂未生成配对 `ClosedTrade`，没有点击原始成交定位图表的流程；历史图表每品种/周期请求并保留最近 2000 根，未实现更早历史分页。
- 支持 Market / Limit / Stop、撤单、服务器 liquidation、挂单入场价修改。
- 带保护价的新订单使用一个 `order/placeoso` 请求；双保护由服务器关联为 OCO。已有该合约净仓时不叠加新括号单，避免保护数量失配。
- 保护单识别支持原生父子关系、无父订单的 OCO 以及单层 ATM 的 `orderStrategy/orderStrategyLink`。只有同账户、同合约、方向/剩余数量及关联证据一致时才开放保护价修改；不靠价格高低猜测 SL/TP。
- 挂单表与图表区分入场、SL 止损、TP 止盈和关联待确认。保护单不走入场改价路径；不完整、多组或冲突的关联不能按仓位修改保护价。每次只改一条保护价，增删或复杂保护结构需在官网处理。
- 无法配对的退出单可以单独修改（2026-11-10 起）：关联待确认的单，以及因为关联不完整而锁住仓位保护的止损、止盈，只要状态是 Working 且还有未成交数量，就能逐张改价格和数量。图表上拖这条线改价格（数量不变），交易页的“单独改单”或保护单上的铅笔按钮打开对话框，可同时改数量。发送的是对这一张单的 `order/modifyorder`（`orderQty` 为总数量，须大于已成交数量），不影响同一仓位的其他单，也不会因此重新配对。止损价越过 Bid/Ask 会立即触发、限价越过会立即成交，这两种直接拒绝；数量大于当前净仓时提示全部成交后会反向持仓。Suspended 状态的单（等待入场成交的括号或 ATM 腿）不在此列。此路径只用离线模拟数据验证，没有对真实账户发送过。
- 未成交入场单的原生括号（父子 / OCO）在图表上作为该入场单的 SL/TP 线显示，可在成交前拖动改价：确认后对对应括号单发送 `order/modifyorder`，数量取括号单自身数量。入场单非 Working、已有成交、括号与入场数量不一致、同类括号不唯一或属于 ATM（策略按成交价重新定价）时只读。此路径只用离线模拟数据验证，未对真实账户发送过。
- 1.1.2 分类修复仅用隔离副本和合成数据验收（107 项测试通过），没有接触用户账户；详见 [TP/SL 分类修复](tpsl-classification-fix.md)。
- 预估盈亏标为毛盈亏，缺失的手续费和保证金不显示为 0。实时净值若由余额 + 浮盈计算则标记“估算”。
- 报价超过 15 秒不能用于新下单/改保护；撤单不依赖实时行情，服务器 liquidation 不使用本地反向市价单冒充平仓。
- 受理不等于成交；回执后仍以订单/仓位推送为准。网络结果未知时不自动重发，当前会话停止继续写入，须先到官网核对。
- 已受理后缺少实体事件、重连快照对账及仓位删除的等待状态恢复尚不完整，可能持续阻止后续写入；离线复现见[审计 A1](implementation-audit.md)。
- REST 请求有统一最小间隔，不会通过轮换身份/端点规避频率限制；429/授权拒绝如实报告。
- 本地版本号只用于阻止过期的界面编辑提交，未确认 Tradovate 服务端支持客户端 CAS。
- 不包含跨账户跟单、自动策略、负价格期货、外币换算、第三方 SSO 白名单扩展、iOS 登录组件。

## 账户风险度

桌面底部资金栏和手机交易页显示当前账户的“可回撤额度”和“危险度”，算法与 BatchTradovate 相同。

- **爆号线**是账户模块的 Drawdown Auto Liq Level：`accountRiskStatus.maxNetLiq` − `userAccountAutoLiq.trailingMaxDrawdown`，追到 `trailingMaxDrawdownLimit` 后封顶并显示“已锁定”。两种实体来自 `user/syncrequest` 和之后的推送。缺任一数字时没有爆号线，两项都显示“—”。
- **可回撤额度** = 账户净值 − 爆号线，用资金栏同一个净值：空仓或持仓全部有 15 秒内的报价时是余额加浮动盈亏，否则是券商快照 `netLiq`，都没有时显示“—”。
- **危险度** = 1 − 可回撤额度 ÷ `trailingMaxDrawdown`，限制在 0～100%，TDD 和 EOD 用同一尺度。50% 起橙色，80% 起红色。
- 悬停可看爆号线的来源；手机直接写在卡片里。断线时来源显示“行情中断”或“缓存快照”，不会显示为实时。
- 这些数字只用于显示，不参与下单判断。风险实体的推送只更新这两项，不触发对账或资金快照刷新。

## 存储与隐私

- 密码由浏览器/系统密码服务保存；Kotlin 和 native host 不读取密码字段。
- 1.1.3 起会话通过重定向进程管道或限定 origin 的 Android WebMessage 交接后，在本机加密保存，不写入 Demo JSON、命令行、日志或剪贴板。
- Windows 使用当前用户 DPAPI，文件位于 `%LOCALAPPDATA%/UniTrading/credentials-v1/tradovate.dpapi`；设置 `UNITRADING_DATA_DIR` 时改为该目录下的 `credentials-v1`。macOS 的文件在 `~/Library/Application Support/UniTrading/credentials-v1`（文件名相同），内容为 AES-256-GCM，随机密钥存在登录钥匙串（按数据目录和文件各一把，不同步到 iCloud），通过系统 `security` 工具读写，避免 ad-hoc 签名的应用每次更新后弹出钥匙串授权。Android 使用 Keystore AES-256-GCM，密文位于应用 `noBackupFilesDir`。
- 重启按用户 ID 和 Demo/Live 环境恢复有效会话，从服务器重新同步账户，恢复上次选择的交易商/账户。运行中续期同步保存；临近过期的会话先续期再读取账户。过期、撤销的登录需要重新认证；网络故障保留凭据并提供重试入口。
- “账户 / 切换账户”提供“清除已保存登录”，确认后删除应用会话并断开连接；不撤单、不平仓、不清空浏览器密码。正常退出和“断开 Tradovate”保留会话，重启仍可恢复。
- 旧版本只保存浏览器资料，升级后须成功登录一次才能建立应用会话存档。iOS 尚无登录组件，也未接入凭据存储。
- 只接受官方主文档和匹配环境的消息；拦截非白名单导航、下载与证书失败，不绕过服务端挑战。
- 不删除用户的浏览器资料或清空凭据；如需清理，可使用本应用的独立浏览器 profile 和系统密码服务设置进行管理。

## 构建与测试

```bash
./gradlew :broker-api:desktopTest :demo-broker:desktopTest :tradovate-broker:desktopTest :composeApp:desktopTest :androidApp:assembleDebug :composeApp:stageTradovateLoginHost
```

Native host 的离线策略测试：

```bash
native-auth/build/1.1.1/UniTrading.TradovateLogin.exe --self-test
```

浏览器桥还运行 Node.js 隔离测试，因此全套测试需要 Node.js。测试不访问登录账户或发送真实订单。

### 当前验证边界（1.1.4）

常规 JVM/KMP/Compose 套件 127 项通过，当前主题/导航/图表验收见 [1.1.4 记录](theme-navigation-chart.md)。登录保存与恢复使用合成会话、DPAPI/Keystore 及 Android 进程恢复测试；这些测试未覆盖真实账户的免登录流程。

真实账户读取证据仍来自以下 1.1.1 的 Windows Demo 只读流程。真实下单、改价、成交回执未联调；Android 完整登录到账户/行情链路、iOS、真机和 release 设备运行尚未形成通过记录。本次审计重跑回归并确认了待处理缺口，见[实现核对](implementation-audit.md)。

### 历史验证边界（1.1.1）

该版本结果：**86 项 JVM/KMP/Compose 自动测试通过，0 失败、0 跳过**；Windows native 策略 self-test 另有 **52 项断言通过**。Android debug APK 构建成功（1.1.1 / versionCode 3）。

APK SHA-256：`a11173e4cac3042b7595ee84c8ce6aa7b02d5f3211be8da74cae73785bba750c`。

- 已有本地 Demo 交易/拖动/恢复测试继续运行。
- 新增协议测试覆盖 origin 与环境隔离、JWT 字段提取、帧解析、合约精度、净额状态、原始成交、OSO、改价、保护关系、超时不重发、行情授权缺失、服务器 liquidation。
- UI 测试覆盖空远程目录、Live 明示、整数张数和未知保证金显示。
- Node.js 运行实际登录桥脚本，验证只捕获目标会话、不读取密码/本地存储、不向跨域/子框架开放交接。
- Windows native host 已编译，52 项断言覆盖共享认证域、令牌清理及自动完成状态机；不再强制等待手动 DONE。
- Windows 真实 Demo 只读实测：会话自动交接成功，2 个授权账户、2 个合约、实时报价与 155 根历史 K 线进入插件；订单请求为 0。浏览器记住的账号密码已出现自动填充，诊断仅点击官网 Login，未读取密码字段。
- Android 1.1.1 已构建；此前官方登录页可打开，模拟器未启用密码服务，因此不把 Android 密码自动填充记为实测通过。
- **真实下单、改价和成交回执未联调；未发送真实交易。** 自动测试仍使用合成令牌与模拟协议，真实账户只用于单独、明确禁止订单端点的只读诊断。
- iOS、Android 真机及 Windows 安装包发布未验证。

## 参考来源

- 用户提供扩展的静态分析与来源 hash：[接入计划](tradovate-integration-plan.md)。参考扩展本身未执行或整体复制。
- [Tradovate 官方 API](https://api.tradovate.com/)：OpenAPI 中的 Product/Contract/Position/Order/OSO/liquidation 与行情协议。
- [Tradovate 官方示例](https://github.com/tradovate/example-api-faq)：WebSocket、历史图表与服务器订单策略。
- [WebView2 密码保存](https://learn.microsoft.com/en-us/dotnet/api/microsoft.web.webview2.core.corewebview2settings.ispasswordautosaveenabled)。
- [Android Autofill](https://developer.android.com/identity/autofill/autofill-optimize)。
