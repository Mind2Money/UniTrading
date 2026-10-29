import Foundation
import Darwin
import UIKit
import WebKit
import Security
import UniTrading

final class ApplePlatform: NSObject, NativeLoginPresenter, NativeCredentialStorage {
    let bridge = NativePlatformBridge()
    let livePnl = AppleLivePnl()
    weak var presentingController: UIViewController?
    private var loginWindow: UINavigationController?
    private let keychain = SessionKeychain(service: "com.unitrading.credentials.v1")

    override init() {
        super.init()
        bridge.presenter = self
        bridge.storage = self
        bridge.livePnl = livePnl
    }

    func open(environment: String, script: String) {
        guard loginWindow == nil, let presenter = presentingController else {
            bridge.finish(cancelled: true)
            return
        }
        let login = TradovateWebLogin(environment: environment, script: script, bridge: bridge)
        let navigation = UINavigationController(rootViewController: login)
        navigation.modalPresentationStyle = .fullScreen
        navigation.isModalInPresentation = true
        loginWindow = navigation
        var active = presenter.viewIfLoaded?.window?.windowScene?.windows.first(where: { $0.isKeyWindow })?.rootViewController ?? presenter
        while let presented = active.presentedViewController { active = presented }
        active.present(navigation, animated: true)
    }

    func close() {
        loginWindow?.dismiss(animated: true)
        loginWindow = nil
    }
    func read() -> NativeStorageResult { keychain.read() }
    func write(value: String) -> NativeStorageResult { keychain.write(value) }
    func clear() -> NativeStorageResult { keychain.clear() }

    #if DEBUG
    func runUISelfTest(attempts: Int = 50) {
        guard presentingController?.viewIfLoaded?.window != nil else {
            precondition(attempts > 0, "Compose root did not attach to a window")
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.1) { self.runUISelfTest(attempts: attempts - 1) }
            return
        }
        // Exercise the exported presenter protocol and the real WebKit controller without account credentials.
        bridge.presenter?.open(environment: "demo", script: "/* UI host smoke test */")
        DispatchQueue.main.asyncAfter(deadline: .now() + 1) {
            guard let window = self.loginWindow, let login = window.topViewController as? TradovateWebLogin else {
                preconditionFailure("Native login controller was not presented")
            }
            precondition(window.view.window != nil && login.bridgeIsInstalled)
            self.close()
            DispatchQueue.main.asyncAfter(deadline: .now() + 1) {
                precondition(self.loginWindow == nil && window.presentingViewController == nil)
                print("NATIVE_UI_SELF_TEST PASS")
                exit(0)
            }
        }
    }
    #endif
}

final class SessionKeychain {
    private let service: String
    init(service: String) { self.service = service }
    private var query: [String: Any] {
        [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service,
         kSecAttrAccount as String: "tradovate", kSecAttrSynchronizable as String: false]
    }
    func read() -> NativeStorageResult {
        var request = query
        request[kSecReturnData as String] = true
        request[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        let status = SecItemCopyMatching(request as CFDictionary, &result)
        if status == errSecItemNotFound { return NativeStorageResult(value: nil, error: nil) }
        guard status == errSecSuccess, let data = result as? Data, let value = String(data: data, encoding: .utf8) else {
            return NativeStorageResult(value: nil, error: "Keychain read failed (OSStatus \(status))")
        }
        return NativeStorageResult(value: value, error: nil)
    }
    func write(_ value: String) -> NativeStorageResult {
        let attributes: [String: Any] = [kSecValueData as String: Data(value.utf8),
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly]
        var status = SecItemUpdate(query as CFDictionary, attributes as CFDictionary)
        if status == errSecItemNotFound {
            status = SecItemAdd(query.merging(attributes) { _, new in new } as CFDictionary, nil)
        }
        return NativeStorageResult(value: nil, error: status == errSecSuccess ? nil : "Keychain write failed (OSStatus \(status))")
    }
    func clear() -> NativeStorageResult {
        let status = SecItemDelete(query as CFDictionary)
        return NativeStorageResult(value: nil, error: status == errSecSuccess || status == errSecItemNotFound ? nil : "Keychain delete failed (OSStatus \(status))")
    }
}

enum OfficialLoginPolicy {
    static func navigationAllowed(_ url: URL?) -> Bool {
        guard let url = url, url.scheme?.lowercased() == "https", url.user == nil, url.password == nil,
              url.port == nil || url.port == 443 else { return false }
        return ["trader.tradovate.com", "auth.tradovate.com", "login.tradovate.com"].contains(url.host?.lowercased() ?? "")
    }
    static func messageAllowed(protocolName: String, host: String, port: Int, mainFrame: Bool, currentURL: URL?) -> Bool {
        mainFrame && protocolName == "https" && host == "trader.tradovate.com" && (port == 0 || port == 443) &&
        navigationAllowed(currentURL) && currentURL?.host == "trader.tradovate.com"
    }
}

final class TradovateWebLogin: UIViewController, WKScriptMessageHandler, WKNavigationDelegate, WKUIDelegate {
    private let environment: String
    private let script: String
    private let bridge: NativePlatformBridge
    private var web: WKWebView!
    private var completed = false
    private var captured = false
    private var automaticReturn: DispatchWorkItem?
    #if DEBUG
    var bridgeIsInstalled: Bool {
        guard let web = web else { return false }
        let scripts = web.configuration.userContentController.userScripts
        return !web.configuration.websiteDataStore.isPersistent && scripts.count == 1 &&
            scripts[0].isForMainFrameOnly && scripts[0].injectionTime == .atDocumentStart
    }
    #endif

