using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Text;
using System.Threading.Tasks;
using System.Web.Script.Serialization;
using System.Windows.Forms;
using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.WinForms;

namespace UniTrading.NativeAuth
{
    internal static class LoginPolicy
    {
        internal const int MaximumMessageBytes = 128 * 1024;
        internal const int MaximumTokenLength = 32768;
        private static readonly HashSet<string> NavigationHosts = new HashSet<string>(StringComparer.OrdinalIgnoreCase)
        {
            "trader.tradovate.com", "auth.tradovate.com", "login.tradovate.com"
        };

        internal static bool IsEnvironment(string value)
        {
            return value == "demo" || value == "live";
        }

        private static Uri SecureUri(string value)
        {
            Uri uri;
            if (String.IsNullOrEmpty(value) || !Uri.TryCreate(value, UriKind.Absolute, out uri)) return null;
            if (!String.Equals(uri.Scheme, "https", StringComparison.OrdinalIgnoreCase) || uri.Port != 443 || uri.UserInfo.Length != 0) return null;
            return uri;
        }

        internal static bool IsAllowedNavigation(string value)
        {
            var uri = SecureUri(value);
            return uri != null && NavigationHosts.Contains(uri.IdnHost);
        }

        internal static bool IsTraderDocument(string value)
        {
            var uri = SecureUri(value);
            return uri != null && String.Equals(uri.IdnHost, "trader.tradovate.com", StringComparison.OrdinalIgnoreCase);
        }

        private static readonly HashSet<string> AuthenticationHosts = new HashSet<string>(StringComparer.OrdinalIgnoreCase)
        {
            "live.tradovateapi.com", "demo.tradovateapi.com", "tv.tradovateapi.com", "tv-demo.tradovateapi.com"
        };

        internal static bool IsAuthenticationResponse(string value, string environment)
        {
            if (!IsEnvironment(environment)) return false;
            var uri = SecureUri(value);
            if (uri == null || !AuthenticationHosts.Contains(uri.IdnHost)) return false;
            return System.Text.RegularExpressions.Regex.IsMatch(uri.AbsolutePath,
                @"^/(v1/)?auth/[a-z][a-z0-9_-]*$", System.Text.RegularExpressions.RegexOptions.IgnoreCase);
        }

        internal static string VisibleOrigin(string value)
        {
            var uri = SecureUri(value);
            return uri != null && IsAllowedNavigation(value) ? uri.GetLeftPart(UriPartial.Authority) : "https://trader.tradovate.com";
        }

        private static Dictionary<string, object> ParseObject(string json)
        {
            if (String.IsNullOrEmpty(json) || json.Length > MaximumMessageBytes || Encoding.UTF8.GetByteCount(json) > MaximumMessageBytes) return null;
            try
            {
                return new JavaScriptSerializer { MaxJsonLength = MaximumMessageBytes, RecursionLimit = 12 }.DeserializeObject(json) as Dictionary<string, object>;
            }
            catch (ArgumentException) { return null; }
            catch (InvalidOperationException) { return null; }
        }

        private static bool CopyToken(Dictionary<string, object> input, Dictionary<string, object> output, string key)
        {
            object raw;
            if (!input.TryGetValue(key, out raw) || raw == null) return true;
            var token = raw as string;
            if (token == null) return false;
            if (token.Length == 0) return true;
            if (token.Length < 20 || token.Length > MaximumTokenLength) return false;
            foreach (char c in token)
            {
                if (!(c >= 'A' && c <= 'Z') && !(c >= 'a' && c <= 'z') && !(c >= '0' && c <= '9') &&
                    c != '.' && c != '-' && c != '_' && c != '~' && c != '+' && c != '/' && c != '=') return false;
            }
            output[key] = token;
            return true;
        }

        private static readonly string[] RoutingKeys = { "demo", "live", "mdDemo", "mdLive" };

        // The login reply's routing as received: the app decides which hosts it accepts and explains a refusal.
        private static object Routing(object raw)
        {
            var hosts = raw as Dictionary<string, object>;
            if (hosts == null)
            {
                var list = raw as object[];
                return Object.Equals(raw, "") || list != null && list.Length == 0 ? null : "<invalid>";
            }
            var routing = new Dictionary<string, object>();
            foreach (var key in RoutingKeys)
            {
                object value;
                if (!hosts.TryGetValue(key, out value) || value == null || Object.Equals(value, "")) continue;
                var host = value as string;
                routing[key] = host != null && host.Length <= 253 ? host : "<invalid>";
            }
            return routing;
        }

