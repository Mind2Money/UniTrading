import SwiftUI
import UniTrading
import Darwin

@main
struct UniTradingApp: App {
    init() {
        #if DEBUG
        if ProcessInfo.processInfo.arguments.contains("--native-platform-self-test") {
            NativePlatformSelfTest.run()
            print("NATIVE_PLATFORM_SELF_TEST PASS")
            exit(0)
        }
        #endif
    }
    var body: some Scene {
        WindowGroup {
            TradingView().ignoresSafeArea(.keyboard)
        }
    }
}

struct TradingView: UIViewControllerRepresentable {
    func makeCoordinator() -> ApplePlatform { ApplePlatform() }
    func makeUIViewController(context: Context) -> UIViewController {
        let controller = MainViewControllerKt.MainViewController(platform: context.coordinator.bridge)
        context.coordinator.presentingController = controller
        return controller
    }
    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
    static func dismantleUIViewController(_ uiViewController: UIViewController, coordinator: ApplePlatform) {
        coordinator.bridge.finish(cancelled: true)
        coordinator.close()
        coordinator.bridge.presenter = nil
        coordinator.bridge.storage = nil
    }
}
