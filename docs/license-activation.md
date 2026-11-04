# 授权码激活

状态：Windows / Android / iOS 共用同一套逻辑。桌面单元测试、Compose UI 测试与 Android 模拟器设备测试已通过；未在真机上运行，iOS 未构建。详见文末“验证”。

## 流程

1. 启动时读取本机授权记录。记录有效则直接进入应用，否则显示“输入授权码”页面。
2. 输入授权码，点击“验证并进入”或按回车。验证通过后写入授权记录，进入登录页。
3. 之后启动只校验本机记录，不再要求输入授权码。

授权通过前，交易界面不会组合：已保存的 Tradovate / Rithmic 登录不会恢复，行情和账户连接不会建立，实时盈亏通知也没有可显示的账户。入口为 [LicenseGate](../composeApp/src/commonMain/kotlin/com/unitrading/ui/LicenseGate.kt)，三个平台入口（[Main.kt](../composeApp/src/desktopMain/kotlin/com/unitrading/Main.kt)、[MainActivity](../androidApp/src/main/kotlin/com/unitrading/android/MainActivity.kt)、[MainViewController](../composeApp/src/iosMain/kotlin/com/unitrading/MainViewController.kt)）都用它包住 `TradingScreen`。桌面 `--local-demo` 模式同样需要授权。

## 实现

| 环节 | 做法 |
| --- | --- |
| 授权码存放 | 应用只带 PBKDF2-HMAC-SHA256 验证值：16 字节随机盐，600,000 次迭代（OWASP 2023 建议值），32 字节输出。仓库和安装包都不含授权码明文。比较使用恒定时间算法。见 [License.kt](../composeApp/src/commonMain/kotlin/com/unitrading/app/License.kt) 的 `ProductionLicenseKeys` |
| 加密算法 | SHA-256 / HMAC / PBKDF2 写在共享代码中（[Pbkdf2.kt](../composeApp/src/commonMain/kotlin/com/unitrading/app/Pbkdf2.kt)），因为 iOS 目标没有共用的加密库；测试逐项对照 NIST / RFC 向量和 JDK 实现 |
| 输入处理 | 去掉首尾空白，区分大小写，最长 256 字符。输入框默认隐藏、可切换显示，使用密码键盘，关闭自动大写和纠错；输入内容不写入 Android 的保存状态 |
| 授权记录 | 只保存授权项 ID、激活时间、连续失败次数和锁定截止时间，不保存授权码。整份记录用平台加密存储，与已保存登录分开存放，清除登录不影响授权 |
| 失败限制 | 连续 5 次错误后锁定 30 秒，此后每次错误锁定时间加倍，最长 15 分钟；验证成功后清零。计数保存在加密记录中，重启不重置；时钟回拨不会使剩余锁定超过 15 分钟。空输入和验证器自身故障不计入失败次数 |
| 轮换与吊销 | `ProductionLicenseKeys` 可以同时保留多个授权项。新版本删除某一项后，用该项激活的设备启动时会提示“授权码已更换”，需要输入新授权码 |
| 扩展 | `LicenseVerifier` 接口隔离校验方式，可换成在线校验，界面和存储不变 |

各平台的授权记录：

| 平台 | 位置 | 保护方式 |
| --- | --- | --- |
| Windows | `%LOCALAPPDATA%\UniTrading\credentials-v1\license.dpapi`（`UNITRADING_DATA_DIR` 可改目录） | DPAPI，当前 Windows 用户 |
| Android | 应用私有 `noBackupFilesDir/license-v1.enc` | Keystore AES-256-GCM，独立密钥别名 `unitrading.license.v1`；不参与备份（`allowBackup=false`） |
| iOS | Keychain，service `com.unitrading.license.v1`，account `license` | `AfterFirstUnlockThisDeviceOnly`，不同步到其他设备 |

记录被篡改、无法解密或格式不符时按“未授权”处理，提示重新输入，不会放行。存储写入失败时，本次运行仍可使用，应用会提示下次启动需要重新输入。