        private static Dictionary<string, object> Normalize(Dictionary<string, object> input, string environment)
        {
            if (input == null || !IsEnvironment(environment)) return null;
            var output = new Dictionary<string, object> { { "kind", "auth" }, { "environment", environment } };
            if (!CopyToken(input, output, "accessToken") || !CopyToken(input, output, "mdAccessToken")) return null;
            if (!output.ContainsKey("accessToken") && !output.ContainsKey("mdAccessToken")) return null;

            object raw;
            if (input.TryGetValue("userId", out raw) && raw != null)
            {
                long id;
                // IDs are decimal integers, never floating point or locale-formatted strings.
                if (!(raw is string || raw is int || raw is long) || !Int64.TryParse(Convert.ToString(raw, CultureInfo.InvariantCulture), NumberStyles.None,
                    CultureInfo.InvariantCulture, out id) || id <= 0) return null;
                output["userId"] = id;
            }
            if (input.TryGetValue("userName", out raw) && raw != null)
            {
                var name = raw as string;
                if (name == null || name.Length > 256 || name.Any(Char.IsControl)) return null;
                if (name.Length != 0) output["userName"] = name;
            }
            if (input.TryGetValue("expirationTime", out raw) && raw != null)
            {
                var value = raw as string;
                DateTimeOffset expiry;
                if (value == null || value.Length > 80 || !DateTimeOffset.TryParse(value, CultureInfo.InvariantCulture,
                    DateTimeStyles.RoundtripKind, out expiry)) return null;
                output["expirationTime"] = expiry.ToUniversalTime().ToString("O", CultureInfo.InvariantCulture);
            }
            if (input.TryGetValue("expires_in", out raw) && raw != null)
            {
                long seconds;
                if (!Int64.TryParse(Convert.ToString(raw, CultureInfo.InvariantCulture), out seconds) || seconds < 1 || seconds > 604800) return null;
                output["expires_in"] = seconds;
            }
            if (input.TryGetValue("authSource", out raw) && (Object.Equals(raw, "renewal") || Object.Equals(raw, "response") || Object.Equals(raw, "socket"))) output["authSource"] = raw;
            if (input.TryGetValue("marketUrl", out raw) && raw != null)
            {
                var url = raw as string;
                var expected = environment == "demo" ? "wss://md-demo.tradovateapi.com/v1/websocket" : "wss://md.tradovateapi.com/v1/websocket";
                if (url != expected && !(environment == "demo" && url == "wss://md.tradovateapi.com/v1/websocket")) return null;
                output["marketUrl"] = url;
            }
            if (input.TryGetValue("apiHosts", out raw) && raw != null)
            {
                var routing = Routing(raw);
                if (routing != null) output["apiHosts"] = routing;
            }
            return output;
        }

        internal static Dictionary<string, object> NormalizeMessage(string source, string currentDocument, string environment, string json)
        {
            if (!IsTraderDocument(source) || !IsTraderDocument(currentDocument) || !IsEnvironment(environment)) return null;
            var input = ParseObject(json);
            object kind;
            object messageEnvironment;
            if (input == null || !input.TryGetValue("kind", out kind) || !Object.Equals(kind, "auth") ||
                !input.TryGetValue("environment", out messageEnvironment) || !Object.Equals(messageEnvironment, environment)) return null;
            return Normalize(input, environment);
        }

        internal static Dictionary<string, object> NormalizeResponse(string responseUrl, string currentDocument, string environment, string json)
        {
            if (!IsTraderDocument(currentDocument) || !IsAuthenticationResponse(responseUrl, environment)) return null;
            var input = ParseObject(json);
            if (input == null) return null;
            object wrapped;
            if (input.TryGetValue("d", out wrapped) && wrapped is Dictionary<string, object>) input = (Dictionary<string, object>)wrapped;
            foreach (var key in new[] { "error", "errorText", "p-ticket", "p-captcha" })
            {
                object error;
                if (input.TryGetValue(key, out error) && error != null && !Object.Equals(error, "") && !Object.Equals(error, false)) return null;
            }
            object responseEnvironment;
            if (input.TryGetValue("environment", out responseEnvironment) &&
                (Object.Equals(responseEnvironment, "demo") || Object.Equals(responseEnvironment, "live")) && !Object.Equals(responseEnvironment, environment)) return null;
            object alias;
            if (!input.ContainsKey("accessToken"))
            {
                if (input.TryGetValue("access_token", out alias) || input.TryGetValue("token", out alias)) input["accessToken"] = alias;
            }
            if (!input.ContainsKey("userId") && input.TryGetValue("user_id", out alias)) input["userId"] = alias;
            object name;
            if (!input.ContainsKey("userName") && input.TryGetValue("name", out name)) input["userName"] = name;
            input["authSource"] = new Uri(responseUrl).AbsolutePath.EndsWith("/renewaccesstoken", StringComparison.OrdinalIgnoreCase) ? "renewal" : "response";
            return Normalize(input, environment);
        }

