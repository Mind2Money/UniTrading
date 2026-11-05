# Unreleased

## 简体中文
🔌 **Rithmic 接入**
✅ 开启应用内 Rithmic 登录入口（登录页、设置、账户切换、空合约页）。Paper Trading 的登录、账户、行情、图表、下单、改单、撤单和成交配对已实测，平仓尚未验证。Rithmic 对生产系统要求先通过 conformance，公开分发前请自行确认合规。
✅ 连续合约优先使用账户可交易的交易所，失败时换下一个交易所，错误信息写明交易所；连续图表跟随到期合约的实时逐笔和 K 线
✅ 登录 Rithmic 后自动加载 MES，直接进入主界面；随后在后台同步账户可交易的产品，添加品种时不用先搜索
✅ 登录时同步按产品的下单上限、手续费和最近 7 天成交，下单预估显示双边手续费
✅ 余额改用服务器的账户余额，当日盈亏和手续费计入余额；净值和浮动盈亏不重复计算
✅ 修复下单后误报“交易结果未知”并暂停交易的问题
✅ 修复持仓和挂单的止损、止盈、入场价无法修改的问题
✅ 历史列表配对入场和止盈、止损、平仓成交，并显示手续费和净盈亏；止损触发时播放止损音效

## English
🔌 **Rithmic**
✅ Enabled the in-app Rithmic login entries (welcome, settings, account switcher, empty-catalog). Paper Trading login, accounts, market data, charts, placing, modifying and cancelling orders and fill pairing are verified; closing a position is not verified yet. Rithmic requires conformance for production systems, so confirm compliance before public distribution.
✅ Continuous contracts prefer an exchange the account can trade, fall back to the next one, and name the exchange in errors; continuous charts follow live trades and bars of the dated contract
✅ Rithmic login loads MES automatically and opens on the main screen, then syncs the account's tradable products in the background so the add-symbol list needs no search
✅ Login also syncs per-product order limits, commissions and the last 7 days of fills; order previews show the round-trip commission
✅ The balance now uses the server's account balance, including the day's P&L and fees, and equity and floating P&L count open P&L once
✅ Fixed orders being reported as "result unknown", which paused trading
✅ Fixed stop-loss, take-profit and entry price edits being refused on positions and pending orders
✅ History pairs entries with their take-profit, stop-loss and closing fills, with fees and net P&L; a triggered stop plays the stop-loss sound

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
