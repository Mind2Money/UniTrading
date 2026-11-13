# UniTrading 文档

当前代码位于 `main`，应用版本为 1.6.0 / Android versionCode 12。Windows 与 Android 启动时先校验授权码，授权后显示登录页，并恢复仍有效的已保存 Tradovate 会话，默认不注册本地模拟券商。iOS 有工程入口，但尚未接入登录和凭据存储，交易流程暂不可用。

## 当前实现与使用

- [A1 至 A7 修复进度](remediation-progress.md)：当前持续修复任务、验证证据和未完成项。

- [当前实现、构建与验证状态](current-status.md)：功能矩阵、代码入口、构建命令、CI 与验证边界，是查询当前状态的入口。
- [授权码激活](license-activation.md)：启动授权页、授权码验证值、本机加密授权记录、失败锁定、更换授权码，以及离线校验的边界。
- [本地构建与 CI](local-build.md)：本地测试、Windows/Android/iOS 构建、签名、Telegram 发布、tag 发布及后台完成回调。
- [发布说明](release-notes.md)：每个 tag 的双语更新内容，也是 GitHub Release 的正文。
- [应用内更新](app-update.md)：设置页检查和安装新版本、持仓时的拦截规则、发布约定与验证记录。
- [代码与文档核对](implementation-audit.md)：1.1.4 已确认的实现缺口、文档差异和可复现诊断。
- [Tradovate 使用与验证](tradovate.md)：登录、服务器账户、交易限制、会话存储。
- [Rithmic 接入与验证](rithmic.md)：R | Protocol API 直连登录、四个 plant、交易边界与未实测项；应用内入口暂时隐藏。
- [连续合约](continuous-contracts.md)：连续图表与指标、上一完整交易日成交量主力、实际到期合约下单及换月处理。
- [插件接入](plugin-guide.md)：接口职责、数值单位、空注册表及动态注册。
- [Material 3 界面规范](ui-material3.md)：配色角色、字阶、图标、共用组件、导航布局和审计结论；改界面前先读。
- [主题、导航与图表](theme-navigation-chart.md)：Material 3 导航、自定义颜色、两轴缩放与平移。
- [手机布局](mobile-layout.md)：参考 MT5 的图表优先布局，单行图表工具条，资金和回撤在账户页总览，以及各次调整的验证记录。
- [手机下单界面](mobile-order-sheet.md)：上滑面板、停靠下单栏和独立弹窗三种下单界面的状态、尺寸、收起与返回规则和验证范围。
- [桌面布局](desktop-layout.md)：拖动分隔线调整各面板尺寸、本机记忆、窗口边界及重置操作。
- [图表指标](indicators.md)：MA/EXPMA/BOLL 叠加线与 RSI/MACD/KDJ/ATR 副图，多指标叠加、参数与颜色自定义、本机保存。
- [图表画图工具](chart-drawing.md)：TradingView 式左侧工具栏，趋势线、斐波那契、江恩、形态、测量、形状与注释，按品种保存。
- [K 线缓存](candle-cache.md)：本地持久化、重启恢复、自选品种后台预读、按缺失区间分页补取与实时更新合并。
- [实时盈亏通知](live-pnl-notifications.md)：Android Live Update / 常驻通知与 iOS 灵动岛 Live Activity 显示当前账户浮动盈亏，刷新规则、权限与边界。
- [今日盈亏](daily-pnl.md)：直接使用券商当日总盈亏快照，标明交易日、更新时间和缺失数据。
- [子账户风控](account-risk-lock.md)：交易锁定（按券商当日盈亏，可自动平仓、可冻结）、手数与品种限制、订单风控、平仓此子账户 / 全部子账户，以及只在本机生效的边界。
- [账户分析](account-analytics.md)：账户页的总览、交易、分析三页，统计口径，本机平仓缓存及清除方式，账户别名。
- [验证记录索引](verification.md)：当前摘要及首版本地 Demo 的历史证据。

## 历史设计与版本记录

以下文件保留各版本当时的设计和结果。其中的产品范围、测试数量和产物 hash 只对应所记版本。

- [初版实施计划](implementation-plan.md)：本地 Demo 阶段的设计基线，当前默认登录流程已替代该启动方案。
- [初版 UI 与图表设计](ui-and-chart-design.md)：初版布局与交互要求，固定深色配色已由可配置主题替代。
- [Tradovate 初版接入计划](tradovate-integration-plan.md)：协议来源及最初方案，仅内存会话已被加密保存替代。
- [1.1.1 登录交接修复](tradovate-login-fix.md)：Windows Tradovate Demo 真实只读链路的历史实测。
- [1.1.2 TP/SL 分类修复](tpsl-classification-fix.md)：父子、OCO、ATM 保护关联的离线验收。
- [1.1.3 登录保存修复](saved-login-fix.md)：DPAPI / Keystore 存储及恢复测试。

仓库当前只使用 `main` 分支。各文档按“未实现”“已实现但未实测”和“已验证”标注状态，历史设计中的要求需结合这些状态查阅。
