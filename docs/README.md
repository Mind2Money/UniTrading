# UniTrading 文档

当前代码：`main`，应用 **1.1.4 / Android versionCode 6**。Windows 与 Android 默认从登录页开始，恢复有效的已保存 Tradovate 会话；不再默认注册本地模拟券商。iOS 有工程入口，但缺少登录与凭据存储接线，不能视为可用交易端。

## 当前实现与使用

- [A1–A7 修复进度](remediation-progress.md)：当前持续修复任务、验证证据和未完成项。

- [当前实现、构建与验证状态](current-status.md)：功能矩阵、代码入口、构建命令、CI 与验证边界；作为当前状态入口。
- [代码与文档核对](implementation-audit.md)：1.1.4 已确认的实现缺口、文档差异和可复现诊断。
- [Tradovate 使用与验证](tradovate.md)：登录、服务器账户、交易限制、会话存储。
- [插件接入](plugin-guide.md)：接口职责、数值单位、空注册表及动态注册。
- [主题、导航与图表](theme-navigation-chart.md)：Material 3 导航、自定义颜色、两轴缩放与平移。
- [图表指标](indicators.md)：MA/EXPMA/BOLL 叠加线与 RSI/MACD/KDJ/ATR 副图，多指标叠加、参数与颜色自定义、本机保存。
- [验证记录索引](verification.md)：当前摘要及首版本地 Demo 的历史证据。

## 历史设计与版本记录

以下文件按当时版本保留，不把其中的旧产品范围、测试数量或产物 hash 当成当前状态。

- [初版实施计划](implementation-plan.md)：本地 Demo 阶段的设计基线，当前默认登录流程已替代该启动方案。
- [初版 UI 与图表设计](ui-and-chart-design.md)：初版布局与交互要求，固定深色配色已由可配置主题替代。
- [Tradovate 初版接入计划](tradovate-integration-plan.md)：协议来源及最初方案，仅内存会话已被加密保存替代。
- [1.1.1 登录交接修复](tradovate-login-fix.md)：Windows Tradovate Demo 真实只读链路的历史实测。
- [1.1.2 TP/SL 分类修复](tpsl-classification-fix.md)：父子、OCO、ATM 保护关联的离线验收。
- [1.1.3 登录保存修复](saved-login-fix.md)：DPAPI / Keystore 存储及恢复测试。

仓库当前只使用 `main` 分支。文档分别标注“未实现”“已实现但未实测”和“已验证”；历史设计不等于已交付功能。
