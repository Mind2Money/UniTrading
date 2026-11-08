# Windows Tradovate 登录窗口

独立的 x64 WinForms / WebView2 host，由 Kotlin Desktop 启动，通过重定向的进程管道通信。只用于用户主动打开的 Tradovate 官方登录页，不读取其他浏览器的密码、cookie 或配置。

host 当前独立版本为 1.1.1，与主应用 1.1.4 的版本号不同。主应用收到登录信息后通过 DPAPI 保存会话，host 的管道协议和浏览器 profile 路径未因此改变；见[当前状态](../docs/current-status.md)。

## 构建

需要 Windows .NET Framework 4.x 自带 C# 编译器和 Microsoft Edge WebView2 Runtime。不要求安装 .NET SDK。脚本固定 WebView2 SDK `1.0.4314.35` 并验证 NuGet 包 SHA-256：

`90778deb1b77e36292828b4b5833c9255c27533f40e0ddd55891ae718944edc4`

```bash
powershell.exe -NoProfile -ExecutionPolicy Bypass -File native-auth/build.ps1
```

可以传入 `-OutputDir <directory>`。默认输出为 `native-auth/build/1.1.1/UniTrading.TradovateLogin.exe`；同目录的 Core/WinForms/Loader DLL 和许可证文件应一同分发。`build/` 已由仓库通用规则忽略。

脚本先尝试用户 NuGet 缓存；不存在时从固定的官方 NuGet URL 下载。包和所需 SDK 文件分别进入新建隔离目录，不执行包中的脚本。编译及默认离线 self-test 成功后才将完整产物复制到输出根目录。

```bash
native-auth/build/1.1.1/UniTrading.TradovateLogin.exe --self-test
```

## 调用协议

参数仅接受三对配置值，不接受账号密码或 token：

```text
--profile <application-profile-root>
--environment demo|live
--script <application-owned-auth-bridge.js>
```

实际浏览器数据目录为 `<application-profile-root>/<environment>`，Demo 与 Live 分离。固定起始页面为 `https://trader.tradovate.com/welcome`。

Kotlin 必须以 `ProcessBuilder` 重定向 stdout/stderr，并直接消费管道，**不得把进程输出写入终端或日志**：

```text
TOKEN {"kind":"auth","environment":"demo","accessToken":"...","mdAccessToken":"...","userId":123,"userName":"...","expirationTime":"..."}
DONE
```

- `TOKEN` 可以多次发送，每次可能只有部分令牌字段；同环境会话信息由调用者合并。
- JS 通过顶层页面的 `window.chrome.webview.postMessage(JSON.stringify(...))` 发送上述 `kind: auth` 对象。
- host 监听固定官方域名的 `/v1/auth/*` 与 `/auth/*` 单层认证响应，兼容响应包装及标准 token 别名。认证使用共享 live 域名，不据此改变用户选择的 Demo/Live 交易环境；不读取请求密码。
- 只接受来源与当前顶层文档均为 **HTTPS `trader.tradovate.com`、443 端口** 的消息；认证响应来源限定 live/demo/tv/tv-demo 的 `tradovateapi.com` 主机，不接受任意域名。
- 认证响应的 `apiHosts` 只转交 `demo`、`live`、`mdDemo`、`mdLive` 四项，非字符串或过长的值标记为 `<invalid>`。是否接受由应用的 `TradovateHosts.kt` 判断，host 不做连接。
- 输入最多 128 KiB，token 长度为 20 至 32768 且不允许空白/控制字符；未知字段不转发。环境不符、失败/挑战响应和非法消息被忽略。
- 收到账户 token 后自动进入交接，不等待独立行情 token。首次保留最多 60 秒供浏览器保存密码；发现密码库文件的大小/修改时间变化（不读取内容）后等待 1.5 秒，已有完成标记或续期会话也只等待 1.5 秒。
- “立即返回”可跳过等待；登录后直接关闭窗口也完成交接。明确点击“取消登录”仍丢弃会话。
- 管道 `STATUS WAITING_BROWSER/SESSION_CAPTURED/PASSWORD_SAVE <秒数>/RETURNING` 只含阶段，不含凭据；主程序按这些阶段显示登录进度。
- `DONE` 且退出码 0 表示交接成功。取消退出码 2；配置/初始化/管道失败退出码 3。错误行只有固定错误码，不包含异常文本、认证响应或敏感 URL。

## 浏览器与密码

`IsPasswordAutosaveEnabled` 与 `IsGeneralAutofillEnabled` 均开启。密码保存、更新提示与自动填充属于 WebView2 自己的持久 profile；用户决定是否保存。host 不读取或自行存储密码，也不关闭窗口抢占保存提示。

顶层导航允许 `trader.tradovate.com`、`auth.tradovate.com` 和 `login.tradovate.com`；外部 URI、下载、任意新窗口与特权资源访问被阻止。官方同域的新窗口请求在原窗口打开。DevTools、host objects 和默认浏览器快捷键关闭；证书失败不忽略。

第三方 SSO、外部账户恢复页面或未来变更的登录域名可能需要显式新增、审核白名单。当前实现不会自动放宽此限制，也不会绕过 MFA/验证码。连接、市场数据和交易权限必须仍由 Tradovate 服务端验证；网页登录成功并不保证 API 可用。

## 验证状态

构建脚本默认执行不联网的 policy self-test，覆盖环境、origin、端口、URI 用户信息、字段清理、消息大小、token 长度、错误响应和敏感 URL 展示规则。该测试仅验证离线策略。真实 Tradovate 登录和浏览器密码保存需在正式登录窗口中单独验证。

## macOS

`TradovateLogin.swift` 是 macOS 上对应的 host（Apple Silicon，macOS 13 及以上），用 WKWebView 打开同一个官方登录页，向 Kotlin 输出相同的 `TOKEN` / `STATUS` / `ERROR` / `DONE` 行。与 Windows 的差异：

- 只靠注入脚本拿到认证响应，WKWebView 没有 WebResourceResponseReceived 这样的接口。
- 没有密码管理器，收到会话后约 1.5 秒自动返回，不输出 `STATUS PASSWORD_SAVE`。
- `--profile` 只为保持相同的命令行；网站数据按环境由 WebKit 保存。

```bash
bash native-auth/build-macos.sh
native-auth/build/1.1.1/UniTrading.TradovateLogin --self-test
```

`build-macos.sh` 编译后先跑离线自测，通过才写到输出目录。真实登录需要在 Mac 上单独验证。
