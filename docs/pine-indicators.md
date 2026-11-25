# Pine 指标与绘图

在图表工具条点 **指标 → Pine**，可直接保存默认的布林带（20 周期、2 倍标准差），也可粘贴自己的 Pine 源码后点 **保存**。可添加多份脚本、独立隐藏、排序或移除；源码与其他指标一起保存在当前图表的本机配置中，切换品种或周期后继续使用。保存图表模板也会带上脚本源码，不要把不愿分享的源码贴进准备共享的模板。

## 主图、副图和绘图对象

`indicator(…, overlay=true)` 与 K 线共用价格轴；省略 `overlay` 或写 `false` 会为这个脚本创建独立、可拖拽调整高度的副图。支持 `force_overlay=true` 的绘图调用可以从副图脚本画到价格图。脚本读取的是实际价格（例如 `5250.25`，不是内部定点整数），`time` 为 Unix 毫秒。切换品种、加载历史或更新 K 线时，应用根据最新的 K 线快照重算；画线、矩形和标签跟随图表平移和缩放。

默认脚本仅在主图上画布林带的中轨、上轨和下轨：

```pine
//@version=6
indicator("布林带", overlay=true)
length = 20
basis = ta.sma(close, length)
deviation = 2 * ta.stdev(close, length)
plot(basis, "中轨", color=color.orange)
plot(basis + deviation, "上轨", color=color.blue)
plot(basis - deviation, "下轨", color=color.blue)
```

如果要画在独立副图，下面是 RSI 和水平参考区间的写法：

```pine
//@version=5
indicator("Pine RSI")
plot(ta.rsi(close, 14), "RSI", color=color.aqua, linewidth=2)
upper = hline(70, "Upper", linestyle=hline.style_dashed)
lower = hline(30, "Lower", linestyle=hline.style_dashed)
fill(upper, lower, color.new(color.blue, 90))
```

## 兼容范围

- 支持 Pine **v5/v6 子集**：变量与 `var`、重赋值、运算和三元表达式、`[n]` 历史引用、`if` / `else` 和有界的 `for`。可读当前品种的 OHLCV、时间与部分品种/周期信息。`input.*` 取源码中的默认值；要改参数，请修改源码中的默认值。
- 支持 `plot`（普通线、断点线、柱、直方图、圆点、十字）、`hline`、`fill`、`plotshape`、`plotchar`、`bgcolor`、`barcolor`，以及数值坐标版本的 `line.new`、`label.new`、`box.new` 和常用 `set_*`、`get_*`、`copy`、`delete`。支持 `xloc.bar_index` 和 `xloc.bar_time`；`box` 的延伸只延长两条横边，不延长填充。无值的填充会断开。
- 支持常用的 `ta.sma/ema/rma/rsi/wma/stdev/highest/lowest/atr/tr/change/crossover/crossunder` 等、基础 `math.*`、颜色和文本格式化。并非 TradingView 的完整运行时；依赖数组、元组、自定义函数、库导入、其他品种/周期的 `request.*`、策略、告警、`table` 或 `polyline` 的脚本不能直接使用。脚本没有联网、文件或交易接口，不会下单。
- 脚本使用最近至多 **5,000 根已加载 K 线**，`bar_index` 从这段样本的第一根算起；没有分钟内逐笔执行状态。每次重算都回放快照，因此 `barstate.islast` 标记最后一根，`barstate.isrealtime` 始终为 `false`。数据源没有成交量时 `volume` 为 `na`。回放和缺失数据处理可能与 TradingView 不同。
- 每份源码最多 32,768 个字符，常用 `ta.*` 周期为 1–1,000。运行限制历史引用、循环工作量及绘图输出；绘图非常密集时会省略部分几何或文字，极端价格也不会拉伸主图交易价格轴。可用 `show_last` 或减少对象数量。保存时会显示语法或不支持调用的行号；依赖行情才出现的运行错误显示在图表上，其他指标照常工作。
