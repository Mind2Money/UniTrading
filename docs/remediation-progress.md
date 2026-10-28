# 审计问题修复进度

目标：修复 [1.1.4 审计](implementation-audit.md) 的 A1–A7 全部问题。所有工作保持在 `main`；本记录不把部分完成当作目标完成。

| 项目 | 当前代码与证据 | 剩余工作 |
| --- | --- | --- |
| A1 指令等待恢复 | 完整快照后对账；30 秒后进入核对并尝试只读同步；显式“同步账户”入口；处理净仓删除/归零；未知请求保留关联信息，证据不足不解除写入限制，不重发订单。6 项 `ReconciliationRecoveryTest` 常规回归通过，含自动核对、晚到事件和证据不足反例 | CI 跨端回归 |
| A2 iOS 登录 | Swift WKWebView 官方域/主框架校验、取消和自动交接；Keychain 本机凭据存储；Kotlin 桥与实际入口已接线，桥的跨域拒绝、取消和存储错误测试通过 | macOS 编译与模拟器平台自测尚待 CI 结果，不能声明 iOS 已验证 |
| A3 远程历史 | 已核对官方 FillPair、FillFee 与历史 chart 协议 | 实现配对、费用分配、净盈亏未知语义、远程成交选择/定位及测试 |
| A4 保护编辑 | 能力声明和前端校验已阻止不支持的移除/同时修改；单条有效编辑与本地原子编辑回归通过 | 完成远程已有净仓添加/删除保护的服务端协议与测试，不以禁用界面代替完整目标 |
| A5 动态目录 | 删除所选品种时选择剩余品种；重置草稿和失效编辑；无规格持仓/挂单行只读；目录变空/恢复的 Compose 测试通过 | 完整跨端验收 |
| A6 图表 | 保留原缩放和平移实现 | 横向价格十字线、独立保护线开关、时间空档显示、历史分页与定位 |
| A7 CI | 新增 Windows 回归/打包、Android debug 与 R8 release 模拟器测试、macOS Kotlin Native/应用/Keychain 验证 jobs；签名依赖所有验证成功 | 实际执行和修复 CI；最终验证所有 gates 对完整实现有效 |

## 当前验证

- 本机完整 139 项 JVM/KMP/Compose 测试与 Android debug 构建通过；包含原生桥 3 项合成测试。
- Swift 原生部分没有在 Windows 上编译；将通过新增 macOS runner 验证。
- 诊断性质的“保持缺陷现状”探针已移除，相关正常行为纳入默认回归；旧审计文件保留发现时证据。
- 尚未连接用户真实账户或发送交易。实时金融协议以固定官方端点和服务器事实为准。

## 协议依据

- [Tradovate 官方 API](https://api.tradovate.com/)：PlaceOCO/PlaceOcoResult、FillPair、FillFee、md/getChart 时间范围。
- [Apple WKWebView](https://developer.apple.com/documentation/webkit/wkwebview)、[WKFrameInfo.securityOrigin](https://developer.apple.com/documentation/webkit/wkframeinfo/securityorigin)：主文档来源校验和导航代理。
- [GitHub runner 规格](https://docs.github.com/en/actions/how-tos/write-workflows/choose-where-workflows-run/choose-the-runner-for-a-job)：`macos-15` ARM64 runner。