        internal static string Serialize(Dictionary<string, object> value)
        {
            return new JavaScriptSerializer { MaxJsonLength = MaximumMessageBytes }.Serialize(value);
        }
    }

    internal static class PipeOutput
    {
        internal static bool Write(string line)
        {
            try { Console.Out.WriteLine(line); Console.Out.Flush(); return true; }
            catch (IOException) { return false; }
            catch (ObjectDisposedException) { return false; }
        }
    }

    internal sealed class LoginCompletion
    {
        private long? capturedAt;
        private long? settledAt;
        internal bool Captured { get { return capturedAt.HasValue; } }
        internal void Capture(long now, bool remembered)
        {
            if (!capturedAt.HasValue) capturedAt = now;
            if (remembered && (!settledAt.HasValue || now + 1500 < settledAt.Value)) settledAt = now + 1500;
        }
        internal void PasswordStoreChanged(long now) { if (Captured && !settledAt.HasValue) settledAt = now + 1500; }
        internal long Deadline { get { return Math.Min((capturedAt ?? Int64.MaxValue - 60000) + 60000, settledAt ?? Int64.MaxValue); } }
        internal bool ShouldComplete(long now) { return Captured && now >= Deadline; }
        internal int SecondsRemaining(long now) { return Captured ? (int)Math.Max(0, (Deadline - now + 999) / 1000) : 0; }
    }

    internal sealed class LoginWindow : Form
    {
        private readonly string environment;
        private readonly string profile;
        private readonly string script;
        private readonly WebView2 browser;
        private readonly ToolStripLabel originLabel;
        private readonly ToolStripLabel statusLabel;
        private readonly ToolStripButton finishButton;
        private readonly ToolStripButton reloadButton;
        private bool hasAccessToken;
        private bool completed;
        private bool closing;
        private bool cancelled;
        private readonly LoginCompletion completion = new LoginCompletion();
        private readonly Stopwatch clock = Stopwatch.StartNew();
        private readonly System.Windows.Forms.Timer completionTimer = new System.Windows.Forms.Timer { Interval = 250 };
        private string[] passwordFiles;
        private string[] passwordBaseline;
        private int reportedRemaining = -1;
        private string CompletionMarker { get { return Path.Combine(profile, ".unitrading-login-completed"); } }
        private long navigationGeneration;
        internal int ExitResult { get; private set; }

        internal LoginWindow(string environment, string profile, string script)
        {
            this.environment = environment;
            this.profile = profile;
            this.script = script;
            ExitResult = 2;
            Text = "Tradovate 登录 | UniTrading · " + (environment == "live" ? "Live 实盘" : "Demo 模拟");
            StartPosition = FormStartPosition.CenterScreen;
            MinimumSize = new Size(720, 600);
            Size = new Size(1100, 820);
            Font = new Font("Segoe UI", 9F);

            var toolbar = new ToolStrip { Dock = DockStyle.Top, GripStyle = ToolStripGripStyle.Hidden, AutoSize = true };
            originLabel = new ToolStripLabel("https://trader.tradovate.com");
            toolbar.Items.Add(originLabel);
            toolbar.Items.Add(new ToolStripSeparator());
            toolbar.Items.Add(new ToolStripLabel(environment == "live" ? "Live 实盘" : "Demo 模拟"));
            finishButton = new ToolStripButton("立即返回") { Enabled = false };
            finishButton.Click += delegate { FinishLogin(); };
            var cancelButton = new ToolStripButton("取消登录");
            cancelButton.Click += delegate { cancelled = true; Close(); };
            completionTimer.Tick += delegate { AdvanceCompletion(); };
            reloadButton = new ToolStripButton("重新加载") { Enabled = false };
            reloadButton.Click += delegate { if (browser.CoreWebView2 != null) browser.CoreWebView2.Reload(); };
            toolbar.Items.Add(reloadButton);
            toolbar.Items.Add(cancelButton);
            toolbar.Items.Add(finishButton);

            var status = new StatusStrip { Dock = DockStyle.Bottom };
            statusLabel = new ToolStripStatusLabel("正在初始化浏览器。账号密码只在官方网页内输入。") { Spring = true, TextAlign = ContentAlignment.MiddleLeft };
            status.Items.Add(statusLabel);
            browser = new WebView2 { Dock = DockStyle.Fill };
            Controls.Add(browser);
            Controls.Add(toolbar);
            Controls.Add(status);
            Shown += async delegate { await InitializeBrowser(); };
            FormClosing += delegate
            {
                if (!completed && hasAccessToken && !cancelled && ExitResult == 2) CompleteHandoff();
                closing = true;
                completionTimer.Stop();
                completionTimer.Dispose();
                if (!completed && ExitResult == 2) PipeOutput.Write("ERROR login_cancelled");
                browser.Dispose();
            };
        }

