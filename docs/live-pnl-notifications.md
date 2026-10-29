# 实时盈亏通知（Android Live Update / iOS 灵动岛）

当前显示的账户的浮动盈亏会镜像到系统界面：Android 常驻通知（Android 16 起申请提升为 Live Update，状态栏芯片显示盈亏），iOS 16.2 起为 Live Activity（灵动岛与锁屏）。显示内容为「交易商 · 账户」、带符号的浮动盈亏与币种、净值、持仓数以及断开状态；盈利、亏损分别使用 `#089981` / `#F23645`。

## 共享逻辑

- `LivePnlController`（`composeApp/src/commonMain/kotlin/com/unitrading/app/LivePnl.kt`）：`TradingScreen` 在打开账户会话时调用 `track`，离开时 `untrack`；平台各自用自己的 `LivePnlPresenter` 调用一次 `run`。
- `LivePnlUpdate.from` 由 `AccountSnapshot` 生成文本：余额或净值未知时盈亏显示「—」，`equityEstimated` 标注「估算」，行情断开追加「已断开」。浮动盈亏 = 净值 − 余额，与界面底栏一致。
- 刷新规则：文本变化才重发，最快每秒一次；无变化时每 20 秒重发一次心跳，使平台过期计时不会把安静账户的通知移除。
- 设置保存在本机 `live-pnl-v1`，默认开启；账户页「实时盈亏通知」开关只在运行了平台 presenter 的平台（Android、iOS）显示，桌面没有该开关。损坏的设置回退到默认并显示错误，保存失败保留现有值。

## Android

- `androidApp/.../LivePnlNotifier.kt`：通知渠道 `live-pnl`，`setOngoing(true)`，`setTimeoutAfter(90 s)`；Android 16+ 额外 `setRequestPromotedOngoing(true)` 与 `setShortCriticalText(盈亏)`。点击通知回到 `MainActivity`。
- 清单声明 `POST_NOTIFICATIONS`（Android 13+ 运行时权限）和 `POST_PROMOTED_NOTIFICATIONS`（Live Update）。首次有账户可显示而权限缺失时，`MainActivity` 弹出一次系统权限请求；拒绝后不再提示，通知静默不显示，开关仍可用。
- `TradingRuntimeModel` 持有通知并在模型销毁时取消；没有前台服务，进程被系统结束后通知在 90 秒内自动过期，不会留下过时数字。
- 设备测试 `LivePnlNotificationTest` 通过真实 `NotificationManager` 发布合成通知，核对标题、文本、常驻标志和 Android 16 的状态栏文本。

## iOS

- Kotlin 侧 `NativeLivePnlPresenter` 由 Swift `AppleLivePnl`（`iosApp/iosApp/LivePnlPresenter.swift`）实现；`LivePnlActivityHost` 维护单个 `Activity<LivePnlAttributes>`，账户变化时结束旧活动并重新请求，`staleDate` 为最近刷新后 90 秒，启动时结束上次进程遗留的活动。
- Widget 扩展 `UniTradingWidgets`（`iosApp/UniTradingWidgets/`）渲染锁屏横幅与灵动岛（紧凑、最小化、展开三种布局）；`LivePnlActivity.swift` 在应用与扩展两个目标中共同编译。
- `Info.plist` 增加 `NSSupportsLiveActivities` / `NSSupportsLiveActivitiesFrequentUpdates`；扩展部署目标 iOS 16.2，应用保持 16.0，低版本静默不显示。
- 仅在应用前台运行时刷新（Kotlin 协程随应用挂起），后台不更新；没有推送通道。iOS 工程仍未在 Mac/Xcode 构建验证，此部分代码只经过阅读检查，CI `test-ios` 会随应用一起编译该扩展。

## 验证

- `LivePnlTest`：文本格式、每秒限频、心跳、开关持久化、`untrack` 语义与取消后的清理。
- `LivePnlSwitchTest`（桌面 Compose）：没有 presenter 时账户页不显示开关；presenter 运行后开关出现并可关闭通知。
- Android `LivePnlNotificationTest` 在 CI 模拟器上运行；iOS 扩展未在本地构建。
