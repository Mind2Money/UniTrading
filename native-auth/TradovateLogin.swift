// The official Tradovate login window for macOS. It speaks the same stdout protocol as TradovateLogin.cs to
// DesktopTradovateLogin, opens only Tradovate's own sign-in pages and never reads form fields or passwords.
import AppKit
import Darwin
import WebKit

enum LoginPolicy {
    static let maximumMessageBytes = 128 * 1024
    static let maximumScriptBytes = 512 * 1024

    static func isEnvironment(_ value: String) -> Bool { value == "demo" || value == "live" }

    static func navigationAllowed(_ url: URL?) -> Bool {
        guard let url = url, url.scheme?.lowercased() == "https", url.user == nil, url.password == nil,
              url.port == nil || url.port == 443 else { return false }
        return ["trader.tradovate.com", "auth.tradovate.com", "login.tradovate.com"].contains(url.host?.lowercased() ?? "")
    }

    static func messageAllowed(protocolName: String, host: String, port: Int, mainFrame: Bool, currentURL: URL?) -> Bool {
        return mainFrame && protocolName == "https" && host == "trader.tradovate.com" && (port == 0 || port == 443) &&
            navigationAllowed(currentURL) && currentURL?.host?.lowercased() == "trader.tradovate.com"
    }

    /// The page's message as one protocol line, when it is a session message for this window. The app validates its fields again.
    static func session(_ body: Any, environment: String) -> (line: String, hasAccessToken: Bool)? {
        guard let payload = body as? String, payload.utf8.count <= maximumMessageBytes,
              !payload.contains(where: { $0.isNewline }), let data = payload.data(using: .utf8),
              let object = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              object["kind"] as? String == "auth", object["environment"] as? String == environment else { return nil }
        let token = object["accessToken"] as? String
        return (payload, token != nil && token != "")
    }
}

enum Output {
    /// False once the app has gone away; SIGPIPE is ignored so that a closed pipe is an error here and not a crash.
    static func write(_ line: String) -> Bool {
        let bytes = Array((line + "\n").utf8)
        var offset = 0
        while offset < bytes.count {
            let written = bytes[offset...].withUnsafeBytes { Darwin.write(STDOUT_FILENO, $0.baseAddress, $0.count) }
            if written < 0 {
                if errno == EINTR { continue }
                return false
            }
            offset += written
        }
        return true
    }
}

final class LoginController: NSObject, NSApplicationDelegate, NSWindowDelegate, WKScriptMessageHandler, WKNavigationDelegate, WKUIDelegate {
    private let environment: String
    private let script: String
    private let statusLabel = NSTextField(labelWithString: "账号密码只在官方网页内输入。")
    private let cancelButton = NSButton(title: "取消登录", target: nil, action: nil)
    private let returnButton = NSButton(title: "立即返回", target: nil, action: nil)
    private var window: NSWindow!
    private var web: WKWebView!
    private var captured = false
    private var finished = false

    init(environment: String, script: String) {
        self.environment = environment
        self.script = script
        super.init()
    }

    func applicationDidFinishLaunching(_ notification: Notification) {
        NSApp.mainMenu = makeMenu()
        web = makeWebView()
        window = makeWindow()
        window.makeKeyAndOrderFront(nil)
        NSApp.activate(ignoringOtherApps: true)
        if !Output.write("STATUS WAITING_BROWSER") { exit(3) }
        web.load(URLRequest(url: URL(string: "https://trader.tradovate.com/welcome")!))
    }