    init(environment: String, script: String, bridge: NativePlatformBridge) {
        self.environment = environment; self.script = script; self.bridge = bridge
        super.init(nibName: nil, bundle: nil)
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) is not supported") }
    override func viewDidLoad() {
        super.viewDidLoad()
        title = "Tradovate \(environment)"
        navigationItem.leftBarButtonItem = UIBarButtonItem(title: "取消", style: .plain, target: self, action: #selector(cancel))
        navigationItem.rightBarButtonItem = UIBarButtonItem(title: "返回", style: .done, target: self, action: #selector(done))
        navigationItem.rightBarButtonItem?.isEnabled = false
        let configuration = WKWebViewConfiguration()
        // Authentication cookies never cross login attempts; the app persists the returned session in Keychain.
        configuration.websiteDataStore = .nonPersistent()
        configuration.preferences.javaScriptCanOpenWindowsAutomatically = false
        let content = configuration.userContentController
        content.add(self, name: "uniTradingAuth")
        let bridgeScript = "window.UniTradingAuth = {postMessage: function(m) { window.webkit.messageHandlers.uniTradingAuth.postMessage(m); }};\n"
        content.addUserScript(WKUserScript(source: bridgeScript + script, injectionTime: .atDocumentStart, forMainFrameOnly: true))
        web = WKWebView(frame: .zero, configuration: configuration)
        web.navigationDelegate = self; web.uiDelegate = self
        web.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(web)
        NSLayoutConstraint.activate([web.leadingAnchor.constraint(equalTo: view.leadingAnchor), web.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            web.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor), web.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor)])
        web.load(URLRequest(url: URL(string: "https://trader.tradovate.com/welcome")!))
    }
    func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
        let origin = message.frameInfo.securityOrigin
        guard !completed, message.name == "uniTradingAuth", message.webView === web,
              OfficialLoginPolicy.messageAllowed(protocolName: origin.protocol, host: origin.host, port: origin.port,
                  mainFrame: message.frameInfo.isMainFrame, currentURL: web.url),
              let payload = message.body as? String, payload.utf8.count <= 131072 else { return }
        if bridge.accept(message: payload, origin: "https://trader.tradovate.com", isMainFrame: true), !captured {
            captured = true
            title = "登录成功，正在返回"
            navigationItem.rightBarButtonItem?.isEnabled = true
            let item = DispatchWorkItem { [weak self] in self?.done() }
            automaticReturn = item
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.5, execute: item)
        }
    }
    func webView(_ webView: WKWebView, decidePolicyFor navigationAction: WKNavigationAction, decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        if navigationAction.shouldPerformDownload { decisionHandler(.cancel); return }
        // Subresources are isolated by WebKit; top-level destinations require an explicit official host.
        let top = navigationAction.targetFrame?.isMainFrame ?? true
        decisionHandler(!top || OfficialLoginPolicy.navigationAllowed(navigationAction.request.url) ? .allow : .cancel)
    }
    func webView(_ webView: WKWebView, decidePolicyFor navigationResponse: WKNavigationResponse, decisionHandler: @escaping (WKNavigationResponsePolicy) -> Void) {
        decisionHandler(navigationResponse.canShowMIMEType ? .allow : .cancel)
    }
    func webView(_ webView: WKWebView, createWebViewWith configuration: WKWebViewConfiguration, for navigationAction: WKNavigationAction, windowFeatures: WKWindowFeatures) -> WKWebView? {
        if OfficialLoginPolicy.navigationAllowed(navigationAction.request.url) { web.load(navigationAction.request) }
        return nil
    }
    func webView(_ webView: WKWebView, didReceive challenge: URLAuthenticationChallenge, completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void) {
        completionHandler(.performDefaultHandling, nil)
    }
    @objc private func cancel() { finish(cancelled: true) }
    @objc private func done() { if captured { finish(cancelled: false) } }
    private func finish(cancelled: Bool) {
        guard !completed else { return }
        completed = true; automaticReturn?.cancel()
        web.stopLoading(); web.configuration.userContentController.removeScriptMessageHandler(forName: "uniTradingAuth")
        bridge.finish(cancelled: cancelled)
    }
    override func viewDidDisappear(_ animated: Bool) {
        super.viewDidDisappear(animated)
        automaticReturn?.cancel()
        web?.stopLoading()
        web?.configuration.userContentController.removeScriptMessageHandler(forName: "uniTradingAuth")
        if !completed { completed = true; bridge.finish(cancelled: true) }
    }
}

