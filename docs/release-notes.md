# Unreleased

## 简体中文

💻 **macOS 版**
✅ 新增 Apple Silicon 的 macOS 桌面版（macOS 13 及以上），支持 Tradovate 和 Rithmic 登录，授权记录和已保存登录由系统钥匙串保护
⚠️ 尚未在真实 Mac 上实测登录。安装包未经 Apple 公证，解压后先执行 `xattr -cr UniTrading.app` 再打开
⚠️ macOS 登录窗口不能记住密码，每次需要手动输入

📈 **行情**
✅ 编辑自选改为拖动调整顺序，去掉上移、下移箭头：鼠标按住整行，手机按住左侧手柄，拖到列表边缘自动滚动

## English

💻 **macOS**
✅ New macOS desktop build for Apple Silicon (macOS 13 or later) with Tradovate and Rithmic sign-in; the license record and saved logins are protected by the system keychain
⚠️ Sign-in has not been run on a real Mac yet. The package is not notarized by Apple: after unzipping, run `xattr -cr UniTrading.app` before opening it
⚠️ The macOS login window cannot remember passwords; enter yours each time

📈 **Market watch**
✅ Reorder the watch list by dragging instead of up and down arrows: hold a row with the mouse or its left handle on a phone; the list scrolls when you reach its edge

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
