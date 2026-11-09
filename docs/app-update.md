# 应用内更新

入口在设置页的“关于与更新”。只有用户点击时才访问 GitHub，没有后台检查、后台下载或自动安装。

| 运行方式 | 这一组的内容 |
| --- | --- |
| Windows 打包版、Android | 检查更新、一键更新、更新日志 |
| 从源码运行、`--local-demo`、macOS 包 | 只能检查，发现新版本时打开发布页下载，并有更新日志；版本号取 `RithmicCredentials.DEFAULT_APP_VERSION`（打包版用 `jpackage.app-version`），从源码运行时不代表代码有多新，所以没有更高版本时显示“没有更高的正式版本” |
| iOS | 不显示 |

## 流程

| 步骤 | Windows（打包版） | Android |
| --- | --- | --- |
| 检查 | 匿名读取 `Mind2Money/UniTrading` 的 latest release，不带任何凭据 | 同左 |
| 选包 | `UniTrading-<版本>-windows-x64.zip` | `UniTrading-<版本>-<提交>.apk` |
| 下载 | 先走 API 资源地址，失败时换 release 下载地址；跳转只跟 GitHub 自己的域名；核对大小和 SHA-256 | 同左 |
| 本机检查 | 解压成 `payload` 和 `helper` 两份，启动 helper 副本的 `--version`，输出必须等于目标版本 | 包名和 `versionName` 必须对应，`versionCode` 必须升高，签名证书必须与已安装应用相同 |
| 安装 | helper 等旧版退出，把旧文件改名移开、新文件移入，出错时回滚，再打开新版 | 打开系统安装界面，由用户确认；首次还要在系统页面允许本应用安装未知应用 |
| 收尾 | 新版首次启动报告结果并删除 `update-staging` | 下次启动时删除缓存的 APK |

Windows 的做法来自 BatchTradovate：jpackage 启动器把应用放在 kill-on-close 的 job 里，应用的子进程会随它退出，只有 shell 能启动活得更久的进程，所以用 `explorer.exe` 打开 helper。

## 什么时候不能更新

任一已连接的远程账户（Tradovate、Rithmic，含 Demo / Paper）有持仓或挂单时，更新按钮不可用，并写明原因。账户未连接、未同步完成或有待核对的指令（`tradingReady` 为 false）时状态无法确认，同样不能更新。本地模拟账户不计入。

下载开始前和下载完成后各检查一次。下载期间开了仓，更新停在“已下载并校验”，账户清空后由用户点击重启或安装。

依据：持仓和挂单留在券商服务器上，不受应用退出影响，但重启期间无法查看或修改。规则在 `BrokerRegistry.updateBlock()`。被拦住时发布页入口仍可用，可以手动下载。

## 校验的边界

- SHA-256 由 GitHub 在上传资源时计算，和安装包来自同一个仓库。它能发现下载损坏，不能证明发布者身份。
- Windows 包没有代码签名。Android 由系统比对签名证书，与已安装应用不同的 APK 装不上；调试版遇到正式版会在下载后提示签名不同。
- Windows 程序目录不可写时（例如装在 `Program Files`）更新失败，当前版本不变，需要手动更新。
- 桌面端打开了 `java.net.useSystemProxies`，让更新走系统代理；券商连接用 Ktor 自己的 socket，不受影响。

## 发布约定

旧版本靠这些约定更新到新版本，改动前要考虑已经发出去的版本：

- Release 资源名：`UniTrading-<版本>-windows-x64.zip`、`UniTrading-<版本>-<7 位及以上十六进制提交>.apk`，各只能有一个匹配项。
- zip 只有一个根目录 `UniTrading/`，里面有 `UniTrading.exe`、`app`、`runtime`。
- 打包后的程序支持 `--version`（输出 `jpackage.app-version`），无参数启动时先判断自己是不是 helper 副本。
- `update-staging/helper/update-job.json` 的位置和字段不变。

第一个带此功能的版本本身仍要手动安装一次。

## 代码入口

| 文件 | 内容 |
| --- | --- |
| `composeApp/src/commonMain/kotlin/com/unitrading/app/AppUpdates.kt` | 类型、GitHub 响应解析、版本比较、拦截规则 |
| `composeApp/src/jvmAndAndroidMain/kotlin/com/unitrading/app/ReleaseUpdater.kt` | 下载、校验和状态机，桌面与 Android 共用 |
| `composeApp/src/desktopMain/kotlin/com/unitrading/SelfUpdate.kt` | Windows 解压、helper、替换 |
| `androidApp/src/main/kotlin/com/unitrading/android/ApkUpdateTarget.kt` | APK 检查、打开系统安装界面 |
| `composeApp/src/commonMain/kotlin/com/unitrading/ui/UpdateControls.kt` | 设置页界面 |

## 验证

2026-11-08，用只在本机打的临时补丁让应用从本地文件取“新版本”、自动开始更新，其余代码与提交相同：

- Windows：`createDistributable` 打包的 1.3.1 更新到打包的 9.9.9。打包运行时实际读取了 GitHub API 并下载了 v1.3.1 的资源开头；`--version` 校验、`explorer.exe` 交接、等待旧版退出、替换、打开新版、新版报告结果并清除 `update-staging` 均实际运行。
- Android：API 36 模拟器上 debug 包 1.3.0 更新到 9.9.9。读取 GitHub API 和下载 APK 开头、未知来源授权、系统安装确认、安装后清除缓存均实际运行。用其他证书重签的 APK 在打开安装界面前被拒绝，提示签名不同。
- 设备测试 `ApkUpdateTargetTest` 在 debug 和 R8 release 构建上通过，覆盖 FileProvider 和路径表在压缩后仍可用。

未验证：CI 签名的正式版之间的更新（要等第一个带此功能的版本发布后才有）；真机安装流程；通过系统代理下载；在界面上手动点击的完整路径（点击到调用的部分由 Compose 测试覆盖）。
