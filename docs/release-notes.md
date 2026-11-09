# Unreleased

## 简体中文

📊 **Rithmic 订单流**
✅ 登录 Rithmic 后可从“订单流”打开足迹（Bid × Ask）、逐笔成交量分布（POC / 70% 价值区）、Delta / CVD、可按成交量过滤并暂停显示的逐笔成交，以及只读多档 DOM
✅ 宽屏将图表与分析并排显示；小窗口和手机使用独立分析页，支持返回和横向滚动切换工具
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

🆕 **应用内更新**
✅ 「设置 → 关于与更新」可以检查新版本并一键更新：Windows 自动下载、校验、替换程序并重启；Android 下载校验后打开系统安装界面，第一次需要在系统页面允许本应用安装未知应用
✅ 账户有持仓或挂单、或账户状态无法确认时不能更新；登录状态和设置保存在程序目录之外，更新不受影响
✅ 从源码运行、本地模拟和 macOS 版也能在「关于与更新」里手动检查，发现新版本时打开发布页下载；检查后显示检查时间，并加了更新日志入口
⚠️ 这个版本本身仍要手动下载一次，之后的版本才能在应用内更新

## English

📊 **Rithmic order flow**
✅ Added Bid × Ask footprints, traded volume profiles with POC and a 70% value area, Delta / CVD, a filterable tape with display pause, and a read-only multi-level DOM after Rithmic sign-in
✅ Wide windows show the chart and analysis side by side; smaller windows and phones use a dedicated analysis page with back navigation and horizontally scrollable tools
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