        private async Task InitializeBrowser()
        {
            try
            {
                var webEnvironment = await CoreWebView2Environment.CreateAsync(null, profile, null);
                if (closing) return;
                await browser.EnsureCoreWebView2Async(webEnvironment);
                if (closing) return;
                var core = browser.CoreWebView2;
                core.Settings.IsPasswordAutosaveEnabled = true;
                core.Settings.IsGeneralAutofillEnabled = true;
                core.Settings.IsWebMessageEnabled = true;
                core.Settings.AreHostObjectsAllowed = false;
                core.Settings.AreDevToolsEnabled = false;
                core.Settings.AreBrowserAcceleratorKeysEnabled = false;
                core.Settings.AreDefaultContextMenusEnabled = false;
                core.Settings.IsStatusBarEnabled = false;
                core.Settings.IsSwipeNavigationEnabled = false;
                core.NavigationStarting += delegate(object sender, CoreWebView2NavigationStartingEventArgs args)
                {
                    if (!LoginPolicy.IsAllowedNavigation(args.Uri))
                    {
                        args.Cancel = true;
                        statusLabel.Text = "已阻止非 Tradovate 官方登录地址。";
                        return;
                    }
                    navigationGeneration++;
                    originLabel.Text = LoginPolicy.VisibleOrigin(args.Uri);
                };
                core.NewWindowRequested += delegate(object sender, CoreWebView2NewWindowRequestedEventArgs args)
                {
                    args.Handled = true;
                    if (args.IsUserInitiated && LoginPolicy.IsAllowedNavigation(args.Uri)) core.Navigate(args.Uri);
                    else statusLabel.Text = "已阻止外部窗口；请在此官方登录窗口继续。";
                };
                core.DownloadStarting += delegate(object sender, CoreWebView2DownloadStartingEventArgs args) { args.Cancel = true; };
                core.LaunchingExternalUriScheme += delegate(object sender, CoreWebView2LaunchingExternalUriSchemeEventArgs args) { args.Cancel = true; };
                core.PermissionRequested += delegate(object sender, CoreWebView2PermissionRequestedEventArgs args) { args.State = CoreWebView2PermissionState.Deny; };
                core.ServerCertificateErrorDetected += delegate(object sender, CoreWebView2ServerCertificateErrorDetectedEventArgs args)
                {
                    args.Action = CoreWebView2ServerCertificateErrorAction.Cancel;
                    statusLabel.Text = "证书校验失败，登录连接已拒绝。";
                };
                core.NavigationCompleted += delegate(object sender, CoreWebView2NavigationCompletedEventArgs args)
                {
                    if (!args.IsSuccess) statusLabel.Text = "网页加载失败。请检查网络后重新加载。";
                };
                core.ProcessFailed += delegate
                {
                    if (closing) return;
                    ExitResult = 3;
                    PipeOutput.Write("ERROR browser_process_failed");
                    Close();
                };
                // This event is for the top-level document; frame events are deliberately not attached.
                core.WebMessageReceived += OnMessage;
                core.WebResourceResponseReceived += OnResponse;
                await core.AddScriptToExecuteOnDocumentCreatedAsync(script);
                if (closing) return;
                reloadButton.Enabled = true;
                statusLabel.Text = "请在官方网页登录，成功后自动返回。首次登录最多等待 60 秒，供浏览器保存密码。";
                PipeOutput.Write("STATUS WAITING_BROWSER");
                core.Navigate("https://trader.tradovate.com/welcome");
            }
            catch (Exception)
            {
                if (closing) return;
                ExitResult = 3;
                PipeOutput.Write("ERROR browser_initialization_failed");
                statusLabel.Text = "无法启动登录浏览器。请确认 Microsoft Edge WebView2 Runtime 可用。";
                finishButton.Enabled = false;
            }
        }

