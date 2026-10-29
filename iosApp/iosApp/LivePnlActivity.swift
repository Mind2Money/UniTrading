import ActivityKit
import Foundation

/// Live Activity payload shared by the app (which requests and updates it) and the widget extension (which renders it).
@available(iOS 16.1, *)
struct LivePnlAttributes: ActivityAttributes {
    public struct ContentState: Codable, Hashable {
        /// Signed floating P&L with currency, e.g. "+128.50 USD", or "—" when unknown.
        var pnl: String
        /// Equity, open positions and connection state.
        var detail: String
        /// 1 profit, -1 loss, 0 flat or unknown.
        var trend: Int
        var updatedAt: Date
    }
    /// "Broker · Account"; a different account starts a new activity.
    var title: String
}