    // Without an Edit menu, Command-V never reaches the page's password field.
    private func makeMenu() -> NSMenu {
        let application = NSMenu()
        let quit = application.addItem(withTitle: "取消登录", action: #selector(cancelLogin), keyEquivalent: "q")
        quit.target = self
        let edit = NSMenu(title: "编辑")
        edit.addItem(withTitle: "剪切", action: #selector(NSText.cut(_:)), keyEquivalent: "x")
        edit.addItem(withTitle: "复制", action: #selector(NSText.copy(_:)), keyEquivalent: "c")
        edit.addItem(withTitle: "粘贴", action: #selector(NSText.paste(_:)), keyEquivalent: "v")
        edit.addItem(withTitle: "全选", action: #selector(NSText.selectAll(_:)), keyEquivalent: "a")
        let bar = NSMenu()
        for submenu in [application, edit] {
            let item = NSMenuItem()
            item.submenu = submenu
            bar.addItem(item)
        }
        return bar
    }

    private func makeWebView() -> WKWebView {
        let configuration = WKWebViewConfiguration()
        // Kept per environment, as the Windows and Android login profiles are, so that Tradovate keeps seeing one device.
        if #available(macOS 14.0, *), let identifier = UUID(uuidString: environment == "live" ? "6F0B7C52-52C1-4F3E-9D6A-2B1E8C4A7702" : "6F0B7C52-52C1-4F3E-9D6A-2B1E8C4A7701") {
            configuration.websiteDataStore = WKWebsiteDataStore(forIdentifier: identifier)
        } else {
            configuration.websiteDataStore = WKWebsiteDataStore.default()
        }
        // This is Safari's engine; without the suffix the web trader sees an unnamed browser.
        configuration.applicationNameForUserAgent = "Version/18.0 Safari/605.1.15"
        configuration.preferences.javaScriptCanOpenWindowsAutomatically = false
        let content = configuration.userContentController
        content.add(self, name: "uniTradingAuth")
        let bridge = "window.UniTradingAuth = {postMessage: function(m) { window.webkit.messageHandlers.uniTradingAuth.postMessage(m); }};\n"
        content.addUserScript(WKUserScript(source: bridge + script, injectionTime: .atDocumentStart, forMainFrameOnly: true))
        let view = WKWebView(frame: .zero, configuration: configuration)
        view.navigationDelegate = self
        view.uiDelegate = self
        return view
    }

    private func makeWindow() -> NSWindow {
        let window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 1100, height: 820),
                              styleMask: [.titled, .closable, .miniaturizable, .resizable], backing: .buffered, defer: false)
        window.title = "Tradovate 登录 | UniTrading · " + (environment == "live" ? "Live 实盘" : "Demo 模拟")
        window.minSize = NSSize(width: 720, height: 600)
        window.isReleasedWhenClosed = false
        window.delegate = self
        cancelButton.target = self
        cancelButton.action = #selector(cancelLogin)
        returnButton.target = self
        returnButton.action = #selector(finishLogin)
        returnButton.isEnabled = false
        statusLabel.lineBreakMode = .byTruncatingTail
        statusLabel.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        let content = NSView()
        let views: [NSView] = [statusLabel, cancelButton, returnButton, web]
        for view in views {
            view.translatesAutoresizingMaskIntoConstraints = false
            content.addSubview(view)
        }
        NSLayoutConstraint.activate([
            cancelButton.topAnchor.constraint(equalTo: content.topAnchor, constant: 8),
            returnButton.centerYAnchor.constraint(equalTo: cancelButton.centerYAnchor),
            statusLabel.centerYAnchor.constraint(equalTo: cancelButton.centerYAnchor),
            statusLabel.leadingAnchor.constraint(equalTo: content.leadingAnchor, constant: 12),
            statusLabel.trailingAnchor.constraint(lessThanOrEqualTo: cancelButton.leadingAnchor, constant: -12),
            cancelButton.trailingAnchor.constraint(equalTo: returnButton.leadingAnchor, constant: -8),
            returnButton.trailingAnchor.constraint(equalTo: content.trailingAnchor, constant: -12),
            web.topAnchor.constraint(equalTo: cancelButton.bottomAnchor, constant: 8),
            web.leadingAnchor.constraint(equalTo: content.leadingAnchor),
            web.trailingAnchor.constraint(equalTo: content.trailingAnchor),
            web.bottomAnchor.constraint(equalTo: content.bottomAnchor),
        ])
        window.contentView = content
        window.center()
        return window
    }

    func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
        let origin = message.frameInfo.securityOrigin
        guard !finished, message.name == "uniTradingAuth", message.webView === web,
              LoginPolicy.messageAllowed(protocolName: origin.protocol, host: origin.host, port: origin.port,
                                         mainFrame: message.frameInfo.isMainFrame, currentURL: web.url),
              let session = LoginPolicy.session(message.body, environment: environment) else { return }
        if !Output.write("TOKEN " + session.line) { exit(3) }
        guard session.hasAccessToken, !captured else { return }
        captured = true
        returnButton.isEnabled = true
        statusLabel.stringValue = "网页登录成功，正在返回应用。"
        if !Output.write("STATUS SESSION_CAPTURED") { exit(3) }
        // Leaves time for the rest of the login reply's messages.
        perform(#selector(finishLogin), with: nil, afterDelay: 1.5)
    }

    @objc private func finishLogin() {
        guard captured, !finished else { return }
        finished = true
        _ = Output.write("STATUS RETURNING")
        exit(Output.write("DONE") ? 0 : 3)
    }

    @objc private func cancelLogin() {
        guard !finished else { return }
        finished = true
        _ = Output.write("ERROR login_cancelled")
        exit(2)
    }

    func windowWillClose(_ notification: Notification) {
        if captured { finishLogin() } else { cancelLogin() }
    }

    func webView(_ webView: WKWebView, decidePolicyFor navigationAction: WKNavigationAction, decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        if navigationAction.shouldPerformDownload { decisionHandler(.cancel); return }
        // Frames are isolated by WebKit; a top-level destination must be an official host.
        let top = navigationAction.targetFrame?.isMainFrame ?? true
        if top && !LoginPolicy.navigationAllowed(navigationAction.request.url) {
            statusLabel.stringValue = "已阻止非 Tradovate 官方登录地址。"
            decisionHandler(.cancel)
            return
        }
        decisionHandler(.allow)
    }

    func webView(_ webView: WKWebView, decidePolicyFor navigationResponse: WKNavigationResponse, decisionHandler: @escaping (WKNavigationResponsePolicy) -> Void) {
        decisionHandler(navigationResponse.canShowMIMEType ? .allow : .cancel)
    }

    func webView(_ webView: WKWebView, createWebViewWith configuration: WKWebViewConfiguration, for navigationAction: WKNavigationAction, windowFeatures: WKWindowFeatures) -> WKWebView? {
        if LoginPolicy.navigationAllowed(navigationAction.request.url) { web.load(navigationAction.request) }
        else { statusLabel.stringValue = "已阻止外部窗口；请在此官方登录窗口继续。" }
        return nil
    }

    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
        let failure = error as NSError
        if failure.domain == NSURLErrorDomain && failure.code != NSURLErrorCancelled {
            statusLabel.stringValue = "网页加载失败。请检查网络后重新打开登录窗口。"
        }
    }

    func webViewWebContentProcessDidTerminate(_ webView: WKWebView) {
        _ = Output.write("ERROR browser_process_failed")
        exit(3)
    }
}

func runSelfTest() -> Int32 {
    var passed = 0
    var failed = false
    func check(_ condition: Bool) {
        if condition { passed += 1 } else { failed = true }
    }
    let official = URL(string: "https://trader.tradovate.com/welcome")
    check(LoginPolicy.navigationAllowed(official))
    check(LoginPolicy.navigationAllowed(URL(string: "https://auth.tradovate.com/login")))
    check(LoginPolicy.navigationAllowed(URL(string: "https://login.tradovate.com/")))
    for rejected in ["http://trader.tradovate.com/", "https://trader.tradovate.com.evil.example/", "https://user@trader.tradovate.com/",
                     "https://trader.tradovate.com:8443/", "https://random.tradovate.com/", "file:///tmp/test.html"] {
        check(!LoginPolicy.navigationAllowed(URL(string: rejected)))
    }
    check(!LoginPolicy.navigationAllowed(nil))
    check(LoginPolicy.messageAllowed(protocolName: "https", host: "trader.tradovate.com", port: 443, mainFrame: true, currentURL: official))
    check(LoginPolicy.messageAllowed(protocolName: "https", host: "trader.tradovate.com", port: 0, mainFrame: true, currentURL: official))
    check(!LoginPolicy.messageAllowed(protocolName: "https", host: "trader.tradovate.com", port: 443, mainFrame: false, currentURL: official))
    check(!LoginPolicy.messageAllowed(protocolName: "http", host: "trader.tradovate.com", port: 443, mainFrame: true, currentURL: official))
    check(!LoginPolicy.messageAllowed(protocolName: "https", host: "evil.example", port: 443, mainFrame: true, currentURL: official))
    check(!LoginPolicy.messageAllowed(protocolName: "https", host: "trader.tradovate.com", port: 8443, mainFrame: true, currentURL: official))
    check(!LoginPolicy.messageAllowed(protocolName: "https", host: "trader.tradovate.com", port: 443, mainFrame: true, currentURL: URL(string: "https://auth.tradovate.com/")))
    let message = "{\"kind\":\"auth\",\"environment\":\"demo\",\"accessToken\":\"fake-token-for-offline-test\",\"userId\":123}"
    let accepted = LoginPolicy.session(message, environment: "demo")
    check(accepted?.line == message && accepted?.hasAccessToken == true)
    check(LoginPolicy.session("{\"kind\":\"auth\",\"environment\":\"demo\",\"mdAccessToken\":\"fake\"}", environment: "demo")?.hasAccessToken == false)
    check(LoginPolicy.session(message, environment: "live") == nil)
    check(LoginPolicy.session(message.replacingOccurrences(of: "\"auth\"", with: "\"other\""), environment: "demo") == nil)
    check(LoginPolicy.session("{\"kind\":\"auth\",\n\"environment\":\"demo\"}", environment: "demo") == nil)
    check(LoginPolicy.session(String(repeating: "x", count: LoginPolicy.maximumMessageBytes + 1), environment: "demo") == nil)
    check(LoginPolicy.session("[]", environment: "demo") == nil)
    check(LoginPolicy.session("{", environment: "demo") == nil)
    check(LoginPolicy.session(5, environment: "demo") == nil)
    check(parseOptions(["--profile", "p", "--environment", "demo", "--script", "b.js"])?.count == 3)
    check(parseOptions(["--profile", "p", "--environment", "demo"]) == nil)
    check(parseOptions(["--profile", "p", "--profile", "q", "--script", "b.js"]) == nil)
    check(parseOptions(["--other", "p", "--environment", "demo", "--script", "b.js"]) == nil)
    check(Output.write("SELF_TEST \(failed ? "FAIL" : "PASS") \(passed)"))
    return failed ? 1 : 0
}

func parseOptions(_ arguments: [String]) -> [String: String]? {
    guard arguments.count == 6 else { return nil }
    var options: [String: String] = [:]
    var index = 0
    while index < arguments.count {
        let key = arguments[index]
        let value = arguments[index + 1]
        guard ["--profile", "--environment", "--script"].contains(key), options[key] == nil,
              !value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return nil }
        options[key] = value
        index += 2
    }
    return options.count == 3 ? options : nil
}

signal(SIGPIPE, SIG_IGN)
let launchArguments = Array(CommandLine.arguments.dropFirst())
if launchArguments == ["--self-test"] { exit(runSelfTest()) }
guard let options = parseOptions(launchArguments), let environment = options["--environment"], LoginPolicy.isEnvironment(environment),
      let scriptPath = options["--script"],
      let scriptSize = (try? FileManager.default.attributesOfItem(atPath: scriptPath))?[.size] as? NSNumber,
      scriptSize.intValue > 0, scriptSize.intValue <= LoginPolicy.maximumScriptBytes,
      let script = try? String(contentsOfFile: scriptPath, encoding: .utf8) else {
    _ = Output.write("ERROR invalid_configuration_or_browser_failure")
    exit(3)
}
let application = NSApplication.shared
let controller = LoginController(environment: environment, script: script)
application.delegate = controller
application.setActivationPolicy(.regular)
application.run()
