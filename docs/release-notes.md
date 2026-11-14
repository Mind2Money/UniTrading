# Unreleased

## 简体中文

- 修复图表上部分止损、止盈线无法拖动的问题：仓位保护不能成组修改、但交易商允许逐单修改时，仍可拖动对应的 SL / TP 线。每张保护单只显示一次，拖动只修改该单，沿用交易确认与风控检查；不能改单的保护线仍只读。

## English

- Fix stop and target lines that could not be dragged on the chart when their position's protection could not be edited together, even though the broker allowed each order to change on its own. Each order is still drawn once, and dragging changes only that order through the existing confirmation and risk checks; non-editable legs stay read-only.

# v1.6.0

## 简体中文

🎉 UniTrading 更新至 **v1.6.0**！新增子账户风控（交易锁定、手数与品种限制、订单风控、平仓此子账户和全部子账户）、手机的停靠下单栏和独立弹窗、图表长按菜单与市价下单、图表锁定和跟随现价的自动价格轴，以及入场后添加止损止盈和成交音效。

🙏 **特别鸣谢**
感谢 DC 群的 @XAU真爱粉 提交 [PR #3](https://github.com/Mind2Money/UniTrading/pull/3)：子账户风控（交易锁定、手数与品种限制、订单风控、平仓此子账户和全部子账户）、手机的停靠下单栏和独立弹窗、交易分析的本机平仓缓存，以及收藏周期按时长排序都来自这次贡献。

📖 **新功能使用说明**
1. 子账户风控：先在顶部“交易子账户”下拉里选中要管理的子账户，再打开「设置 → 子账户风控」，分别设置“交易锁定”“手数与品种限制”“订单风控”
2. 平仓：账户页“交易”页签顶部的“平仓此子账户”“平仓全部子账户”，确认框列出要处理和要跳过的子账户
3. 手机下单界面：在「设置 → 交易 → 下单界面」里选上滑面板、停靠下单栏或独立弹窗

🛡️ **子账户风控**
✅ 「设置 → 子账户风控」新增三项按子账户保存的规则：交易锁定、手数与品种限制、订单风控。平仓、撤单、收紧止损和挂减仓单不受这些规则限制（两个平仓按钮执行期间除外）；减仓单指与持仓方向相反、连同已挂的同方向单最多也只成交到持仓数量的单，不论哪几张先成交，持仓都不会反向。已挂的减仓单已经覆盖持仓时，再挂的反向单在交易锁定期间和禁止交易的品种里被拒（开启订单风控时只算手挂的单，券商自己的括号腿不算），提示先撤销或减少已挂的减仓单，或直接平仓；给持仓挂止损单例外（连同已挂的止损不超过持仓），例如只挂着满仓止盈时照样能挂止损，它和止盈都成交时持仓会反向；已有满仓止损时再叠挂的止损不算例外。交易锁定、禁止交易的品种和最大持仓都不拦与持仓反向的挂单改价和减量，不论它的数量多少（暂停期间止损只能收紧）；超过持仓的这类挂单移到能立即成交的价位，会马上成交并让持仓反向
✅ 交易锁定：券商当日总盈亏达到亏损上限或盈利目标后，这个子账户暂停开仓、加仓和放宽止损（持仓仍可能经上一条所说的挂单反向），直到券商报告下一个交易日；盈亏回落、切换账户、重启或停用规则都不会提前解锁。选“平仓并锁定”时，触发后先撤会开仓、加仓或反手的挂单，再平掉全部持仓，止损止盈单由应用留到平仓确认后再撤
✅ 交易锁定规则可以冻结到下一交易日（周五冻结到下周一）或下周一，冻结期间不能修改或停用；冻结按券商交易日计算，改本机时间不会解冻，结束后券商数据暂时过时也不会重新冻结；冻结时券商快照已过时，应用先重取一次交易日，等待时表单显示“正在向券商获取交易日…”；券商答复之前关闭表单即放弃，不保存，答复到了之后保存照常完成
✅ 手数与品种限制：最大持仓把每个品种挂单全部成交后可能出现的最大持仓相加，期货按整数张，止损、止盈等减仓单不占额度；同一组括号单的止损和止盈只按较大的一条计，券商没有标明成组的单按全部数量计，等入场成交的括号腿不计；超过上限时新单和加量只能是减仓单，或是不让总量变大的新止损单，不能反手，挂单都可以撤、改价或减量；禁止交易的品种可以写根代码（MES 包括 @MES 和各月份合约）或具体月份
✅ 订单风控：新开仓单按设置的金额自动带上止损止盈；或者按单笔风险金额和止损价计算数量，另设单笔最大数量，在图表上拖动草稿止损线时实时显示算出的数量。图表右键和键盘下单同样适用。减仓单不加止损止盈，所以连同已挂的手挂减仓单（止损、止盈等）会超过持仓的反向单（保护止损除外）在发出前被拒；券商括号单（包括只带止损或只带止盈的）旁的分批平仓照常发出，之后请把括号腿减到剩余持仓。改挂单和止损止盈时亏损和利润也不能越过设置的金额；与持仓反向的挂单（单独修改的止损止盈腿，手挂的止损单、限价单）加量后须仍是减仓单或不超过持仓的止损单，提示写明是它自己超过持仓，还是连同其他减仓单超过；单独修改的止损止盈腿往不利于持仓的方向移动或者加量时，按持仓数量检查金额，只改这条腿分几步也放不宽，价位不变只减量总是可以；新挂的止损单（数量不超过持仓）不按金额检查，所以把腿改小或撤掉后再新挂止损，合计亏损可以超过设置的金额。Rithmic 挂单的止损止盈随入场价一起移动
✅ 账户页“交易”页签新增“平仓此子账户”和“平仓全部子账户”：先撤会开仓、加仓或反手的挂单，平仓确认后再撤止损止盈。“全部”包括本应用所有登录下的子账户，Live 也在内；只读和未连接的子账户列出但跳过。开启“跳过交易确认”时也要确认
✅ 本机风控记录读不出时，所有子账户暂停开仓，设置里出现“重置风控设置”，确认后清除这台设备上的全部风控规则
✅ 自动平仓没有确认完成时，不论当前在哪个页面都会弹出提示，写明是哪个子账户，请到券商核对持仓和挂单；提示一直留到点关闭为止，被下单结果等其他提示暂时顶替后会重新出现，切换子账户、Android 因深色模式、字号或语言变化重建界面也不会清掉；自动平仓进行中界面被重建时不会重发指令，提示自动平仓被中断、结果待核对
⚠️ 交易锁定需要券商报告交易日，目前只有 Tradovate 能用，Rithmic 和本地模拟上不可用。Tradovate 上每个设了交易锁定的子账户约每 20 秒多一次资金快照请求，和下单指令排在同一个请求队列里
⚠️ 规则只在这台设备上的 UniTrading 里生效，券商网站、其他终端和其他设备不受限制；应用退出、被系统挂起或断线时不检查盈亏，也不会自动平仓
⚠️ 开启金额止损止盈后，Tradovate 和 Rithmic 上已有持仓的合约不能再加仓：应用不在已有净仓上叠加括号单
⚠️ 每条指令按检查时券商快照里的持仓和挂单判断：在 Tradovate 上很快地连按两次市价下单时，第二笔可能在第一笔成交之前按原来的持仓检查，锁定期间也可能因此反手
⚠️ Tradovate 的平仓请求本身会撤掉该合约的全部挂单：自动平仓或平仓按钮没有完成时，止损可能已被券商撤掉，请到券商核对
⚠️ 以上功能只经过离线测试，没有在真实 Tradovate 或 Rithmic 账户上运行；Android 只做了编译检查

📱 **手机下单界面**
✅ 「设置 → 交易 → 下单界面」可选上滑面板（默认，就是原来的下单面板）、停靠下单栏或独立弹窗
✅ 停靠下单栏竖屏在图表下方、横屏在图表右侧，不遮挡 K 线；止损止盈开关和价格始终可见，数量滑动条、参考 SL / TP 和预估放在“更多设置”里，展开与否会记住，下一单和重启后照旧；最小化再展开，表单的滚动位置和预估的展开状态不变
✅ 独立弹窗在对话框里下单：点 ×、按返回或点对话框外面只是收起，草稿留在图表上，底部按钮变成“继续下单”，点它接着编辑；这时再按返回，或点“取消订单”、图上草稿线的“×”，丢弃草稿
⚠️ 停靠下单栏和独立弹窗只在桌面测试中验证过，手势、键盘和系统返回没有在 Android 设备上试过

🧾 **下单确认**
✅ 确认框显示止损价和止盈价，没有设置时写“未设置”；市价单的这两项标为预估，券商的提示也显示在确认框里，例如 Rithmic 市价单的括号按实际成交价生成。横屏手机上放不下时内容可以滚动，确认按钮不可用的原因排在券商提示前面

👤 **账户页与交易分析**
✅ 资金（余额、净值、今日盈亏、保证金）和回撤与风险移到账户页“总览”，“交易”页签放持仓、挂单、历史和两个平仓按钮
✅ 交易分析把这台设备同步到的平仓记录在本机保留 30 天，券商不再返回的较早记录也计入统计，总览的“已加载历史净盈亏”同样计入；页眉写明本机记录从哪一天开始
✅ 部分平仓记录的净盈亏待确认时，总览和分析页一样合计净盈亏已知的记录，并写明另有几笔未计入，不再整项显示“—”；全部待确认时仍显示“—”，并写明几笔未计入
✅ 清除已保存登录时删除这些记录；断开已保存的登录时保留，重新连接后照常计入；断开没有保存的登录（例如没勾选记住密码的 Rithmic 登录）时一并删除
⚠️ 只记录应用运行并选中该子账户时同步到的记录，不会向券商补取更早的历史；记录以明文 JSON 存在应用数据目录，没有像已保存的登录那样加密

📈 **图表**
✅ 工具栏上的收藏周期按时长从短到长排列，未收藏的当前周期排在最后
✅ 图表空白处的菜单新增“买入市价”“卖出市价”两项，排在限价单、突破单前面，数量取下单面板，走与下单面板相同的校验、订单风控和确认；没有报价或交易商不支持市价单时不显示
✅ 手机和触屏上长按图表打开与右键相同的菜单：按在绘图上打开绘图菜单，按在订单线或其标签上打开线上按钮的菜单，按在空白处打开画水平线和下单的菜单，并在按住的位置显示十字线。都不命中时（例如价格轴、指标副图）仍按原来显示十字线和数值提示
⚠️ 长按菜单只在桌面测试中用模拟触摸验证过，没有在 Android 设备上试过；菜单下单没有在 Tradovate、Rithmic 账户上实际下单
✅ 桌面图表可以加锁：点图表下方缩放按钮条最右边的锁，加锁后鼠标滚轮不再缩放，而是前后移动图表（向前滚看更早的 K 线，向后滚回到较新的 K 线），价格轴上的滚轮也不再缩放价格；拖动、按钮和双指缩放照常可用。锁定状态会记住，重启后照旧
✅ 打开图表时价格轴不再拉得很大：图表停在最新 K 线时，自动价格轴以现价居中，高度按可见 K 线计算，持仓、止损止盈和挂单价位仍保留在图内，离现价较远的叠加指标（如 MA、BOLL）可能超出图外；现价离中线超过图高的四分之一才重新居中，不会每跳一下就晃动。以前手动拉过的价格轴只在回看历史的视图里恢复，停在最新 K 线的视图打开时改用自动价格轴；保存的价格轴与当前行情对不上（价格已远离、或单位不同）时也改用自动价格轴

🔊 **成交音效**
✅ 「设置 → 交易」新增“成交音效”开关，默认开启，并写明这是搞笑功能：入场、止盈、止损成交时各播放一段搞笑音效
✅ 关闭后成交不再出声，正在播放的片段立即停止；关闭期间的成交不会在重新打开后补播

🛡️ **入场后添加止损止盈**
✅ Tradovate 和 Rithmic 的持仓可以在入场后添加止损、止盈：在图表持仓线上拖出 SL / TP，或在交易页点持仓的“止损/止盈”填写价格。止损以普通 Stop 单、止盈以普通 Limit 单下到交易商，数量等于当前持仓
✅ 止损和止盈同时添加时作为一组 OCO 提交（Tradovate `placeOCO`，Rithmic OCO 请求）：成交一条，交易商服务器撤销另一条，软件关闭后同样有效
✅ 已有一条、再加另一条时，软件先撤销已有那条，服务器确认撤销后，再把两条作为一组 OCO 重新提交。撤销到重新下单之间约 1–2 秒没有这条保护；若已有那条在撤销前成交，不再下单；若 OCO 被拒，提示仓位当前缺少这条保护
✅ 软件下的止损止盈带有标签，单独一条也显示为该仓位的 SL / TP，可以拖动改价；其他来源、没有关联的反向单仍显示为“待确认”
⚠️ 只经过离线测试，没有在 Tradovate、Rithmic 账户上实际下单。Rithmic 的 OCO 请求（模板 328）字段取自开源库 rithmic-rs，没有在真实服务器上发过；单独撤销 OCO 中的一条时，交易商是否同时撤销另一条也未核实，撤单后请看仓位上的保护是否还在

## English

🎉 UniTrading is now **v1.6.0**! It adds sub-account risk controls (trading lock, lot and symbol limits, order risk, and flattening one account or all of them), the docked order bar and popup order page on phones, long-press menus and market orders on the chart, a chart lock and an automatic price axis that follows the current price, stops and targets added after entry, and fill sounds.

🙏 **Special thanks**
Thanks to @XAU真爱粉 from our Discord for [PR #3](https://github.com/Mind2Money/UniTrading/pull/3): the sub-account risk controls (trading lock, lot and symbol limits, order risk, and flattening one account or all of them), the docked and popup phone order pages, the local cache of closed trades for analytics and the starred intervals sorted by length all come from it.

📖 **How to use the new features**
1. Sub-account risk controls: pick the account in the "trading account" picker at the top, then open Settings → Sub-account risk and set up "trading lock", "lot and symbol limits" and "order risk"
2. Flattening: "flatten this account" and "flatten all accounts" sit at the top of the Trading tab of the account page; the confirmation lists the accounts it will work on and the ones it skips
3. Phone order page: choose the slide-up sheet, the docked bar or the popup under Settings → Trading → Order page

🛡️ **Sub-account risk controls**
✅ Settings → Sub-account risk has three rules, kept per trading account: trading lock, lot and symbol limits, and order risk. Closing, cancelling, tightening a stop and placing an order that only reduces a position are not blocked, except while a flatten button is running; such an order is on the opposite side and, together with the orders already working on that side, can fill no more than the position, so whatever fills first the position cannot reverse. When the working exits already cover the position, another order against it is refused during a trading lock, in a blocked symbol and with order risk on, with a note to cancel or reduce those exits first, or to close the position; with order risk on only the exits placed by hand count, not the broker's own bracket legs; a stop for the position that fits in it together with the stops already working is the exception and still goes, as beside a working target of the whole position (if both fill the position reverses), but a second full stop beside a working one is no exception. A trading lock, a blocked symbol and the lot cap let any working order against the position be moved or reduced, whatever its size (during a pause a stop only tightens); one larger than the position, moved to a price that fills at once, fills and reverses the position
✅ Trading lock: once the broker's day P&L reaches the loss limit or the profit target, the account cannot open, add or loosen a stop (the working orders above can still reverse the position) until the broker reports the next trading day; a P&L that comes back, switching accounts, restarting or turning the rule off does not end it early. With "flatten and lock" a trigger also cancels the orders that could open, add or reverse and closes every position; the app keeps stops and targets until the close is confirmed
✅ The trading lock rule can be frozen until the next trading day (a Friday's until Monday) or the next Monday, and cannot be changed or turned off meanwhile; the freeze follows the broker's trading day, so changing the device clock does not end it, and once it has ended a broker snapshot that goes stale does not bring it back; when the broker's snapshot is old, freezing fetches the trading day once and the form says so while it waits; closing the form before the broker answers gives up without saving, and once the answer is in the save completes
✅ Lot and symbol limits: the cap adds up, per symbol, the largest position the working orders could leave if they all filled, in whole contracts for futures, so stops, targets and other reducing orders take none of it; the stop and target of one bracket count as the larger of the two, orders the broker does not group count in full, and the bracket of an entry that has not filled counts for nothing; over the cap a new or raised order passes only if it reduces a position, or is a new stop for it that does not raise the total, never a reversal, and any working order can still be cancelled, moved or reduced; a blocked symbol is a root (MES covers @MES and every MES month) or a single month
✅ Order risk: new opening orders get a stop and target from cash amounts; or the quantity comes from a risk amount and the stop price, with a maximum per order, and dragging the draft stop on the chart shows the quantity as you go. Orders from the chart's context menu and the trading keys follow the same rule. Orders that reduce a position get no stop or target, so one against the position that could fill more than it together with the working exits placed by hand (stops and targets the broker does not hold as its protection) is refused before it is sent, unless it is a protective stop; a scale-out beside the position's own broker bracket, one-legged or OCO, goes as usual, and the bracket legs should then be cut to what is left. Changing an order or its stop and target cannot go past the amounts either; an order against the position raised by an edit (a stop or target leg changed on its own, a stop or limit placed by hand) has to stay a reducing order or a stop no larger than the position, and the refusal says whether the order itself or the exits beside it exceed the position; a stop or target leg changed on its own is held to the amounts, for no more than the position, when it moves against the position or takes more lots, so no number of edits of that leg loosens it, and cutting it at the same price always passes; a new stop no larger than the position is not held to the amounts, so stops placed after the leg is cut or cancelled can lose more than the amount in total. On Rithmic a pending entry's stop and target move with its price
✅ The Trading tab of the account page has "flatten this account" and "flatten all accounts": orders that could open, add or reverse are cancelled first, stops and targets once the close is confirmed. "All" covers the accounts of every login in the app, Live included; view-only accounts and those of logins that are not connected are listed and skipped. Both ask for confirmation even with confirmations turned off
✅ When the risk rules saved on the device cannot be read, every account stops opening positions and Settings offers "reset risk settings", which clears all risk rules on this device once you confirm
✅ When an automatic flatten ends without being confirmed, a notice naming the account shows on whatever page is open, asking you to check the positions and orders at the broker; it stays until you close it, comes back after another notice such as an order result has taken its place, and is not cleared by switching accounts or by Android rebuilding the screen for dark mode, font size or language; a flatten cut off by such a rebuild is not sent again, and a notice says it was interrupted and its outcome needs checking
⚠️ The trading lock needs the broker's trading day, which only Tradovate reports, so it is not available on Rithmic or the local demo. On Tradovate each account with a trading lock adds a cash snapshot request about every 20 seconds, in the same request queue as orders
⚠️ The rules apply to UniTrading on this device only: the broker's website, other platforms and other devices are not limited by them, and nothing is checked or flattened while the app is closed, suspended by the system or disconnected
⚠️ With cash brackets on, Tradovate and Rithmic accounts cannot add to a contract they already hold: the app does not put a new bracket on an existing net position
⚠️ Each command is checked against the positions and orders in the broker's snapshot of that moment: on Tradovate, a second market order sent in quick succession can be checked before the first has filled, against the old position, so it can reverse a position even during a lock
⚠️ Tradovate's close request cancels the contract's working orders itself: when an automatic flatten or a flatten button does not finish, the stop may already be gone at the broker, so check there
⚠️ All of this was only checked in offline tests, never on a real Tradovate or Rithmic account; on Android it was only compiled

📱 **Phone order page**
✅ Settings → Trading → Order page offers the slide-up sheet (the default, and the order panel you had), a docked order bar and a popup
✅ The docked bar sits below the chart in portrait and beside it in landscape, so it covers no bars; the stop and target switches and prices are always shown, while the quantity slider, the reference SL / TP and the estimates wait behind "more settings", which stays open or closed as you left it, also after a restart; minimizing and restoring it keeps the form scrolled and folded as it was
✅ The popup is a dialog: ×, back or a tap beside it only puts it away and leaves the draft on the chart, where the bottom button turns into "resume order" and opens it again; back at that point, "cancel order", or the × on the draft line on the chart drops the draft
⚠️ The docked bar and the popup were only checked in desktop tests; gestures, the keyboard and system back have not been tried on an Android device

🧾 **Order confirmation**
✅ The confirmation shows the stop and target prices, or "not set"; for a market order they are marked as estimates, and the broker's notes show there too, such as Rithmic building a market order's bracket from the fill price. On a phone held sideways its content scrolls, and the reason the confirm button is off comes before the broker's notes

👤 **Account page and analytics**
✅ Funds (balance, equity, day P&L, margin) and drawdown and risk move to the Overview tab of the account page; the Trading tab has positions, orders, history and the two flatten buttons
✅ Analytics keeps the closed trades this device synchronized for 30 days, so older trades the broker no longer returns still count, in the overview's net result of the loaded history too; the header says from which day the device has them
✅ When some closed trades have no net result yet, the overview adds up the known ones as the analytics page does and says how many it left out, instead of showing "—" for the whole figure; when none is known it still shows "—", with the number of trades left out
✅ Forgetting the saved logins deletes them; disconnecting a saved login keeps them, and they count again once it reconnects, while disconnecting a login that is not saved (such as a Rithmic login without "remember") deletes them too
⚠️ Only trades synchronized while the app ran with that account selected are kept, and no older history is fetched from the broker; they are stored as plain JSON in the app's data folder, not encrypted like the saved logins

📈 **Charts**
✅ Starred intervals on the toolbar run from shortest to longest, with a current interval that is not starred at the end
✅ The chart's menu on the empty plot has "buy market" and "sell market", above the limit and stop orders; they carry the order panel's quantity and go through the same checks, order risk and confirmation as the panel, and they are left out without a quote or on a broker without market orders
✅ On phones and touch screens a long press on the chart opens the menu a right click does: on a drawing the drawing's menu, on an order line or its label the line's buttons, on the empty plot the horizontal line and the orders, with the crosshair at the finger. Where nothing opens (the price axis, an indicator pane) the press shows the crosshair and the values tooltip as before
⚠️ The long-press menus were only checked with simulated touch in desktop tests, not on an Android device; orders from the menu have not been placed on a Tradovate or Rithmic account
✅ The desktop chart can be locked with the lock at the right end of the zoom buttons below the chart. While it is locked the mouse wheel moves the chart through time instead of zooming (roll away from you for earlier bars, towards you for later ones), and the wheel over the price axis no longer zooms the prices; dragging, the buttons and pinch zoom work as before. The lock is remembered across restarts
✅ A chart no longer opens with a stretched price axis: while it shows the newest bar, the automatic axis is centred on the current price and as tall as the visible bars need, with positions, stops, targets and orders still kept in view, while overlay indicators far from the price, such as MA or BOLL, may fall outside it. It only centres again once the price moves more than a quarter of the height from the middle, so it does not jump at every tick. A price axis set by hand now comes back only with a view of the past; a view on the newest bar opens on the automatic axis, and so does a saved axis that no longer fits the market (the price moved away, or other price units)

🔊 **Fill sounds**
✅ Settings → Trading has a new "fill sounds" switch, on by default, and it says the sounds are a joke feature: a funny clip plays on an entry, take-profit or stop-loss fill
✅ Switched off, fills are silent and a clip that is playing stops at once; a fill that happened while it was off is not played after it is switched back on

🛡️ **Stop loss and take profit after entry**
✅ Tradovate and Rithmic positions can take a stop loss and take profit after entry: drag SL / TP out of the position line on the chart, or enter prices under "Stop loss / take profit" on the position in the trade page. The stop goes to the broker as a plain Stop order and the target as a plain Limit order, sized to the position
✅ Added together, they go out as one OCO pair (Tradovate `placeOCO`, the Rithmic OCO request): when one fills, the broker's server cancels the other, also while the app is closed
✅ With one line in place, adding the other cancels it first and, once the server confirms the cancel, places both lines again as one OCO pair. The position goes without that line for about 1–2 seconds in between; if it filled before the cancel, nothing more is sent; if the OCO pair is rejected, the app says the position is now missing that line
✅ Stops and targets the app places carry a tag, so a single one also shows as the position's SL / TP and can be dragged to a new price; unlinked opposite orders from elsewhere still show as unresolved
⚠️ Tested offline only; no order has been placed this way on a Tradovate or Rithmic account. The Rithmic OCO request (template 328) follows the open-source rithmic-rs library and has never been sent to a live server. Whether a broker also cancels the other line when one line of an OCO pair is cancelled on its own is not confirmed: check the position's protection after such a cancel

# v1.5.0

## 简体中文

🎉 UniTrading 更新至 **v1.5.0**！新增账户页与交易分析、账户别名、多图表窗口和面板独立窗口、秒级周期、手机双指缩放、图表右键下单和键盘下单，Android 新增内置浏览器版安装包。

🙏 **特别鸣谢**
感谢 DC 群的 @XAU真爱粉 提交 [PR #2](https://github.com/Mind2Money/UniTrading/pull/2)：账户页（总览 / 交易 / 分析）、交易分析与盈亏日历、双指缩放、手机常驻收藏周期和短横屏布局都来自这次贡献。

📖 **新功能使用说明**
1. 切换账户与别名：顶部“登录账户”“交易子账户”两个下拉直接切换；点下拉里每行右侧的铅笔可以起别名，留空保存即恢复原名，别名只存在本机
2. 账户页与交易分析：手机点底部第一个“账户”，桌面点顶栏“账户总览”；“分析”页按合约和时区（芝加哥 / 北京 / UTC）看净盈亏、胜率、盈利因子和最大回撤，点盈亏日历的某一天看当日明细，“交易明细”列出本月的平仓记录
3. 手机图表：双指张合缩放、双指移动平移；工具栏常驻显示收藏周期，点 ▾ 打开全部周期，在菜单里点星号收藏或取消
4. 秒级周期：周期菜单的“秒”分组，或在“添加自定义周期”里选“秒”作单位
5. 多图表与独立窗口（桌面）：最右侧面板栏底部的“窗口”菜单可以把面板移到独立窗口，“新建图表窗口”同时打开多个图表
6. 右键与键盘下单（桌面）：在图表空白处右键画水平线或挂限价单、突破单；在「设置 → 交易」打开“键盘下单”，点一下图表后按 W / S（或 ↑ / ↓）市价买卖
7. 绘图默认设置：绘图设置对话框底部点“保存为默认”，之后新画的同类绘图都用这份设置
8. 单独修改止损止盈：无法配对的止损止盈，在图表上拖动它的线改价，或在交易页点保护单上的铅笔修改
9. 内置浏览器版：鸿蒙等系统 WebView 不满足要求、登录不了 Tradovate 的手机，安装文件名以 `-webview.apk` 结尾的版本

📱 **Android 安装包**
✅ 发布页新增内置浏览器版 APK（文件名以 `-webview.apk` 结尾，约 95 MB，只支持 arm64）：Tradovate 登录改用 APK 自带的 GeckoView，不依赖手机的 System WebView，鸿蒙等系统 WebView 不满足要求的手机可以安装这个版本
✅ 两个版本的包名和签名相同，可以直接互相覆盖安装并保留数据；应用内更新只更新到同类型的安装包
✅ 标准版不再因为 WebView 不支持独立配置而拒绝登录，这种情况下 Demo 和 Live 共用网站登录状态；确实缺少必需能力时，提示写明缺哪一项和 WebView 版本
⚠️ 这两项改动尚未在鸿蒙真机上验证，内置浏览器版也未用真实账号登录过

📈 **图表**
✅ 修复打开画图工具栏“图标”的表情面板时报错、面板打不开的问题；表情、贴纸、符号三个标签页现在都能打开并选用
✅ 绘图的设置可以保存为工具默认：设置对话框底部新增“保存为默认”，保存样式、层级（例如斐波那契的各层数值、颜色和显示与否）、参数和可见周期，之后新画的同类绘图都用这份设置；“恢复初始默认”回到内置设置。以前的“设为此工具的默认样式”只保存颜色线型，斐波那契设好的层级在新画时会丢
✅ 十字线竖线不再停在最新一根 K 线上：指针移到 K 线之外的留白时竖线跟随指针，时间轴按周期顺推显示该位置的时间（不跳过休市时段）；图例和指标读数此时显示最近一根 K 线
✅ 自选品种的 K 线在后台预读并缓存：当前周期和收藏周期都读全 2000 根，添加品种后随即开始。打开已缓存的图表直接显示，只补取之后缺少的 K 线，指标不再随着历史一页页加载反复重绘
✅ 打开已缓存的图表时，最新一根 K 线不再先紧挨着旧的末根画出来，等缺少的 K 线补齐后一起显示
✅ 周期支持秒级：周期菜单新增“秒”分组（1、5、10、15、30 秒），自定义周期可以选“秒”作单位，例如 45 秒；秒级图表的时间轴、十字线和图例时间显示到秒
⚠️ 秒级周期在本地模拟和 Tradovate 上可用（Tradovate 用 Demo 账户实测 `@MES` 的 30 秒、5 秒、1 秒：各取到 2,000 根并持续更新）；Rithmic 的秒线请求没有在真实服务器上验证
✅ 图表视图按品种和周期记住：每屏 K 线数、位置和手动拉过的价格轴在切换品种、周期、账户、交易商、手机页面和图表窗口后不再重置，重启后照原样恢复；没看过的品种和周期沿用当前的每屏 K 线数，停在最新 K 线
✅ 在图表空白处点鼠标右键打开菜单：在该价位画水平线，或在该价位挂限价单、突破单。价位在市价下方时给出买入限价和卖出突破，在上方时给出卖出限价和买入突破；数量取下单面板当前的数量，不带止损止盈。默认弹确认框，开启“跳过交易确认”后点击即下单
⚠️ 右键菜单需要鼠标，触屏没有对应入口；菜单下单只经过离线测试，没有在 Tradovate、Rithmic 账户上实际下单

🖥️ **桌面窗口**
✅ 任意面板都能以独立窗口显示：面板栏底部新增“窗口”菜单，图表、行情、下单、交易、订单流可以分别移到独立的系统窗口，关闭窗口即收回主窗口
✅ 移出的面板不占主窗口空间，剩下的面板自动接管；行情、下单、交易图标照常收起和展开独立窗口里的面板
✅ 窗口的位置和大小会保存，重启后照原样打开；“重置布局”把全部面板收回主窗口
✅ 在独立窗口里下单、改单时，确认框和操作提示出现在当前操作的窗口里
✅ 图表可以多开：“窗口”菜单的“新建图表窗口”打开新的图表窗口，每个窗口有自己的品种和周期，最多 8 个。下单面板、行情选中和订单流跟随最后点击的图表；每个图表都能画图、拖订单线、右键下单和按键下单
✅ 同一品种的画线在所有图表间共享，撤销按品种进行，不会撤掉另一个图表后画的线
✅ 图表窗口的品种、周期、位置和大小会保存，重启后照原样打开；所有打开的图表都保持实时更新
⚠️ Rithmic 的多图表同时订阅只经过离线测试；Tradovate 对同时订阅的图表数量有上限，8 个窗口加预读是否会触到上限尚未实测

🛡️ **止损止盈**
✅ 无法配对的止损、止盈不再只能看不能改：关联待确认的单，以及因为配对不完整而锁住仓位保护的止损止盈，可以逐张单独修改。图表上拖动这条线改价格；交易页的“单独改单”（持仓行里是保护单上的铅笔按钮）打开对话框，Tradovate 可同时改数量，Rithmic 只改价格
✅ 只改这一张单，同一仓位的其他单不变；止损价越过当前报价会立即触发、限价越过会立即成交，这两种直接拒绝；数量大于当前净仓时提示全部成交后会反向持仓。沿用交易确认设置
⚠️ 只经过离线测试，没有在 Tradovate、Rithmic 账户上实际改过单；Rithmic 单独改括号子单数量未开放，等待在服务器上核实

⌨️ **键盘下单**
✅ 「设置 → 交易」新增“键盘下单”开关，默认关闭。开启后在 W / S 和 ↑ / ↓ 两组按键里选一组：点一下图表后，按 W（或 ↑）市价买入，按 S（或 ↓）市价卖出，数量取下单面板，不带止损止盈
✅ 沿用交易确认设置：默认按键后弹确认框，Enter 确认、Esc 取消；开启“跳过交易确认”后按键即成交，按住不放只下一单
✅ 图例显示当前按键、数量和是否需要确认；键盘焦点不在图表上时（例如正在输入框里打字）按键不下单，图例提示先点一下图表
⚠️ 只经过离线测试，没有在 Tradovate、Rithmic 账户上用按键实际下单；中文输入法开启时字母键是否生效尚未验证
⚠️ Tradovate 的预读不限速：首次补齐时会超过服务器每小时 180 次图表请求的额度，超出期间打开图表和主力核实的每次请求多等 1～3 秒；手机和桌面各自预读，共用这份额度。Rithmic 的预读只在离线测试中验证过

👤 **账户页**
✅ 手机底部导航的“交易”改为“账户”，放在首位，分总览、交易、分析三页；持仓与订单在“交易”页，点图表下方的持仓栏直接进入
✅ 手机和桌面顶部新增“登录账户”“交易子账户”两个下拉，直接切换交易商和子账户；切换时关闭上一个账户未提交的下单、改单和确认框
✅ 交易分析：按芝加哥、北京时间或 UTC 的平仓自然日统计当前子账户已加载的平仓记录，显示净盈亏、胜率、盈利因子、平均盈亏比、已实现最大回撤、累计或每日盈亏图，以及带周汇总的每日盈亏日历；缺少净盈亏的记录标为“待确认”，不按零计
✅ 桌面端点工具栏的“账户总览”在对话框里打开这三页，工作区和独立窗口保持不动
✅ 登录账户和交易子账户可以起别名：点顶部两个下拉里每行右侧的铅笔输入，留空即恢复原名。别名显示在各处账户名的位置，下拉和总览里原名作为小字保留，实时盈亏通知的标题也用别名；别名只保存在本机，手机和电脑各自设置
✅ 桌面右侧栏去掉了交易子账户面板，行情列表占满侧栏，收起按钮移到行情标题栏右端；之前保存的布局照常恢复
⚠️ 统计只覆盖券商已返回的历史记录，自然日不等于交易所结算日

📱 **手机图表**
✅ 图表支持双指缩放和平移，以两指中心为锚点；按下第二根手指时取消尚未提交的订单线和画线拖动，不会因此改单或下单
✅ 手机图表工具栏常驻显示收藏周期，用 15s、5m、1h、D 这样的短标签，其余周期仍在下拉菜单里；桌面工具栏的收藏周期也改用短标签
✅ 横屏的矮屏幕改用窄侧边导航和更矮的交易栏，给 K 线留出更多高度；旋转屏幕不再重建界面，图表缩放和当前页面保持不变
⚠️ 双指手势只在桌面测试里用模拟触摸验证过，没有在真机上试过

## English

🎉 UniTrading **v1.5.0** is out! It adds the account page with trade analytics, account aliases, several chart windows and panels in windows of their own, second intervals, pinch zoom on phones, orders from the chart's context menu and from the keyboard, and a built-in browser APK for Android.

🙏 **Special thanks**
Thanks to @XAU真爱粉 from our Discord for [PR #2](https://github.com/Mind2Money/UniTrading/pull/2): the account page (overview / trading / analytics), the trade analytics and P&L calendar, pinch zoom, the starred intervals on the phone toolbar and the short landscape layout all come from it.

📖 **How to use the new features**
1. Switching accounts and aliases: the "login" and "trading account" pickers at the top switch directly; the pencil at the end of a row gives it an alias, saving it empty brings the broker's name back, and aliases stay on the device
2. Account page and analytics: on a phone tap "Account", first in the bottom bar; on the desktop click "account overview" in the toolbar. The Analytics tab shows net P&L, win rate, profit factor and maximum drawdown by contract and time zone (Chicago / Beijing / UTC); tap a day in the P&L calendar for its trades, and "trades" lists the month's closed trades
3. Phone charts: pinch to zoom, move two fingers to pan; the toolbar shows the starred intervals, ▾ opens all intervals, and the star in that menu adds or removes one
4. Second intervals: the "seconds" group of the interval menu, or "seconds" as the unit of a custom interval
5. Several charts and panel windows (desktop): the "windows" menu at the bottom of the right-hand panel rail moves a panel into a window of its own, and "new chart window" opens another chart
6. Context-menu and keyboard orders (desktop): right-click empty chart space to draw a horizontal line or place a limit or stop order; turn on "keyboard trading" in Settings → Trading, click the chart, and W / S (or ↑ / ↓) buy and sell at the market
7. Drawing defaults: "save as default" at the bottom of a drawing's settings dialog makes every new drawing of that tool start with them
8. Changing a stop or target on its own: drag the line of a stop or target that cannot be paired, or use the pencil on its protection chip in the trade list
9. Built-in browser APK: on phones whose System WebView cannot sign in to Tradovate, such as HarmonyOS, install the APK whose name ends in `-webview.apk`

📱 **Android packages**
✅ The release page now has a built-in browser APK (file name ending in `-webview.apk`, about 95 MB, arm64 only): the Tradovate login uses GeckoView inside the APK instead of the phone's System WebView, for phones whose System WebView falls short, such as HarmonyOS devices
✅ Both packages share one package name and signature, so either installs over the other and keeps your data; in-app updates stay on the same kind of package
✅ The standard package no longer refuses to log in when the WebView lacks separate profiles; Demo and Live then share the website's login state. When a required capability really is missing, the message names it and the WebView version
⚠️ Neither change has been run on a real HarmonyOS phone yet, and the built-in browser package has not logged in with a real account

📈 **Charts**
✅ Fixed an error that kept the icon picker of the drawing toolbar from opening; its emoji, sticker and symbol tabs now open and can be picked from
✅ Drawing settings can be saved as the tool's default: "save as default" at the bottom of the settings dialog keeps the look, the levels (a Fibonacci's values, colours and which ones show, for example), the parameters and the intervals shown on, and every new drawing of that tool starts with them; "restore built-in defaults" goes back. The old "set as this tool's default style" kept colours and lines only, so a Fibonacci's levels were lost on the next one
✅ The crosshair's vertical line no longer stops at the newest bar: in the empty space beside the bars it follows the pointer, and the time axis shows that position's time, counted on at one interval per bar without skipping closed hours; the legend and indicator readouts then show the nearest bar
✅ Watch-list history is read ahead and cached in the background: the shown interval and the starred intervals each get their full 2,000 bars, starting as soon as a symbol is added. A cached chart opens at once and only fetches the bars since, so indicators no longer redraw as history arrives page by page
✅ A cached chart no longer draws the newest bar right next to its old last bar first; it appears together with the bars in between
✅ Second intervals: the interval menu has a new "seconds" group (1, 5, 10, 15, 30 seconds), and a custom interval can take seconds as its unit, 45 seconds for example; on a second chart the time axis, the crosshair and the legend show times to the second
⚠️ Second intervals work on the local demo and on Tradovate (checked on a Demo account with `@MES` at 30, 5 and 1 seconds: 2,000 bars each, then live updates); the Rithmic request for second bars has not been checked against a real server
✅ The chart remembers its view per symbol and interval: the bars per screen, the position and a price axis zoomed by hand no longer reset when you change symbol, interval, account, broker, phone page or chart window, and come back after a restart; a symbol and interval not seen before keeps the current bars per screen and opens on the newest bar
✅ A right click on empty chart space opens a menu: draw a horizontal line at that price, or place a limit or stop order there. Under the market it offers a buy limit and a sell stop, over it a sell limit and a buy stop; the order takes the order panel's quantity and carries no stop or target. A confirmation appears unless you turned confirmations off
⚠️ The menu needs a mouse and has no touch equivalent; orders from it were only checked in offline tests, never placed on a Tradovate or Rithmic account

🖥️ **Desktop windows**
✅ Any panel can live in a window of its own: the panel rail has a new "windows" menu that moves the chart, market list, order ticket, trade list or order flow into a separate system window; closing the window brings the panel back
✅ A panel that left takes no room in the main window and the others take over its space; the rail's market, order and trade icons still fold and unfold a panel in its own window
✅ Window positions and sizes are saved and restored on the next start; "reset layout" brings every panel back into the main window
✅ When you trade from a separate window, confirmations and notices appear in the window you are working in
✅ Several charts at once: "new chart window" in the windows menu opens another chart window with its own symbol and interval, up to 8. The order panel, the market list's selection and the order flow follow the chart you clicked last; every chart can draw, drag order lines, and place orders from its context menu or the trading keys
✅ A symbol's drawings are shared by every chart that shows it, and undo works per symbol, so it never takes back a line another chart drew since
✅ Chart windows keep their symbol, interval, position and size and open as they were on the next start; every open chart stays live
⚠️ Several live charts at once on Rithmic were only checked in offline tests; Tradovate caps simultaneous chart subscriptions, and whether 8 windows plus the read-ahead reach that cap has not been tried

🛡️ **Stops and targets**
✅ A stop or target that cannot be paired is no longer read-only: an order whose link is unconfirmed, and a stop or target whose incomplete pairing locks the position's protection, can each be changed on its own. Drag its line on the chart to change the price; "change on its own" in the trade list (a pencil on the protection chip in a position row) opens a dialog where Tradovate also takes a new quantity and Rithmic the price only
✅ Only that order changes, nothing else on the position; a stop past the current quote would trigger at once and a limit past it would fill, so both are refused; a quantity above the net position warns that a full fill reverses it. Confirmations follow your setting
⚠️ Checked in offline tests only, never on a Tradovate or Rithmic account; changing the size of a Rithmic bracket leg on its own stays off until it is checked against the server

⌨️ **Keyboard trading**
✅ Settings → Trading has a new "keyboard trading" switch, off by default. Turn it on and pick one pair of keys, W / S or ↑ / ↓: after a click on the chart, W (or ↑) buys at the market and S (or ↓) sells at the market, with the order panel's quantity and no stop or target
✅ It follows the confirmation setting: by default a key opens the confirmation, which Enter confirms and Esc dismisses; with confirmations off the order goes out at once, and a held key sends one order only
✅ The legend shows the keys, the quantity and whether a key still asks; while the keyboard focus is not on the chart, for example while you type in a field, the keys place nothing and the legend says to click the chart first
⚠️ Only checked in offline tests; no order was placed with a key on a Tradovate or Rithmic account. Whether the letter keys work while a Chinese input method is on has not been checked
⚠️ Tradovate read-ahead is not rate-limited: the first fill goes over the server's 180 chart requests per hour, and while it is over, every chart request waits 1–3 seconds more, including opening a chart and leader checks; phone and desktop each read ahead and share that budget. Rithmic read-ahead has only been verified in offline tests

👤 **Account page**
✅ On phones the bottom bar's "Trade" becomes "Account", placed first, with Overview, Trading and Analytics tabs; positions and orders are under Trading, and the position bar under the chart opens it directly
✅ Phones and the desktop toolbar get two pickers at the top, for the login and for the trading child account; switching closes the previous account's unsent order, edits and confirmations
✅ Analytics: the current child's loaded closed trades by calendar day in Chicago, Beijing or UTC time, with net P&L, win rate, profit factor, average win/loss, realized maximum drawdown, a cumulative or daily P&L chart and a daily P&L calendar with weekly totals; a trade without net P&L is marked as pending and not counted as zero
✅ On the desktop, the toolbar's account overview button opens these tabs in a dialog; the workspace and any panel windows stay as they are
✅ Logins and trading accounts can have aliases: tap the pencil at the end of a row in either top picker and type one; an empty alias brings the broker's name back. The alias shows wherever the name did, with the broker's name in small text in the pickers and the overview, and it titles the live P&L notice; aliases stay on the device, so phone and desktop are set separately
✅ The desktop side column no longer has the accounts panel: the market list fills it, and its fold button sits at the right end of its title row; saved layouts open as before
⚠️ The statistics cover only the history the broker returned, and a calendar day is not an exchange settlement day

📱 **Phone charts**
✅ Pinch to zoom and pan the chart around the point between the two fingers; a second finger cancels an order-line or drawing drag that was not committed, so it changes no order
✅ The phone chart toolbar shows the starred intervals inline with short labels such as 15s, 5m, 1h and D, and the other intervals stay in the dropdown; the desktop toolbar's starred intervals use the same short labels
✅ Short landscape screens get a narrow side rail and a lower trade bar, leaving more height for the bars; turning the phone no longer rebuilds the screen, so the chart zoom and the open page stay
⚠️ The pinch gesture was only checked with simulated touches in desktop tests, not on a phone

# v1.4.0

## 简体中文

🎉 UniTrading 更新至 **v1.4.0**！新增 Rithmic 订单流、TradingView 式画图工具、账户回撤显示、macOS 版和应用内更新，界面配色按 Material 3 重做。

📊 **Rithmic 订单流**
✅ 登录 Rithmic 后可从“订单流”打开足迹（Bid × Ask）、逐笔成交量分布（POC / 70% 价值区）、Delta / CVD、可按成交量过滤并暂停显示的逐笔成交，以及只读多档 DOM
✅ 桌面订单流入口移到最右侧图标栏，首次展开占原主图区右半边，可拖动分隔线调宽并保存比例，双击恢复对半；下单、行情和下方交易面板继续显示，手机使用独立分析页
✅ 右侧面板栏改用 Material 3 原生导航栏和药丸形导航项，常显“行情、下单、交易、订单流”文字，扩大点击区域和按钮间距
⚠️ 统计来自本次订阅的最近 20,000 笔真实成交，不含历史回补；重连、换合约或关闭分析后重新打开会重新采集。未知主动方向单独列出，不计入 Delta；DOM 取决于账户 L2 行情权限，尚未核实边界的聚合快照降级为买一卖一

🎨 **配色**
✅ 按 Material 3 的色调体系重做主色：按钮、选中项和强调文字都由同一个主题强调色推导，换强调色时整套颜色一起变化，文字和按钮对比度都达到可读标准
✅ TradingV 主题保留 TradingView 的黑色页面、#2962FF 主按钮和青红 K 线；面板从纯黑改为 #131313，文字提亮，选中行上的红绿价格、工具栏和各类按钮都更容易看清

💻 **macOS 版**
✅ 新增 Apple Silicon 的 macOS 桌面版（macOS 13 及以上），支持 Tradovate 和 Rithmic 登录，授权记录和已保存登录由系统钥匙串保护
⚠️ 尚未在真实 Mac 上实测登录。安装包未经 Apple 公证，解压后先执行 `xattr -cr UniTrading.app` 再打开
⚠️ macOS 登录窗口不能记住密码，每次需要手动输入

📈 **行情**
✅ 编辑自选改为拖动调整顺序，去掉上移、下移箭头：鼠标按住整行，手机按住左侧手柄，拖到列表边缘自动滚动

📈 **图表**
✅ 新增 TradingView 式左侧画图工具栏：趋势线、通道、干草叉、斐波那契、江恩、形态、艾略特波浪、周期、多空仓位、仓位预测、成交量分布图、测量、几何形状、文字注释和图标，共一百多个工具
✅ 磁铁吸附、继续绘图、锁定、隐藏、一键移除、框选放大和收藏工具都可用；绘图按品种保存，切换周期和重启后保留
✅ 选中绘图后可拖动控制点或整体移动，修改颜色、线宽、层级和坐标；支持撤销重做、克隆、复制粘贴和快捷键（Alt+T、Alt+F 等）
⚠️ 绘图只能放在 K 线主窗格；帖子和观点只保存链接与文字；贴纸用表情符号代替；手机上点图表左缘的小按钮展开工具栏
✅ 指标设置同样改为拖动调整顺序：鼠标按住卡片标题，手机按住左侧手柄
✅ 持仓、挂单和下单草稿的线上标签改为 TradingView 式样：方向、盈亏、TP、SL、数量、类型和 ×，标签落在价格线上，价格轴读数用同一颜色；持仓和止损止盈的预计盈亏保留
✅ 按住标签上的 TP、SL 按钮拖到目标价即可添加止盈止损，草稿松手回填表单，持仓和挂单松手后确认
✅ 点 × 可直接在图表上平仓、撤单或撤销止损止盈，“关联待确认”的单也能撤；在线上点鼠标右键打开同样的操作。默认仍弹确认框，开启“跳过交易确认”后点击即执行
✅ 下单数量上限先显示上次的结果，后台向服务器核对，只有服务器返回不同的上限才更新，不再卡在“计算中”，也不再过期后要手动刷新
✅ 修复 Tradovate 数量上限经常算不出来的问题：加载合约资料时查询不再被作废；数量上限未知时手数加减按钮仍可用
✅ 支持鼠标滚轮：在图表上滚动缩放时间，在右侧价格轴上滚动缩放价格，Shift + 滚轮或触控板横向滑动平移
⚠️ Tradovate 和 Rithmic 的已有持仓、挂单仍不能在这里新增或删除止损止盈，线上不显示 TP、SL 按钮，下单草稿不受影响

🛡️ **账户风险**
✅ 新增“可回撤额度”和“危险度”：桌面底部资金栏和手机交易页显示当前账户离爆号线还有多少，危险度是一个完整回撤额度已用掉的比例，50% 起变橙、80% 起变红，TDD 和 EOD 账户用同一尺度
✅ Tradovate 账户按账户模块的爆号线（Drawdown Auto Liq Level）计算，随行情更新
✅ Rithmic 账户优先取券商上报的自动平仓线，随风控推送更新；鼠标移到这两项上可以看到这条线怎么来的，手机上直接写在卡片里
⚠️ 拿不到规则或账户净值时显示“—”，不按 0 计算；这些数字只用于显示，不影响下单
⚠️ Rithmic EOD 账户没有上报爆号线时由程序计算，亏损日之后额度会偏大，请以券商后台或 R | Trader 为准；本版尚未用真实账户核对 UniTrading 显示的数字

📋 **交易列表**
✅ 已识别的止损、止盈单合并到所属持仓下，未成交挂单匹配到的止损、止盈合并到该挂单下，显示价格、数量和预计盈亏并可直接撤销，不再各占一行

🎯 **止损止盈校验**
✅ 下单前按订单方向校验：买入单止损须低于入场价、止盈须高于入场价，卖出单相反；市价单的止损止盈要越过买卖价差，不再放行落在价差内或正好等于成交价的止盈
✅ 修改持仓止损止盈时先校验价格：大于零、符合最小变动单位、在当前可平仓价的正确一侧、止损止盈不颠倒，不合格时直接提示原因，确认按钮不可用，跳过确认时拖线也不会发出
✅ 持仓后只和当前可平仓价比较，与入场价无关，推保本、把止损推过入场价不受影响；每次只校验改动的那条线，价格已贴近止盈时也能移动止损

🖥️ **桌面布局**
✅ 面板改为 TradingView 式排版：图表在左、交易区在图表下方，下单面板和行情面板依次排在右侧，最右侧一列图标
✅ 行情、下单、交易三个面板都能收起和展开，图表自动占用空出的位置，状态本机保存；可以用最右侧图标，也可以用面板自己的收起按钮。“重置布局”恢复全部面板
✅ 下单、账户、行情、交易、订单流面板的标题统一了字号、行高、左边距和按钮样式

🆕 **应用内更新**
✅ 「设置 → 关于与更新」可以检查新版本并一键更新：Windows 自动下载、校验、替换程序并重启；Android 下载校验后打开系统安装界面，第一次需要在系统页面允许本应用安装未知应用
✅ 账户有持仓或挂单、或账户状态无法确认时不能更新；登录状态和设置保存在程序目录之外，更新不受影响
✅ 从源码运行、本地模拟和 macOS 版也能在「关于与更新」里手动检查，发现新版本时打开发布页下载；检查后显示检查时间，并加了更新日志入口
⚠️ 这个版本本身仍要手动下载一次，之后的版本才能在应用内更新

## English

🎉 UniTrading is now **v1.4.0**! It adds Rithmic order flow, TradingView-style drawing tools, account drawdown readouts, a macOS build and in-app updates, and rebuilds the interface colors on Material 3.

📊 **Rithmic order flow**
✅ Added Bid × Ask footprints, traded volume profiles with POC and a 70% value area, Delta / CVD, a filterable tape with display pause, and a read-only multi-level DOM after Rithmic sign-in
✅ Desktop order flow opens from the right icon rail, initially using half of the original chart area. Drag the divider to resize and save the ratio, or double-click to restore equal halves; the order ticket, market sidebar and terminal stay visible, while phones use a dedicated analysis page
✅ The right panel rail now uses the native Material 3 navigation rail with pill indicators and persistent Market, Order, Trades and Order Flow labels, larger click targets and more spacing
⚠️ Statistics cover the most recent 20,000 live trades collected during this subscription, without historical backfill. Reconnecting, changing contracts, or reopening analysis starts a new window. Unknown aggressor volume stays separate from Delta; DOM requires account L2 permissions, and aggregated snapshots with unverified replacement boundaries fall back to top of book

🎨 **Colors**
✅ Rebuilt the main colors on Material 3 tones: buttons, selections and accent text all derive from one theme accent, so changing it recolors the whole set, and text and buttons meet readable contrast
✅ The TradingV theme keeps TradingView's black page, #2962FF main buttons and teal and red candles. Panels move from pure black to #131313, text is brighter, and red and green prices on selected rows, the toolbars and every kind of button are easier to read

💻 **macOS**
✅ New macOS desktop build for Apple Silicon (macOS 13 or later) with Tradovate and Rithmic sign-in; the license record and saved logins are protected by the system keychain
⚠️ Sign-in has not been run on a real Mac yet. The package is not notarized by Apple: after unzipping, run `xattr -cr UniTrading.app` before opening it
⚠️ The macOS login window cannot remember passwords; enter yours each time

📈 **Market watch**
✅ Reorder the watch list by dragging instead of up and down arrows: hold a row with the mouse or its left handle on a phone; the list scrolls when you reach its edge

📈 **Charts**
✅ New TradingView-style drawing toolbar on the left of the chart: trend lines, channels, pitchforks, Fibonacci, Gann, patterns, Elliott waves, cycles, long and short positions, forecasts, volume profiles, measuring, shapes, text notes and icons, over a hundred tools
✅ Magnet, keep drawing, lock, hide, remove all, zoom to a range and favourite tools all work; drawings are saved per symbol and survive interval changes and restarts
✅ Select a drawing to drag its points or move it whole and change colour, width, levels and coordinates; undo, redo, clone, copy, paste and shortcuts such as Alt+T and Alt+F are supported
⚠️ Drawings go on the main price pane only; posts and ideas keep your link and text; stickers are emoji; on phones the toolbar opens from the small tab on the left edge of the chart
✅ Indicator settings reorder the same way: hold a card title with the mouse or its left handle on a phone
✅ Position, order and draft lines now carry TradingView-style tags: side, profit or loss, TP, SL, quantity, type and ×, sitting on the price line with a matching price-axis tag; estimated results for positions, stops and targets are kept
✅ Hold a tag's TP or SL button and drag it to a price to add a target or stop: a draft fills the form on release, a position or order asks for confirmation
✅ Press × to close a position, cancel an order or cancel a stop or target right on the chart, including orders marked as unresolved; right-click a line for the same actions. A confirmation still appears unless you turned confirmations off
✅ The order quantity limit now shows the last known value at once and checks the server in the background, changing only when the server answers differently; no more waiting on "calculating" or refreshing an expired limit
✅ Fixed the Tradovate quantity limit often failing to resolve: loading contract details no longer voids the check, and the quantity buttons work while the limit is unknown
✅ Mouse wheel support: scroll over the chart to zoom time, over the price axis to zoom price; Shift + wheel or a sideways trackpad swipe pans
⚠️ Tradovate and Rithmic positions and orders still cannot add or remove stops and targets here, so their lines show no TP and SL buttons; order drafts are not affected

🛡️ **Account risk**
✅ New drawdown room and danger figures: the desktop status bar and the phone trade page show how far the current account stands above its drawdown line; danger is the share of one full trailing amount used, turning orange at 50% and red at 80%, on one scale for TDD and EOD accounts
✅ Tradovate accounts use the Accounts module's Drawdown Auto Liq Level and follow the market
✅ Rithmic accounts use the auto-liquidation line the broker reports first and follow its risk updates; hover either figure to see how the line comes about, and on phones it is written on the card
⚠️ Without a rule or an account value these show "—", never 0; they are display figures only and do not affect orders
⚠️ When a Rithmic EOD account reports no line the app computes one, and the room reads too large after a losing day, so go by your broker's back office or R | Trader; the figures UniTrading shows have not been checked on a real account yet

📋 **Trade list**
✅ Recognized stop and target orders now sit under their position, and those matched to an unfilled order under that order, with price, quantity and estimated result; each can be cancelled there instead of taking a row of its own

🎯 **Stop and target checks**
✅ Orders are checked against their direction before placement: a buy's stop must be below its entry and its target above it, and the reverse for a sell; market orders must clear the bid/ask spread, so a target inside the spread or exactly at the fill price is refused
✅ Editing a position's stop or target now checks the price first: above zero, on the tick grid, on the right side of the current closing price, and with stop and target not crossed. A failure shows its reason and disables the confirm button, and a chart drop with confirmations off is not sent
✅ After a fill only the current closing price counts, never the entry, so moving a stop to break-even or past it is unaffected; only the line being changed is checked, so a stop can still move when price is right at the target

🖥️ **Desktop layout**
✅ Panels now follow TradingView's arrangement: the chart on the left with the trade list under it, then the order panel, the market panel and a column of icons on the right
✅ The market, order and trade panels can each be folded and reopened, from the icon column or their own button; the chart takes the free space and the state is remembered. Reset layout brings every panel back
✅ The order, account, market, trades and order flow panels now share one title size, row height, left inset and button style

🆕 **In-app updates**
✅ Settings → About & updates checks for a new version and updates in one press: Windows downloads, verifies, replaces the program and restarts; Android downloads, verifies and opens the system installer, and the first time asks you to allow installs from this app in a system screen
✅ Updating is unavailable while an account has open positions or working orders, or while its state cannot be confirmed; logins and settings live outside the program folder and are not affected
✅ Source runs, the local demo and the macOS build can also check by hand under About & updates and open the release page when a newer version exists; a check now shows when it ran, and there is a changelog link
⚠️ This version itself still needs one manual download; later versions can update in the app

# v1.3.1

## 简体中文

🎉 UniTrading 更新至 **v1.3.1**！修复 Lucid 账户连接失败，图表加载历史时不再移动。

🔌 **Tradovate 连接**
✅ 修复 Lucid 账户登录后提示 “Tradovate HTTP 307”、读不到账户的问题：现在按 Tradovate 登录回复指定的服务器连接账户和行情
⚠️ 此修复尚未用真实 Lucid 账户实测；更新后仍提示 307 时，请重新登录一次

📈 **图表**
✅ 加载历史 K 线时图表停在最新时间：Tradovate 首次打开时第一页整页出现，不再从右往左逐批长出来，更早的 K 线在后台并入
✅ 平移或缩放过的图表不再随历史加载一路往回退，始终停在原来的 K 线上

## English

🎉 UniTrading is now **v1.3.1**! It fixes Lucid accounts failing to connect and keeps charts in place while history loads.

🔌 **Tradovate connection**
✅ Fixed Lucid logins stopping at "Tradovate HTTP 307" with no accounts: accounts and market data now connect to the servers Tradovate's login reply names
⚠️ This fix has not been run with a real Lucid account yet; if 307 still appears after updating, sign in again

📈 **Charts**
✅ Charts stay on the latest time while history loads: on Tradovate the first page appears whole instead of growing from right to left, and older bars join in the background
✅ A panned or zoomed chart no longer drifts back as history loads; it stays on the same bars

# v1.3.0

## 简体中文

🎉 UniTrading 更新至 **v1.3.0**！新增 Rithmic 接入，全部界面按 Material 3 重做。

🔌 **Rithmic 接入**
✅ 开启应用内 Rithmic 登录入口（登录页、设置、账户切换、空合约页）。Paper Trading 的登录、账户、行情、图表、下单、改单、撤单和成交配对已实测
✅ 连续合约优先使用账户可交易的交易所，失败时换下一个交易所，错误信息写明交易所；连续图表跟随到期合约的实时逐笔和 K 线
✅ 连续图表改用服务器的主力连续历史，包含换月前的 K 线，不再只显示当前到期合约自身稀疏的早期行情
✅ 登录 Rithmic 后自动加载 MES，直接进入主界面；随后在后台同步账户可交易的产品，添加品种时不用先搜索
✅ 登录时同步按产品的下单上限、手续费和最近 7 天成交，下单预估显示双边手续费
✅ 余额改用服务器的账户余额，当日盈亏和手续费计入余额；净值和浮动盈亏不重复计算
✅ 修复下单后误报“交易结果未知”并暂停交易的问题
✅ 修复持仓和挂单的止损、止盈、入场价无法修改的问题
✅ 历史列表配对入场和止盈、止损、平仓成交，并显示手续费和净盈亏；止损触发时播放止损音效

📈 **图表**
✅ Tradovate 的分钟周期图表向更早翻页，1 小时、4 小时图不再只有约一个月的 K 线；首次打开时 K 线会分批出现
✅ 点击持仓、历史或挂单时，图表保持在连续合约上，不再切到历史更短的到期合约；此前停在到期合约上的图表，启动后自动回到连续合约

🎨 **界面重做（Material 3）**
✅ 全部页面按 Material Design 3 重做：统一配色、字号、圆角和官方图标，新增应用图标与桌面窗口图标
✅ 手机每页只有一条顶栏；横屏和平板改用侧边导航，图表高度增加
✅ 手机和桌面共用一套下单表单；下单与确认按钮随买卖方向变色，确认按钮直接写“买入”“平仓”“撤单”
✅ 订单类型统一为市价、限价、突破；“保护价”改称止损/止盈
✅ 交易页的资金数据全部显示在摘要卡片内，不用再横向滚动
✅ 行情列表表头只显示一次，账户状态改用文字标签
✅ 设置页重新分组，桌面删除重复的“切换账户”弹窗
✅ 修复手机上 Rithmic 登录弹窗看不到 Live 选项的问题，修复持仓编号显示成 `#ition:9` 的问题

⚠️ **注意**
UniTrading 尚未通过 Rithmic 的 conformance 审核，Rithmic 的平仓尚未实测，请先用 Paper Trading 账户确认行为
Tradovate 的真实下单、改价和成交回执尚未联调。Live 模式会把你确认的指令发送到真实账户，请先用 Demo 账户确认行为

⬇️ **安装**
1️⃣ Windows x64：下载 `UniTrading-1.3.0-windows-x64.zip`，解压后运行 `UniTrading\UniTrading.exe`。需要 WebView2 Runtime；安装包没有代码签名，首次运行可能出现 SmartScreen 提示
2️⃣ Android 8.0 及以上：下载 `UniTrading-1.3.0-*.apk` 并安装
3️⃣ Windows 的登录状态和设置保存在 `%LOCALAPPDATA%\UniTrading`，替换程序目录不受影响

## English

🎉 UniTrading is now **v1.3.0**! It adds Rithmic and rebuilds every screen on Material 3.

🔌 **Rithmic**
✅ Enabled the in-app Rithmic login entries (welcome, settings, account switcher, empty-catalog). Paper Trading login, accounts, market data, charts, placing, modifying and cancelling orders and fill pairing are verified
✅ Continuous contracts prefer an exchange the account can trade, fall back to the next one, and name the exchange in errors; continuous charts follow live trades and bars of the dated contract
✅ Continuous charts use the server's front-month history, including bars from before each roll, instead of only the current dated contract and its thin early trading
✅ Rithmic login loads MES automatically and opens on the main screen, then syncs the account's tradable products in the background so the add-symbol list needs no search
✅ Login also syncs per-product order limits, commissions and the last 7 days of fills; order previews show the round-trip commission
✅ The balance now uses the server's account balance, including the day's P&L and fees, and equity and floating P&L count open P&L once
✅ Fixed orders being reported as "result unknown", which paused trading
✅ Fixed stop-loss, take-profit and entry price edits being refused on positions and pending orders
✅ History pairs entries with their take-profit, stop-loss and closing fills, with fees and net P&L; a triggered stop plays the stop-loss sound

📈 **Charts**
✅ Tradovate intraday charts now page further back, so 1-hour and 4-hour charts no longer stop at about a month of bars; on first open the bars arrive in batches
✅ Selecting a position, trade or order keeps the chart on the continuous contract instead of switching to the dated contract and its shorter history; a chart left on a dated contract returns to the continuous contract at startup

🎨 **Material 3 redesign**
✅ Every screen follows Material Design 3: one colour, type and shape system, official icons, plus an app icon and a desktop window icon
✅ Each phone page has a single top bar; landscape phones and tablets use side navigation, which gives the chart more height
✅ Phone and desktop share one order form; the order and confirm buttons take the side's colour, and confirmations name the action: Buy, Close, Cancel
✅ Order types are consistently Market, Limit and Stop, and stop loss / take profit replaces the old protection-price wording
✅ All account figures fit on a summary card on the trade page, with no sideways scrolling
✅ The quote list shows its column captions once, and account states are text labels
✅ Settings are regrouped, and the desktop's duplicate account-switch dialog is gone
✅ Fixed the Live option being unreachable in the Rithmic login dialog on phones, and position ids showing as `#ition:9`

⚠️ **Heads up**
UniTrading has not passed Rithmic's conformance review, and closing a position on Rithmic has not been verified live, so check behaviour on a Paper Trading account first
Real Tradovate order placement, changes and fill receipts have not been verified against a live server. Live mode sends the commands you confirm to a real account, so check behaviour on a Demo account first

⬇️ **Install**
1️⃣ Windows x64: download `UniTrading-1.3.0-windows-x64.zip`, extract it and run `UniTrading\UniTrading.exe`. WebView2 Runtime is required; the package is not code-signed, so SmartScreen may warn on first launch
2️⃣ Android 8.0 or later: download and install `UniTrading-1.3.0-*.apk`
3️⃣ Windows logins and settings live in `%LOCALAPPDATA%\UniTrading`, so replacing the program folder keeps them

# v1.2.0

## 简体中文

🎉 UniTrading 更新至 **v1.2.0**！这是首个带 tag 的发布，包含 1.1.4 之后的全部改动。

🔐 **授权**
✅ 首次启动需要输入授权码，验证通过后本机保持授权

📈 **行情与图表**
✅ 新增 MT5 风格行情列表，可搜索添加连续合约、从列表切换图表，重开时回到上次的图表
✅ 新增连续合约图表，按上一完整交易日成交量确定主力，下单路由到实际到期合约
✅ 支持多指标叠加与副图（MA、EXPMA、BOLL、RSI、MACD、KDJ、ATR），参数和颜色可调
✅ 新增虚线十字线，坐标轴显示所指价格和时间
✅ K 线保存到本地，重启后恢复并自动补齐缺失区间

🧾 **交易**
✅ 可在图表上拖动草稿和挂单的止损止盈，下单面板增加保护单勾选和数量滑块
✅ 新增“跳过交易确认”设置
✅ 历史按服务器配对显示，含费用，可展开查看入场和出场明细；显示券商报告的今日盈亏
✅ 入场、止盈、止损成交时播放音效

📱 **手机与桌面**
✅ 手机改为图表优先布局，下单面板可折叠、可最小化
✅ Android 新增实时盈亏常驻通知，Android 16 起支持 Live Update
✅ 桌面面板可拖动调整大小，设置页重新分组

🛠️ **问题修复**
✅ 修复数量滑块未按账户可用容量计算的问题
✅ 修复连续合约解析不稳定，以及解析期间行情列表没有价格的问题
✅ 指令已受理但结果未确认时，改为按服务器状态对账，不重复提交；设置页新增“同步账户”

🚧 **开发中**
Rithmic 登录尚未完成，本版本隐藏了全部入口

⚠️ **注意**
真实下单、改价和成交回执尚未联调。Live 模式会把你确认的指令发送到真实账户，请先用 Demo 账户确认行为

⬇️ **安装**
1️⃣ Windows x64：下载 `UniTrading-1.2.0-windows-x64.zip`，解压后运行 `UniTrading\UniTrading.exe`。需要 WebView2 Runtime；安装包没有代码签名，首次运行可能出现 SmartScreen 提示
2️⃣ Android 8.0 及以上：下载 `UniTrading-1.2.0-*.apk` 并安装
3️⃣ Windows 的登录状态和设置保存在 `%LOCALAPPDATA%\UniTrading`，替换程序目录不受影响

## English

🎉 UniTrading is now **v1.2.0**! This is the first tagged release and includes everything since 1.1.4.

🔐 **License**
✅ First launch asks for a license key; once verified, the device stays licensed

📈 **Market and charts**
✅ New MT5-style watch list: search and add continuous contracts, switch charts from the list, and reopen the last chart
✅ New continuous contract charts: the leader comes from the last completed trading day's volume, and orders route to the actual expiry
✅ Multiple overlays and panes (MA, EXPMA, BOLL, RSI, MACD, KDJ, ATR) with adjustable parameters and colours
✅ New dashed crosshair with price and time readouts on the axes
✅ Candles are saved locally, restored after a restart, and missing ranges are filled in automatically

🧾 **Trading**
✅ Drag stop loss and take profit of drafts and pending orders on the chart; the order panel gains protection checkboxes and a quantity slider
✅ New "skip trade confirmation" setting
✅ History shows server-paired trades with fees, and each expands to its entry and exit; the broker-reported daily P&L is displayed
✅ Sounds play on entry, take-profit and stop-loss fills

📱 **Phone and desktop**
✅ Phones get a chart-first layout with a collapsible, minimizable order panel
✅ New ongoing live P&L notification on Android, with Live Updates from Android 16
✅ Desktop panels can be resized by dragging, and settings are regrouped

🛠️ **Fixes**
✅ Fixed the quantity slider not following the account's available capacity
✅ Fixed unstable continuous contract resolution and missing watch-list prices while it runs
✅ An accepted command with an unconfirmed result now reconciles against server state without being resubmitted, and settings gain "Sync account"

🚧 **In development**
Rithmic login is not finished, so every entry to it is hidden in this version

⚠️ **Heads up**
Real order placement, changes and fill receipts have not been verified against a live server. Live mode sends the commands you confirm to a real account, so check behaviour on a Demo account first

⬇️ **Install**
1️⃣ Windows x64: download `UniTrading-1.2.0-windows-x64.zip`, extract it and run `UniTrading\UniTrading.exe`. WebView2 Runtime is required; the package is not code-signed, so SmartScreen may warn on first launch
2️⃣ Android 8.0 or later: download and install `UniTrading-1.2.0-*.apk`
3️⃣ Windows logins and settings live in `%LOCALAPPDATA%\UniTrading`, so replacing the program folder keeps them