        private void OnMessage(object sender, CoreWebView2WebMessageReceivedEventArgs args)
        {
            if (closing || browser.CoreWebView2 == null) return;
            try
            {
                var result = LoginPolicy.NormalizeMessage(args.Source, browser.CoreWebView2.Source, environment, args.TryGetWebMessageAsString());
                if (result != null) Publish(result);
            }
            catch (Exception) { /* Untrusted messages are ignored without logging payloads. */ }
        }

        private async void OnResponse(object sender, CoreWebView2WebResourceResponseReceivedEventArgs args)
        {
            if (closing || browser.CoreWebView2 == null || args.Response.StatusCode != 200 ||
                !LoginPolicy.IsAuthenticationResponse(args.Request.Uri, environment) ||
                !LoginPolicy.IsTraderDocument(browser.CoreWebView2.Source)) return;
            var generation = navigationGeneration;
            var responseUrl = args.Request.Uri;
            try
            {
                using (var stream = await args.Response.GetContentAsync())
                {
                    if (stream == null) return;
                    using (var buffer = new MemoryStream())
                    {
                        var bytes = new byte[8192];
                        int count;
                        while ((count = await stream.ReadAsync(bytes, 0, bytes.Length)) > 0)
                        {
                            if (buffer.Length + count > LoginPolicy.MaximumMessageBytes) return;
                            buffer.Write(bytes, 0, count);
                        }
                        if (closing || browser.CoreWebView2 == null || generation != navigationGeneration) return;
                        var json = new UTF8Encoding(false, true).GetString(buffer.ToArray());
                        var result = LoginPolicy.NormalizeResponse(responseUrl, browser.CoreWebView2.Source, environment, json);
                        if (result != null) Publish(result);
                    }
                }
            }
            catch (Exception) { /* Never print authentication bodies, URLs with queries or exception data. */ }
        }

        private void Publish(Dictionary<string, object> result)
        {
            if (closing) return;
            if (!PipeOutput.Write("TOKEN " + LoginPolicy.Serialize(result)))
            {
                ExitResult = 3;
                Close();
                return;
            }
            hasAccessToken = hasAccessToken || result.ContainsKey("accessToken");
            finishButton.Enabled = hasAccessToken;
            if (hasAccessToken)
            {
                if (!completion.Captured)
                {
                    var passwordFolder = Path.Combine(profile, "Default");
                    try { passwordFolder = browser.CoreWebView2.Profile.ProfilePath; } catch (Exception) { }
                    passwordFiles = new[] { "Login Data", "Login Data-journal", "Login Data-wal", "Login Data For Account", "Login Data For Account-journal" }
                        .Select(name => Path.Combine(passwordFolder, name)).ToArray();
                    passwordBaseline = passwordFiles.Select(FileStamp).ToArray();
                    PipeOutput.Write("STATUS SESSION_CAPTURED");
                }
                object source;
                var renewing = result.TryGetValue("authSource", out source) && Object.Equals(source, "renewal");
                completion.Capture(clock.ElapsedMilliseconds, File.Exists(CompletionMarker) || renewing);
                completionTimer.Start();
                AdvanceCompletion();
            }
            else statusLabel.Text = "正在等待官方认证响应。";
        }

        private static string FileStamp(string path)
        {
            try { var file = new FileInfo(path); return file.Exists ? file.Length + ":" + file.LastWriteTimeUtc.Ticks : "missing"; }
            catch (IOException) { return "unavailable"; }
            catch (UnauthorizedAccessException) { return "unavailable"; }
        }

