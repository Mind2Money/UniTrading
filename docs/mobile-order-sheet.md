# 移动端下单 Sheet

参考用户提供的 8.24 秒 MT5 录像：0.00 秒为关闭；约 0.25–1.00 秒及 5.50–8.00 秒为最小化；约 2.00–3.25 秒为展开。其余高度为拖动过程，不增设停靠状态。

使用 Compose Material 3 官方 `BottomSheetScaffold` 和 `rememberStandardBottomSheetState`，通过组合封装复用官方手势、吸附、嵌套滚动和无障碍展开/收起。没有复制官方源码或自制拖动状态机。参考：[官方实现](https://github.com/JetBrains/compose-multiplatform-core/blob/jb-main/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/BottomSheetScaffold.kt)。

| 应用状态 | 表现与操作 |
| --- | --- |
| CLOSED | 关闭 Sheet，显示原有下单入口和导航。明确关闭会清空本次草稿。 |
| MINIMIZED | 官方 PartiallyExpanded，112dp：拖动柄、品种、数量/方向摘要、SL/TP、展开/关闭入口、横向订单类型栏。图表可操作；类型和保护开关直接修改同一份草稿。 |
| EDITING | 官方 Expanded，正常竖屏占可用高度 56%，短屏或键盘展开时使用可用高度。表单独立滚动，确认入口固定在面板底部。 |

最小化和展开共享同一个 Sheet 实例与表单，不用独立横栏替换 Sheet。手势只能在这两个位置吸附，快速下滑也不丢弃草稿。图表没有遮罩，点击图表不会收起面板。激活下单时以面板替代底部导航；关闭后恢复导航。最小化和展开之间图表布局不变。

返回键优先关闭键盘；无键盘时，展开退到最小化，最小化再返回关闭。关闭按钮在请求处理中禁用。买卖、订单类型、SL/TP、数量和图表拖线均只修改草稿，仍需现有检查与确认流程才能提交交易。

验证覆盖：原生上/下滑、重复下滑、无障碍收起、紧凑栏切换类型与保护、恢复数量、图表拖线回填、关闭重置、确认成功关闭、拒单保留、横屏与大字体、Android 键盘/返回键。截图和验证日志保存在 artifacts/mt5-sheet/，不入库。