#if DEBUG
enum NativePlatformSelfTest {
    static func run() {
        let store = SessionKeychain(service: "com.unitrading.tests.\(UUID().uuidString)")
        defer { _ = store.clear() }
        let initial = store.read()
        precondition(initial.value == nil && initial.error == nil, initial.error ?? "Unexpected existing test item")
        let firstWrite = store.write("synthetic-platform-test")
        precondition(firstWrite.error == nil, firstWrite.error ?? "")
        precondition(store.read().value == "synthetic-platform-test")
        precondition(store.write("synthetic-platform-update").error == nil)
        precondition(store.read().value == "synthetic-platform-update")
        precondition(store.clear().error == nil && store.read().value == nil)
        let official = URL(string: "https://trader.tradovate.com/welcome")!
        precondition(OfficialLoginPolicy.navigationAllowed(official))
        for rejected in ["http://trader.tradovate.com", "https://trader.tradovate.com.evil.example", "https://user@trader.tradovate.com", "https://trader.tradovate.com:8443"] {
            precondition(!OfficialLoginPolicy.navigationAllowed(URL(string: rejected)))
        }
        precondition(OfficialLoginPolicy.messageAllowed(protocolName: "https", host: "trader.tradovate.com", port: 443, mainFrame: true, currentURL: official))
        precondition(!OfficialLoginPolicy.messageAllowed(protocolName: "https", host: "trader.tradovate.com", port: 443, mainFrame: false, currentURL: official))
        precondition(!OfficialLoginPolicy.messageAllowed(protocolName: "https", host: "evil.example", port: 443, mainFrame: true, currentURL: official))
        precondition(!OfficialLoginPolicy.messageAllowed(protocolName: "https", host: "trader.tradovate.com", port: 8443, mainFrame: true, currentURL: official))
        precondition(!OfficialLoginPolicy.messageAllowed(protocolName: "https", host: "trader.tradovate.com", port: 443, mainFrame: true, currentURL: URL(string: "https://auth.tradovate.com")))
        precondition(AppleLivePnl.date(fromMillis: 1_700_000_000_500).timeIntervalSince1970 == 1_700_000_000.5)
        if #available(iOS 16.1, *) {
            // The widget extension decodes exactly what the app encodes.
            let state = LivePnlAttributes.ContentState(pnl: "+1.00 USD", detail: "净值 1.00 · 持仓 0", trend: 1, updatedAt: Date(timeIntervalSince1970: 1_700_000_000))
            let encoded = try! JSONEncoder().encode(state)
            precondition(try! JSONDecoder().decode(LivePnlAttributes.ContentState.self, from: encoded) == state)
        }
    }
}
#endif
