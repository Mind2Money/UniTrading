# 桌面端网络与系统代理

Windows、macOS 桌面版的 Tradovate 请求和实时连接会读取系统代理设置；Rithmic 连接也使用同一方式。软件更新检查同样使用系统代理。Tradovate 登录窗口使用操作系统的原生网页组件及其网络设置。

使用 Clash、Clash Verge 等软件时，可选择其规则模式并打开“系统代理”，然后启动或重新连接 UniTrading。系统代理是把应用连接交给代理软件，最终走代理节点还是直连仍由该软件的规则和系统的代理排除列表决定；无需开启代理软件的全局模式，但要确保券商域名被规则送往可用节点。Tradovate 常用域名包括 `trader.tradovate.com`、`*.tradovateapi.com` 和 `*.ninjatrader.com`，Rithmic 网关使用 `*.rithmic.com`。更新检查使用 GitHub 域名。

系统没有启用代理，或者代理规则将某域名设为直连时，连接仍按系统和代理软件的配置处理。此功能不会自动查找本地端口、更改系统设置、修改代理规则或关闭 TLS 证书检查。更改代理后，新建连接会重新选择路由；已建立的长连接需要断开并重新连接。系统代理解析由操作系统与 Java 运行时提供，代理自动配置、认证及特殊网络环境的可用性取决于系统配置和代理软件。

## 验证

`SystemProxyTest` 在本地建立只返回错误的 HTTP CONNECT 和 SOCKS 代理，验证 Tradovate HTTPS、Tradovate WebSocket 和 Rithmic WebSocket 的路由，以及现有客户端建立新连接时读取更新后的代理设置。另用本地服务验证交易 POST 不会因服务器要求重试而被自动重复发送。测试目标为不可用的测试子域名，不登录券商或提交订单。

Windows 系统代理的集成测试可在隔离的 Windows 环境中启用：把当前用户系统代理设为 `127.0.0.1:<空闲端口>`，启动测试 JVM 时指定 `-Djava.net.useSystemProxies=true`，并设置 `UNITRADING_TEST_WINDOWS_SYSTEM_PROXY` 为该端口，再单独运行 `SystemProxyTest.brokerRequestsFollowWindowsSystemProxy`。该测试验证真实系统代理选择及三个连接路径；请在测试后恢复原代理设置。

本次实现尚需在真实 Clash/Clash Verge 配置及真实券商 Demo 账户中进行端到端验证，不能保证代理节点本身的可用性或延迟。
