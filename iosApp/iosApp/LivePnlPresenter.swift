import ActivityKit
import Foundation
import UniTrading

/// Receives the Kotlin controller's updates and keeps one Live Activity (Dynamic Island + Lock Screen) alive.
final class AppleLivePnl: NSObject, NativeLivePnlPresenter {
    private var host: Any?

    override init() {
        super.init()
        if #available(iOS 16.2, *) { host = LivePnlActivityHost() }
    }

    static func date(fromMillis millis: Int64) -> Date { Date(timeIntervalSince1970: TimeInterval(millis) / 1000) }

    func show(title: String, pnl: String, detail: String, trend: Int32, timeMs: Int64) {
        guard #available(iOS 16.2, *), let host = host as? LivePnlActivityHost else { return }
        host.show(title: title, pnl: pnl, detail: detail, trend: Int(trend), updatedAt: Self.date(fromMillis: timeMs))
    }

    func dismiss() {
        guard #available(iOS 16.2, *), let host = host as? LivePnlActivityHost else { return }
        host.end()
    }
}

@available(iOS 16.2, *)
final class LivePnlActivityHost {
    /// Lock Screen and Dynamic Island mark the content stale after this long without a refresh.
    static let staleAfter: TimeInterval = 90
    private var activity: Activity<LivePnlAttributes>?

    init() {
        // Activities left behind by a previous process carry stale numbers.
        for stale in Activity<LivePnlAttributes>.activities {
            Task { await stale.end(nil, dismissalPolicy: .immediate) }
        }
    }

    func show(title: String, pnl: String, detail: String, trend: Int, updatedAt: Date) {
        guard ActivityAuthorizationInfo().areActivitiesEnabled else { return }
        let state = LivePnlAttributes.ContentState(pnl: pnl, detail: detail, trend: trend, updatedAt: updatedAt)
        let content = ActivityContent(state: state, staleDate: updatedAt.addingTimeInterval(Self.staleAfter))
        if let current = activity, current.attributes.title == title, current.activityState == .active {
            Task { await current.update(content) }
            return
        }
        end()
        activity = try? Activity.request(attributes: LivePnlAttributes(title: title), content: content, pushType: nil)
    }

    func end() {
        guard let current = activity else { return }
        activity = nil
        Task { await current.end(nil, dismissalPolicy: .immediate) }
    }
}
