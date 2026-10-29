# 实时盈亏通知（Android Live Update / iOS 灵动岛）

当前显示的账户的浮动盈亏会镜像到系统界面：Android 常驻通知（Android 16 QPR1 / API 36.1 起申请提升为 Live Update，状态栏芯片显示盈亏），iOS 16.2 起为 Live Activity（灵动岛与锁屏）。显示内容为「交易商 · 账户」、带符号的浮动盈亏与币种、净值、持仓数以及断开状态；盈利、亏损分别使用 `#089981` / `#F23645`。

## 共享逻辑

- `LivePnlController`（`composeApp/src/commonMain/kotlin/com/unitrading/app/LivePnl.kt`）：`TradingScreen` 在打开账户会话时调用 `track`，离开时 `untrack`；平台各自用自己的 `LivePnlPresenter` 调用一次 `run`。
- `LivePnlUpdate.from` 由 `AccountSnapshot` 生成文本：余额或净值未知时盈亏显示「—」，`equityEstimated` 标注「估算」，行情断开追加「已断开」。浮动盈亏 = 净值 − 余额，与界面底栏一致。
- 刷新规则：文本变化才重发，最快每秒一次；无变化时每 20 秒重发一次心跳，使平台过期计时不会把安静账户的通知移除。
- 设置保存在本机 `live-pnl-v1`，默认开启；账户页「实时盈亏通知」开关只在运行了平台 presenter 的平台（Android、iOS）显示，桌面没有该开关。损坏的设置回退到默认并显示错误，保存失败保留现有值。

## Android

- `androidApp/.../LivePnlNotifier.kt`：按 RedefineNCM_KMP 的实况通知写法，使用 `NotificationCompat`（androidx.core 1.18）：盈亏数字作标题，`setSilent`、`setOngoing`、`PRIORITY_LOW`、`VISIBILITY_PUBLIC`、低重要性渠道 `live-pnl-status`；Android 16 起设置 `setShortCriticalText(盈亏)`，并在 `NotificationManager.canPostPromotedNotifications()` 为真时 `setRequestPromotedOngoing(true)`——该请求只是 `android.requestPromotedOngoing` extra，Android 16.0 与 HyperOS 超级岛同样识别（此前按 API 36.1 判断导致 16.0 设备从不请求提升）。点击通知回到 `MainActivity`。
- `LivePnlService`（`foregroundServiceType="dataSync"`）：首次有通知时由 notifier 用 `startForegroundService` 启动并以该通知 `startForeground`，之后的刷新只 `notify` 同一 id；清除通知即 `stopService`。前台服务让进程、交易商连接和通知在后台继续刷新；Android 15+ 对 dataSync 有每日 6 小时上限，超时后服务自行停止并移除通知，再次打开应用才会重新开始。应用在后台且服务已停止时，通知退化为普通常驻通知并在 90 秒无刷新后过期。
- 清单声明 `POST_NOTIFICATIONS`（Android 13+ 运行时权限）、`POST_PROMOTED_NOTIFICATIONS`（Live Update，用户可在系统通知设置里关闭「实况通知 / 超级岛」）、`FOREGROUND_SERVICE` 与 `FOREGROUND_SERVICE_DATA_SYNC`。首次有账户可显示而权限缺失时，`MainActivity` 弹出一次系统权限请求；拒绝后不再提示，通知静默不显示，开关仍可用。
- `TradingRuntimeModel` 持有 notifier 并在模型销毁（任务被划掉）时清除通知与前台服务；前台服务通知随进程结束一起消失，不会留下过时数字。
- 设备测试 `LivePnlNotificationTest` 通过真实 `NotificationManager` 发布合成通知，核对标题（盈亏）、文本、常驻标志、Android 16 的状态栏文本以及提升请求 extra 与系统许可一致；前台服务在测试进程里若不允许启动则走普通通知路径。

## iOS

- Kotlin 侧 `NativeLivePnlPresenter` 由 Swift `AppleLivePnl`（`iosApp/iosApp/LivePnlPresenter.swift`）实现；`LivePnlActivityHost` 维护单个 `Activity<LivePnlAttributes>`，账户变化时结束旧活动并重新请求，`staleDate` 为最近刷新后 90 秒，启动时结束上次进程遗留的活动。
- Widget 扩展 `UniTradingWidgets`（`iosApp/UniTradingWidgets/`）渲染锁屏横幅与灵动岛（紧凑、最小化、展开三种布局）；`LivePnlActivity.swift` 在应用与扩展两个目标中共同编译。
- `Info.plist` 增加 `NSSupportsLiveActivities` / `NSSupportsLiveActivitiesFrequentUpdates`；扩展部署目标 iOS 16.2，应用保持 16.0，低版本静默不显示。
- 仅在应用前台运行时刷新（Kotlin 协程随应用挂起），后台不更新；没有推送通道。iOS 工程仍未在 Mac/Xcode 构建验证，此部分代码只经过阅读检查，CI `test-ios` 会随应用一起编译该扩展。

## 验证

- `LivePnlTest`：文本格式、每秒限频、心跳、开关持久化、`untrack` 语义与取消后的清理。
- `LivePnlSwitchTest`（桌面 Compose）：没有 presenter 时账户页不显示开关；presenter 运行后开关出现并可关闭通知。
- Android `LivePnlNotificationTest` 在 CI 模拟器上运行；iOS 扩展未在本地构建。
