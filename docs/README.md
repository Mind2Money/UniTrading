# UniTrading 文档

当前阶段：Windows Desktop 与 Android 本地 Demo 已验收；新增 Tradovate 登录与服务器插件已实现，真实账户联调结果与限制见专用文档。iOS 保留工程骨架，未在 Mac/Xcode 验证。

- [实施计划](implementation-plan.md)：已确认的平台范围、构建环境、插件架构、交易规则、实施顺序与验收门槛。
- [UI 与图表交互计划](ui-and-chart-design.md)：视觉规范、桌面/手机布局、图表图层、拖动 SL/TP 和订单预览。
- [插件接入](plugin-guide.md)：接口职责、数值单位与注册示例。
- [验证记录](verification.md)：本地 Demo 初版测试与运行记录。
- [Tradovate 接入计划](tradovate-integration-plan.md)：源码参考、鉴权选择与接入边界。
- [Tradovate 使用与验证](tradovate.md)：网页登录、密码管理、远程交易语义和验证状态。

长期目标为 Windows 桌面、Android 与 iOS 的 Kotlin Multiplatform 本地模拟交易终端；本次验收包含 Desktop 与 Android。交易商通过接口注册，行情、标的、账户及执行结果均来自交易商插件。iOS 构建与运行需要 macOS/Xcode，当前未验证。