        private void AdvanceCompletion()
        {
            if (closing || cancelled || !hasAccessToken) return;
            if (passwordFiles != null && passwordFiles.Where((path, index) => FileStamp(path) != passwordBaseline[index]).Any())
                completion.PasswordStoreChanged(clock.ElapsedMilliseconds);
            if (completion.ShouldComplete(clock.ElapsedMilliseconds)) { FinishLogin(); return; }
            var remaining = completion.SecondsRemaining(clock.ElapsedMilliseconds);
            statusLabel.Text = "网页登录成功，正在等待浏览器保存密码。" + remaining + " 秒后自动返回，可点击“立即返回”。";
            if (remaining != reportedRemaining)
            {
                reportedRemaining = remaining;
                if (!PipeOutput.Write("STATUS PASSWORD_SAVE " + remaining)) { ExitResult = 3; Close(); }
            }
        }

        private void CompleteHandoff()
        {
            if (completed || !hasAccessToken || cancelled) return;
            PipeOutput.Write("STATUS RETURNING");
            completed = PipeOutput.Write("DONE");
            ExitResult = completed ? 0 : 3;
            if (completed) { try { File.WriteAllText(CompletionMarker, DateTime.UtcNow.ToString("O")); } catch (IOException) {} catch (UnauthorizedAccessException) {} }
        }

        private void FinishLogin()
        {
            if (!hasAccessToken || closing || cancelled) return;
            CompleteHandoff();
            Close();
        }
    }

    internal static class Program
    {
        [STAThread]
        private static int Main(string[] args)
        {
            // A Windows-subsystem process has no console code page; write UTF-8 directly to its inherited pipe.
            Console.SetOut(new StreamWriter(Console.OpenStandardOutput(), new UTF8Encoding(false)) { AutoFlush = true });
            if (args.Length == 1 && args[0] == "--self-test") return SelfTest();
            try
            {
                var options = ParseArguments(args);
                var environment = options["--environment"];
                if (!LoginPolicy.IsEnvironment(environment)) throw new ArgumentException();
                var profile = Path.Combine(Path.GetFullPath(options["--profile"]), environment);
                var scriptPath = Path.GetFullPath(options["--script"]);
                var file = new FileInfo(scriptPath);
                if (!file.Exists || file.Length == 0 || file.Length > 512 * 1024) throw new ArgumentException();
                var script = File.ReadAllText(scriptPath, new UTF8Encoding(false, true));
                Directory.CreateDirectory(profile);
                Application.EnableVisualStyles();
                Application.SetCompatibleTextRenderingDefault(false);
                using (var window = new LoginWindow(environment, profile, script))
                {
                    Application.Run(window);
                    return window.ExitResult;
                }
            }
            catch (Exception)
            {
                PipeOutput.Write("ERROR invalid_configuration_or_browser_failure");
                return 3;
            }
        }

        private static Dictionary<string, string> ParseArguments(string[] args)
        {
            if (args.Length != 6) throw new ArgumentException();
            var result = new Dictionary<string, string>(StringComparer.Ordinal);
            for (int i = 0; i < args.Length; i += 2)
            {
                if (args[i] != "--profile" && args[i] != "--environment" && args[i] != "--script") throw new ArgumentException();
                if (String.IsNullOrWhiteSpace(args[i + 1]) || result.ContainsKey(args[i])) throw new ArgumentException();
                result[args[i]] = args[i + 1];
            }
            if (result.Count != 3) throw new ArgumentException();
            return result;
        }

