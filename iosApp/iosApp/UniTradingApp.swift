import SwiftUI
import UniTrading

@main
struct UniTradingApp: App {
    var body: some Scene {
        WindowGroup {
            TradingView().ignoresSafeArea(.keyboard)
        }
    }
}

struct TradingView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }
    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