## 更换授权码

```bash
python scripts/license-key.py key-2026-12
```

脚本无回显读取授权码（也可通过管道输入一行），生成随机盐和验证值，打印一行 `LicenseKey(...)`。将其加入 `ProductionLicenseKeys`：保留旧项则已激活设备继续可用，删除旧项则这些设备需要输入新授权码。授权码须为 8–256 个可见 ASCII 字符。

用某个授权码核对当前验证值，而不把授权码写进仓库：

```bash
UNITRADING_LICENSE_KEY=<授权码> ./gradlew :composeApp:desktopTest --tests com.unitrading.app.Pbkdf2JdkTest
```

未设置该环境变量时，这项检查会跳过。

## 边界

- 当前是离线校验：验证值随安装包分发，判断全部在客户端完成。它能阻止没有授权码的正常使用，不能阻止反编译或修改安装包绕过校验。
- 验证值可以被离线暴力破解。PBKDF2 只提高每次猜测的成本；授权码由常见词组成时，字典攻击仍然可行。更长的随机授权码可以提高破解成本。
- 同一授权码可以在任意数量的设备上使用。无法单独吊销某台设备，无法统计设备数，也无法设置有效期；只能通过发布新版本整体更换授权码。
- 以上能力需要服务端：服务端校验授权码并限速，签发带签名和有效期的许可证，记录设备并支持吊销；客户端只内置公钥验证签名。届时以在线实现替换 `OfflineLicenseVerifier`。
- iOS 删除应用后，Keychain 项可能仍然保留（系统行为），重新安装后不需要再次输入授权码。
- 应用暂未提供“移除授权”入口。重新测试授权页时，删除上表中的记录文件，或在 Android 上清除应用数据。

## 验证

- `Pbkdf2Test`：SHA-256（NIST，含 100 万字节消息）、HMAC-SHA256（RFC 4231，含超过块长的密钥）、PBKDF2-HMAC-SHA256（RFC 7914 §11，含 80,000 次迭代）。
- `Pbkdf2JdkTest`：0–300 字节逐一与 JDK `MessageDigest` / `Mac` 对照，覆盖全部填充边界；PBKDF2 与 JDK `PBKDF2WithHmacSHA256` 对照（含多块输出）；生产验证值拒绝错误授权码。
- `LicenseControllerTest`：激活只保存记录不保存授权码、重启后免输入、区分大小写、锁定与加倍、重启保留锁定、时钟回拨、授权项更换、记录损坏或无法读取、存储写入失败、验证器故障不计数。
- `LicenseGateTest`：授权前不显示交易界面也不读取已保存登录；错误提示；输入框隐藏/显示；锁定期间按钮不可用并显示等待时间；授权记录未保存时的提示。
- `DesktopCredentialStoreTest`、`NativePlatformBridgeTest`、Android `AndroidCredentialStoreTest`：授权记录与已保存登录分开存放。
- Android `AppearanceUiTest`：无授权记录时启动停在授权页；写入授权记录后进入登录页，后续流程不变。测试不持有生产授权码，而是写入一条授权记录。
- 2026-11-05 结果：`:composeApp:desktopTest` 246 项通过，1 项跳过（未设置 `UNITRADING_LICENSE_KEY` 时的生产授权码检查；设置后单独运行通过）。生产验证值（600,000 次迭代）在本机桌面 JVM 上验证一次约 0.3 秒。`:androidApp:assembleDebug`、`:androidApp:assembleRelease`（R8）编译通过；Android 模拟器（Medium_Phone，API 36）上 `connectedDebugAndroidTest` 与 R8 `connectedReleaseAndroidTest` 各 5 项通过。未在真机上输入授权码，手机上的验证耗时未测。
- iOS：`MainViewController`、Swift Keychain 存储及自检改动尚未在 Mac / Xcode 构建验证。
