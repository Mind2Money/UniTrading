# UniTrading 文档

当前阶段：Windows Desktop 与 Android 本地交易 Demo 已完成实现与双端验收。iOS 保留工程骨架，未在 Mac/Xcode 验证。

- [实施计划](implementation-plan.md)：已确认的平台范围、构建环境、插件架构、交易规则、实施顺序与验收门槛。
- [UI 与图表交互计划](ui-and-chart-design.md)：视觉规范、桌面/手机布局、图表图层、拖动 SL/TP 和订单预览。
- [插件接入](plugin-guide.md)：接口职责、数值单位与注册示例。
- [验证记录](verification.md)：实际测试、运行证据和未验证范围。

长期目标为 Windows 桌面、Android 与 iOS 的 Kotlin Multiplatform 本地模拟交易终端；本次验收包含 Desktop 与 Android。交易商通过接口注册，行情、标的、账户及执行结果均来自交易商插件。iOS 构建与运行需要 macOS/Xcode，当前未验证。
