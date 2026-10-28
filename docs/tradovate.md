# Tradovate 使用与验证

## 接入方式

从“账户 / 切换账户”选择“连接 Tradovate”，选 Demo 或 Live，再打开官方登录页。

- 页面为 `https://trader.tradovate.com/welcome`，由官方网页执行签名与登录，MFA/验证码保持原流程。
- 只读取本应用弹出的登录窗口返回的 `accessToken`、`mdAccessToken` 等会话字段；不扫描 Chrome/Edge 的用户资料，不从密码输入框读取账号密码。
- 认证与续期使用共享 `live.tradovateapi.com`；账户和行情仍连接用户选择的 Demo/Live 环境。认证域名不代表交易环境。
- 取得有效账户会话后自动交接，无须等待额外的 `mdAccessToken` 或强制点击“完成登录”。行情使用账户访问令牌连接对应 md 网关，是否有权限以服务器响应为准。
- 仅从固定 Tradovate HTTPS/WSS 端点读取数据和提交用户确认的命令，不经过压缩包内第三方服务。
- Native API 权限仍由 Tradovate 决定。官方网页登录成功不保证该账户可使用 API、实时行情或某种订单。

## 记住密码和自动填充

### Windows Desktop

使用 WebView2 自有密码管理功能，开启 `IsPasswordAutosaveEnabled` 和 `IsGeneralAutofillEnabled`。是否保存由浏览器提示和用户选择决定；应用不复制密码到自己的数据库。

独立 profile 位于 `%LOCALAPPDATA%/UniTrading/browser/demo` 与 `.../live`。该目录不是用户已有的 Edge/Chrome profile。首次登录保留最多 60 秒供浏览器保存密码，并显示倒计时；只观察密码库文件的大小/修改时间，不读取内容。保存完成、已记住的登录或续期会话在短暂等待后自动返回，也可点击“立即返回”。明确点击“取消登录”仍会放弃本次交接。

需要系统 Microsoft Edge WebView2 Runtime 和 Windows .NET Framework 4.x。`:composeApp:run` 会自动构建/准备 native host；无需 .NET SDK。

### Android

使用 Android WebView 的独立 Demo/Live profile 与系统 Autofill 框架。收到会话后自动提交保存上下文、等待系统提示处理并返回主界面。**自动保存/填充依赖用户启用一个系统密码服务**；WebView 不自带独立密码管理器。未启用时登录窗会显示说明和系统设置入口。

要求 System WebView 支持文档起始脚本、受限 WebMessage 和多 profile；缺少任一能力时要求更新，不降级成对所有网页暴露 JavaScriptInterface 的方案。

## 交易能力与限制

- 服务器提供账户、实际到期合约、产品 tickSize/valuePerPoint、Bid/Ask、历史和实时 K 线。
- 当前支持 USD 线性期货，数量为正整数张；均价保留六位十进制，允许均价不在报价 tick 上。
- 净额仓位按账户 + 合约显示，外部增减仓/反向成交以服务器推送更新。
- 原始成交以服务器 fill 显示。不能可靠配对或没有费用数据时，不伪造完整的已实现净盈亏。
- 支持 Market / Limit / Stop、撤单、服务器 liquidation、挂单入场价修改。
- 带保护价的新订单使用一个 `order/placeoso` 请求；双保护由服务器关联为 OCO。已有该合约净仓时不叠加新括号单，避免保护数量失配。
- 保护单识别支持原生父子关系、无父订单的 OCO 以及单层 ATM 的 `orderStrategy/orderStrategyLink`。只有同账户、同合约、方向/剩余数量及关联证据一致时才开放保护价修改；不靠价格高低猜测 SL/TP。
- 挂单表与图表区分入场、SL 止损、TP 止盈和关联待确认。保护单不走入场改价路径；不完整、多组或冲突的关联保持只读。每次只改一条保护价，增删或复杂保护结构需在官网处理。
- 1.1.2 分类修复仅用隔离副本和合成数据验收（107 项测试通过），没有接触用户账户；详见 [TP/SL 分类修复](tpsl-classification-fix.md)。
- 预估盈亏标为毛结果，不把缺失手续费/保证金显示为 0。实时净值若由余额 + 浮盈计算则标记“估算”。
- 报价超过 15 秒不能用于新下单/改保护；撤单不依赖实时行情，服务器 liquidation 不使用本地反向市价单冒充平仓。
- 受理不等于成交；回执后仍以订单/仓位推送为准。网络结果未知时不自动重发，当前会话停止继续写入，须先到官网核对。
- REST 请求有统一最小间隔，不会通过轮换身份/端点规避频率限制；429/授权拒绝如实报告。
- 本地版本只阻止陈旧 UI 提交，不宣称 Tradovate 服务端支持客户端 CAS。
- 不包含跨账户跟单、自动策略、负价格期货、外币换算、第三方 SSO 白名单扩展、iOS 登录组件。

## 存储与隐私

- 密码由浏览器/系统密码服务保存；Kotlin 和 native host 不读取密码字段。
- 会话令牌只在内存、重定向进程管道或限定 origin 的 Android WebMessage 中传递，不写入 Demo JSON、命令行、日志或剪贴板。
- 退出应用后需要通过登录窗口恢复授权；浏览器可能保留官方登录 cookie，密码服务可以提供自动填充。
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

### 当前验证边界

最终结果：**86 项 JVM/KMP/Compose 自动测试通过，0 失败、0 跳过**；Windows native 策略 self-test 另有 **52 项断言通过**。Android debug APK 构建成功（1.1.1 / versionCode 3）。

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
