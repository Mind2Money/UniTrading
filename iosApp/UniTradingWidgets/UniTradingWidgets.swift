import ActivityKit
import SwiftUI
import WidgetKit

@main
struct LivePnlWidgetBundle: WidgetBundle {
    var body: some Widget {
        LivePnlLiveActivity()
    }
}

/// Lock Screen banner and Dynamic Island for the account P&L activity the app starts.
struct LivePnlLiveActivity: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: LivePnlAttributes.self) { context in
            HStack(alignment: .firstTextBaseline) {
                VStack(alignment: .leading, spacing: 4) {
                    Text(context.attributes.title).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                    Text(context.state.detail).font(.caption2).foregroundStyle(.secondary).lineLimit(1)
                }
                Spacer()
                Text(context.state.pnl)
                    .font(.title3.monospacedDigit().weight(.semibold))
                    .foregroundStyle(PnlStyle.color(context.state.trend))
            }
            .padding(14)
            .activityBackgroundTint(Color.black.opacity(0.85))
            .activitySystemActionForegroundColor(.white)
        } dynamicIsland: { context in
            DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    Text(context.attributes.title).font(.caption).lineLimit(1)
                }
                DynamicIslandExpandedRegion(.trailing) {
                    Text(context.state.pnl).font(.headline.monospacedDigit()).foregroundStyle(PnlStyle.color(context.state.trend))
                }
                DynamicIslandExpandedRegion(.bottom) {
                    Text(context.state.detail).font(.caption2).foregroundStyle(.secondary).lineLimit(1)
                }
            } compactLeading: {
                Image(systemName: PnlStyle.symbol(context.state.trend)).foregroundStyle(PnlStyle.color(context.state.trend))
            } compactTrailing: {
                Text(context.state.pnl).font(.caption2.monospacedDigit()).foregroundStyle(PnlStyle.color(context.state.trend)).lineLimit(1)
            } minimal: {
                Image(systemName: PnlStyle.symbol(context.state.trend)).foregroundStyle(PnlStyle.color(context.state.trend))
            }
        }
    }
}

/// TradingView-style profit/loss colours (#089981 / #F23645).
enum PnlStyle {
    static func color(_ trend: Int) -> Color {
        if trend > 0 { return Color(red: 0.03, green: 0.60, blue: 0.51) }
        if trend < 0 { return Color(red: 0.95, green: 0.21, blue: 0.27) }
        return .primary
    }
    static func symbol(_ trend: Int) -> String {
        trend > 0 ? "arrow.up.right" : trend < 0 ? "arrow.down.right" : "minus"
    }
}
