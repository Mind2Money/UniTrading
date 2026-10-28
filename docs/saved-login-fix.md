# Windows / Android 登录保存修复

日期：2026-10-28；版本：1.1.3。

## 原因与行为

原程序只持久化本地 Demo 账本。Tradovate 的注册插件、账户目录、令牌均只在内存，浏览器保存密码不会让主程序重启后恢复账户。

登录交接成功后加密保存会话，账户服务器暂时不可用时仍保留本次认证。启动恢复以环境和用户 ID 区分登录，从服务器重新获取账户、仓位、订单和行情，不从本地生成远程账户快照。上次交易商及子账户选择一并保存。有效期不足两分钟时先续期，运行中续期也更新保存的会话。

过期会话不直接连接；撤销授权、网络故障、损坏文件、存储失败分别提示。网络故障不删除凭据，重试会优先补写此前失败的保存。失败的登录恢复不阻止其他已保存登录恢复。损坏文件不会因切换账户被覆盖。

账户页提供重试恢复和清除全部已保存登录。清除需要确认，删除应用会话并断开连接；旧连接的延迟续期回调不能将凭据写回来。浏览器密码与服务器订单/仓位不受影响。

## 平台存储

- Windows：JNA 调用当前用户范围的 DPAPI，禁止系统交互，不使用机器共享范围。先加密再原子替换文件，临时文件只有密文，无明文降级路径。参考 [JNA Crypt32Util](https://java-native-access.github.io/jna/3.5.0/javadoc/com/sun/jna/platform/win32/Crypt32Util.html)。
- Android：AndroidKeyStore 生成不可导出的 AES-256 密钥，GCM 每次生成新的 IV；密文写入 `noBackupFilesDir`，使用 `AtomicFile`。参考 [Android KeyGenParameterSpec](https://developer.android.com/reference/android/security/keystore/KeyGenParameterSpec)。
- 会话不写入 Demo 快照、日志、命令行或剪贴板，不读取密码字段。旧版本升级后需登录一次建立存档。

## 验证范围

自动测试使用合成令牌和模拟服务端，不访问真实交易账户，不发送交易指令。覆盖多个用户和 Demo/Live、子账户选择、重启恢复、过期、断网重试、超时、损坏存档、磁盘失败、续期保存、旧回调失效、清除登录、DPAPI 加解密与篡改拒绝。

Android 凭据测试使用独立 `com.unitrading.demo.credentialtest` 包名，避免覆盖正常安装。`processRestart` 测试可通过 `credentialPhase=write/read` 在两个独立 instrumentation 进程间验证恢复。

本轮结果：119 项 JVM/KMP/Compose 测试通过，0 失败、0 跳过（broker-api 3、demo-broker 28、tradovate-broker 48、composeApp 40）。Android API 36 模拟器上的实际 Keystore 加解密、密文篡改拒绝通过；先写入、force-stop、再启动独立 instrumentation 进程读取也通过。三次 instrumentation 调用均返回 `OK (1 test)`。

```powershell
./gradlew.bat :broker-api:desktopTest :demo-broker:desktopTest :tradovate-broker:desktopTest :composeApp:desktopTest
./gradlew.bat :androidApp:assembleDebug :androidApp:assembleDebugAndroidTest -PcredentialStoreTest=true
```

安装隔离 APK 及 test APK 后，用 `adb shell am instrument -w -e class com.unitrading.android.AndroidCredentialStoreTest#processRestart -e credentialPhase write com.unitrading.demo.credentialtest.test/androidx.test.runner.AndroidJUnitRunner` 写入；执行 `am force-stop com.unitrading.demo.credentialtest` 后，将 phase 改为 `read` 验证跨进程读取。

交付正常安装包时不传 `credentialStoreTest`，保持 `com.unitrading.demo` 应用 ID，版本号为 1.1.3 / versionCode 5。

Windows `:composeApp:createDistributable` 与正常包名的 Android `:androidApp:assembleDebug` 均构建成功。编译使用 JDK 21 toolchain；Android Studio 的 JBR 缺少 `jpackage.exe`，桌面打包改用本机 Microsoft JDK 25.0.2。Windows 输出为 `composeApp/build/compose/binaries/main/app/UniTrading/UniTrading.exe`（须保留同目录依赖），Android 输出为 `androidApp/build/outputs/apk/debug/androidApp-debug.apk`（调试签名）。APK SHA-256：`8cb7a42f4e98e4f69085d075d60baa97418fc1c843658262abbd1ee2235accda`。

真实 Tradovate 服务端撤销/到期后的认证仍由官方决定；本轮不把模拟测试当作真实账户免登录实测。iOS 未验证。
