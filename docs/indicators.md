# 图表指标

实现日期：2026-10-29。代码入口：[Indicators.kt](../composeApp/src/commonMain/kotlin/com/unitrading/chart/Indicators.kt)（公式与配置模型）、[ChartIndicators.kt](../composeApp/src/commonMain/kotlin/com/unitrading/chart/ChartIndicators.kt)（叠加线、副图与图例绘制）、[IndicatorSettings.kt](../composeApp/src/commonMain/kotlin/com/unitrading/app/IndicatorSettings.kt)（保存与校验）、[IndicatorSettingsDialog.kt](../composeApp/src/commonMain/kotlin/com/unitrading/ui/IndicatorSettingsDialog.kt)（设置对话框）。

## 使用

- 点击图表工具条的“指标”打开设置对话框；已添加指标时，入口显示数量。
- 对话框可反复添加同类或不同指标，上限 12 个；每个指标可单独设置参数、每条线的 `#RRGGBB` 颜色（可点色点用 RGB 滑块选色）、显示开关、上下顺序和移除。
- 参数或颜色无效时该指标标红，保存按钮禁用；取消不改动已保存的指标。
- 保存写入本机 `indicators-v1`（与主题 `theme-v1` 同一存储），重启后恢复；这组指标对所有交易商、品种和周期生效，不按品种分别保存。
- 保存内容损坏、含未知指标或非法参数时清空图表中的指标并提示，不覆盖原文件，不阻止登录。

## 绘制

- 叠加型指标（MA、EXPMA、BOLL）画在 K 线图内，共用价格轴；自动价格范围包含可见区间内的指标值，因此布林带不会被裁出图外。手动缩放价格轴时不自动扩展。
- 副图型指标（RSI、MACD、KDJ、ATR）在 K 线图与时间轴之间各占一个副图：每个副图占图表高度 20%，副图总高度不超过 50%，因此 3 个以上副图时每个会变矮。副图右侧显示当前上下界，RSI 画 30/70、KDJ 画 20/80、MACD 画 0 参考线。
- 图例在 OHLC 下方逐行显示叠加指标名称和当前值；副图标题行显示该指标的值。十字线移动时两者都显示所指 K 线的值，否则显示最新 K 线；十字线横线落在副图内时，右轴标注该位置对应的指标值。
- 收起图例时，OHLC 读数和指标列表一起隐藏。收起/展开状态写入本机 `chart-v1`（与周期收藏同一份设置），切换手机页面、品种、周期和重启后保持；从未切换过时沿用布局默认值：手机横屏等短屏默认收起，其他展开。
- 副图区域横向拖动平移时间，不缩放价格；时间轴缩放手势仍在最底部的时间刻度上。
- 指标按当前已加载的全部 K 线计算，预热期内无值不绘制。Tradovate 每品种/周期请求并保留最近 2000 根（`CHART_BARS`），是周期上限的两倍，周期 1000 的指标在历史加载完整时仍有 1000 根有值；本地 Demo 引擎只保留 48 小时模拟 tick，按周期聚合后 1 分钟约 2880 根、5 分钟 576 根，长周期指标在 Demo 上可能没有值。

## 公式约定

| 指标 | 默认参数 | 约定 |
| --- | --- | --- |
| MA | 周期 20 | 收盘价简单平均 |
| EXPMA | 周期 20 | 首值取前 N 根收盘价简单平均，之后 `α = 2 / (N + 1)` |
| BOLL | 周期 20，带宽 2 | 中轨为 MA；上下轨为中轨 ± 带宽 × 总体标准差（除以 N） |
| RSI | 周期 14 | Wilder 平滑：首个平均涨跌幅为前 N 次变动的简单平均，之后 `(前值 × (N − 1) + 本次) / N`；无涨跌时为 50 |
| MACD | 12 / 26 / 9 | DIF = EXPMA(12) − EXPMA(26)，DEA = EXPMA(DIF, 9)，MACD 柱 = (DIF − DEA) × 2，柱按涨跌色绘制 |
| KDJ | 9 / 3 / 3 | RSV = (收 − N 日最低) / (N 日最高 − N 日最低) × 100，区间为零时取 50；K、D 以 1/3 权重平滑，起始值 50；J = 3K − 2D |
| ATR | 周期 14 | 真实波幅从第二根起使用前收盘；首值为前 N 根真实波幅的简单平均，之后 Wilder 平滑 |

周期为 1 至 1000 的整数（`MAX_INDICATOR_PERIOD`），布林带带宽为 0.1 至 10（两位小数）；MACD 要求快线周期小于慢线。周期大于当前已加载 K 线数时该指标整段处于预热期，不绘制。不同平台对 EXPMA 起始值、RSI 零变动和 KDJ 起始值的处理不一致，上述约定以本仓库实现为准。

## 未实现

- 不支持自定义公式或脚本，只能组合内置指标及其参数。
- 没有按品种或周期分别保存的指标模板。
- 副图没有独立的纵向缩放，范围始终自动计算。
- 图例和副图标题不响应点击，修改指标必须打开对话框。

## 验证

- `IndicatorsTest`：MA/EXPMA 起算、布林带总体标准差、RSI Wilder 平滑与边界值、线性序列 MACD 常量、KDJ 起始值与边界、ATR 起算、参数/颜色校验（含周期 1000 接受、1001 拒绝）、2000 根 K 线上周期 1000 的全部指标预热与有值区间、标签格式、副图范围与数值格式。
- `ChartHistoryDepthTest`：Tradovate `md/getChart` 请求 `asMuchAsElements` 为 2000，收到 2500 根后保留最新 2000 根且时间连续。服务器对 2000 根请求的实际返回数量未用真实账户实测。
- `IndicatorSettingsTest`：重启后恢复、重复 ID / 超量 / 非法参数拒绝、磁盘失败保留旧配置、损坏或未知指标回退且不覆盖原文件。
- `IndicatorUiTest`：添加 EXPMA/BOLL/RSI，修改周期与颜色，无效参数禁用保存，保存后图例与副图出现并持久化；副图下方时间轴缩放和副图区域平移；取消不改动配置；隐藏指标移除副图；预存 MACD/KDJ/MA 随图表加载并可移除。截图输出到 `artifacts/chart-indicators.png`。

```powershell
./gradlew.bat :composeApp:desktopTest --tests "com.unitrading.chart.IndicatorsTest" --tests "com.unitrading.app.IndicatorSettingsTest" --tests "com.unitrading.ui.IndicatorUiTest" :tradovate-broker:desktopTest --tests "com.unitrading.tradovate.ChartHistoryDepthTest"
```