        private static int SelfTest()
        {
            int tests = 0;
            Action<bool> check = delegate(bool ok) { tests++; if (!ok) throw new InvalidOperationException(); };
            try
            {
                const string trader = "https://trader.tradovate.com/welcome";
                const string endpoint = "https://demo.tradovateapi.com/v1/auth/accesstokenrequest";
                const string token = "fake.session.token_for_offline_test_123456";
                var message = "{\"kind\":\"auth\",\"environment\":\"demo\",\"accessToken\":\"" + token + "\",\"userId\":123}";
                check(LoginPolicy.IsAllowedNavigation(trader));
                check(LoginPolicy.IsAllowedNavigation("https://auth.tradovate.com/login"));
                check(!LoginPolicy.IsAllowedNavigation("https://tradovate.com.attacker.test/"));
                check(!LoginPolicy.IsAllowedNavigation("https://trader.tradovate.com:8443/"));
                check(!LoginPolicy.IsAllowedNavigation("https://user@trader.tradovate.com/"));
                check(!LoginPolicy.IsAllowedNavigation("http://trader.tradovate.com/"));
                check(!LoginPolicy.IsAllowedNavigation("file:///C:/test.html"));
                check(!LoginPolicy.IsAllowedNavigation("https://random.tradovate.com/"));
                check(!LoginPolicy.IsTraderDocument("https://auth.tradovate.com/login"));
                check(LoginPolicy.IsAuthenticationResponse(endpoint, "demo"));
                check(LoginPolicy.IsAuthenticationResponse("https://live.tradovateapi.com/v1/auth/renewaccesstoken", "live"));
                check(LoginPolicy.IsAuthenticationResponse(endpoint, "live"));
                check(LoginPolicy.IsAuthenticationResponse("https://live.tradovateapi.com/v1/auth/accesstokenrequest", "demo"));
                check(!LoginPolicy.IsAuthenticationResponse(endpoint + "/extra", "demo"));
                check(!LoginPolicy.IsAuthenticationResponse("https://demo.tradovateapi.com.evil.test/v1/auth/accesstokenrequest", "demo"));
                check(LoginPolicy.NormalizeMessage(trader, trader, "demo", message) != null);
                check(LoginPolicy.NormalizeMessage("https://untrusted.test/", trader, "demo", message) == null);
                check(LoginPolicy.NormalizeMessage(trader, "https://auth.tradovate.com/", "demo", message) == null);
                check(LoginPolicy.NormalizeMessage(trader, trader, "live", message) == null);
                check(LoginPolicy.NormalizeMessage(trader, trader, "demo", message.Replace("\"auth\"", "\"other\"")) == null);
                check(LoginPolicy.NormalizeMessage(trader, trader, "demo", message.Replace(token, "short")) == null);
                check(LoginPolicy.NormalizeMessage(trader, trader, "demo", message.Replace("\"" + token + "\"", "123456")) == null);
                check(LoginPolicy.NormalizeMessage(trader, trader, "demo", message.Replace(token, "Bearer " + token)) == null);
                check(LoginPolicy.NormalizeMessage(trader, trader, "demo", message.Replace(token, token + "\\nBAD")) == null);
                check(LoginPolicy.NormalizeMessage(trader, trader, "demo", message.Replace(token, new string('x', LoginPolicy.MaximumTokenLength + 1))) == null);
                check(LoginPolicy.NormalizeMessage(trader, trader, "demo", "[]") == null);
                check(LoginPolicy.NormalizeMessage(trader, trader, "demo", "{") == null);
                check(LoginPolicy.NormalizeMessage(trader, trader, "demo", new string('x', LoginPolicy.MaximumMessageBytes + 1)) == null);
                check(LoginPolicy.NormalizeMessage(trader, trader, "demo", message.Replace("123", "-1")) == null);
                check(LoginPolicy.NormalizeMessage(trader, trader, "demo", message.Replace("123}", "123.5}")) == null);
                var extra = message.Substring(0, message.Length - 1) + ",\"password\":\"must-not-be-emitted\",\"extra\":\"ignored\"}";
                var normalized = LoginPolicy.NormalizeMessage(trader, trader, "demo", extra);
                check(normalized != null && !normalized.ContainsKey("password") && !normalized.ContainsKey("extra"));
                check(!LoginPolicy.Serialize(normalized).Contains("must-not-be-emitted"));
                var partial = "{\"kind\":\"auth\",\"environment\":\"demo\",\"mdAccessToken\":\"" + token + "\"}";
                check(LoginPolicy.NormalizeMessage(trader, trader, "demo", partial) != null);
                var response = "{\"accessToken\":\"" + token + "\",\"mdAccessToken\":\"" + token + "\",\"userId\":123,\"name\":\"offline\",\"expirationTime\":\"2030-01-01T00:00:00Z\"}";
                var result = LoginPolicy.NormalizeResponse(endpoint, trader, "demo", response);
                check(result != null && Object.Equals(result["userName"], "offline") && result.ContainsKey("expirationTime"));
                check(LoginPolicy.NormalizeResponse(endpoint, trader, "live", response.Replace("\"userId\":123", "\"environment\":\"demo\",\"userId\":123")) == null);
                check(LoginPolicy.NormalizeResponse(endpoint, "https://outside.test/", "demo", response) == null);
                check(LoginPolicy.NormalizeResponse(endpoint, trader, "demo", response.Replace("\"userId\":123", "\"errorText\":\"denied\"")) == null);
                check(LoginPolicy.NormalizeResponse(endpoint, trader, "demo", "{\"p-ticket\":\"challenge\"}") == null);
                check(LoginPolicy.NormalizeResponse(endpoint, trader, "demo", response.Replace("2030-01-01T00:00:00Z", "invalid")) == null);
                check(LoginPolicy.VisibleOrigin(trader + "?secret=not-logged") == "https://trader.tradovate.com");
                check(ParseArguments(new[] { "--profile", "profile", "--environment", "demo", "--script", "bridge.js" }).Count == 3);
                var shared = LoginPolicy.NormalizeResponse("https://live.tradovateapi.com/v1/auth/accesstokenrequest", trader, "demo", response);
                check(shared != null && Object.Equals(shared["environment"], "demo"));
                var wrapped = "{\"d\":{\"access_token\":\"" + token + "\",\"user_id\":123,\"expires_in\":3600}}";
                var aliases = LoginPolicy.NormalizeResponse("https://tv-demo.tradovateapi.com/v1/auth/session", trader, "demo", wrapped);
                check(aliases != null && aliases.ContainsKey("accessToken") && aliases.ContainsKey("userId") && aliases.ContainsKey("expires_in"));
                var routed = LoginPolicy.NormalizeResponse("https://live.tradovateapi.com/v1/auth/accesstokenrequest", trader, "demo", response.Replace("\"userId\":123",
                    "\"apiHosts\":{\"demo\":\"demo-api.org.ninjatrader.com\",\"mdDemo\":\"md-demo-api.org.ninjatrader.com\",\"live\":null,\"reportingDemo\":\"rpt.org.ninjatrader.com\"},\"userId\":123"));
                var routes = routed != null ? routed["apiHosts"] as Dictionary<string, object> : null;
                check(routes != null && routes.Count == 2 && Object.Equals(routes["demo"], "demo-api.org.ninjatrader.com") && Object.Equals(routes["mdDemo"], "md-demo-api.org.ninjatrader.com"));
                check(LoginPolicy.Serialize(routed).Contains("md-demo-api.org.ninjatrader.com") && !LoginPolicy.Serialize(routed).Contains("rpt.org"));
                var routedMessage = message.Substring(0, message.Length - 1) + ",\"apiHosts\":{\"demo\":\"demo-api.org.ninjatrader.com\"}}";
                check(((Dictionary<string, object>)LoginPolicy.NormalizeMessage(trader, trader, "demo", routedMessage)["apiHosts"]).Count == 1);
                var unusable = LoginPolicy.NormalizeResponse(endpoint, trader, "demo", response.Replace("\"userId\":123", "\"apiHosts\":\"elsewhere\",\"userId\":123"));
                check(unusable != null && Object.Equals(unusable["apiHosts"], "<invalid>"));
                var numeric = LoginPolicy.NormalizeResponse(endpoint, trader, "demo", response.Replace("\"userId\":123", "\"apiHosts\":{\"demo\":5},\"userId\":123"));
                check(numeric != null && Object.Equals(((Dictionary<string, object>)numeric["apiHosts"])["demo"], "<invalid>"));
                foreach (var empty in new[] { "null", "\"\"", "[]" })
                {
                    var unrouted = LoginPolicy.NormalizeResponse(endpoint, trader, "demo", response.Replace("\"userId\":123", "\"apiHosts\":" + empty + ",\"userId\":123"));
                    check(unrouted != null && !unrouted.ContainsKey("apiHosts"));
                }
                var first = new LoginCompletion();
                check(!first.ShouldComplete(999999));
                first.Capture(100, false);
                check(first.SecondsRemaining(100) == 60);
                first.Capture(30000, false);
                check(!first.ShouldComplete(60099));
                check(first.ShouldComplete(60100));
                var remembered = new LoginCompletion();
                remembered.Capture(0, true);
                check(!remembered.ShouldComplete(1499));
                check(remembered.ShouldComplete(1500));
                var saved = new LoginCompletion();
                saved.Capture(0, false); saved.PasswordStoreChanged(2000);
                check(!saved.ShouldComplete(3499));
                check(saved.ShouldComplete(3500));
                check(saved.SecondsRemaining(4000) == 0);
                PipeOutput.Write("SELF_TEST PASS " + tests.ToString(CultureInfo.InvariantCulture));
                return 0;
            }
            catch (Exception)
            {
                PipeOutput.Write("SELF_TEST FAIL " + tests.ToString(CultureInfo.InvariantCulture));
                return 1;
            }
        }
    }
}
