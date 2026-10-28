# 1.1.4：主题、导航与图表

## 用户界面

Android 紧凑布局使用 Material 3 `NavigationBar` / `NavigationBarItem`，包含行情、图表、交易、账户四个同级入口。每个入口都有 24 dp 图标、固定文字标签及选中指示背景；应用安全区处理系统导航栏，组件不重复添加底部 inset。参考 [Android Navigation bar](https://developer.android.com/develop/ui/compose/components/navigation-bar)。

首次启动显示登录页，不注册本地模拟券商、不加载旧模拟账本。有效的已保存 Tradovate 会话仍会自动恢复；断开最后一个账户或清除所有登录后回到登录页。保留 `demo-broker` 作为测试依赖，离线测试必须显式传入 `includeDemoBrokers = true`，正式入口不启用。

## 主题

登录页、账户页和桌面顶栏提供主题入口。支持深色、浅色、跟随系统，独立设置页面/面板背景、图表背景、网格、上涨/下跌 K 线、盈利/亏损数字、文字、强调色和 SL/TP 线颜色。颜色圆点打开 RGB 滑块，也可输入 `#RRGGBB`。

编辑时在对话框预览，保存才写入本机 `theme-v1` 设置；取消不改变现有主题。无效值阻止保存，磁盘失败显示错误并保留现有主题。损坏设置回退到默认配色，不阻止登录。清除登录不影响主题。Android 系统栏图标根据页面背景明暗切换。

盈亏颜色作用于浮动盈亏、持仓、已配对历史成交及下单预估；未知和零值保留普通文字色。K 线颜色与盈亏颜色互相独立。

## 图表

- 右侧价格标尺上下滑动缩放价格范围，保持中心价格。
- 底部时间标尺左右滑动调整 K 线密度，保持中心时间坐标。
- 图表空白区域支持连续横向和纵向平移，保留不足一根 K 线的平移量，允许最新 K 线后留白。
- “重置视图”恢复最新 80 根及自动价格范围，切换账户、品种或周期后独立初始化视图。
- 坐标轴手势不命中保护价或挂单线；图表内编辑这些线仍进入原有预览/确认流程。绘制裁剪到图表区域，超出价格范围的标签不挤入坐标轴。
- 可见 K 线数量限制为 12–600，价格范围至少为 4 个 tick；历史边界保留适量空白，不能拖到无限远。

## 验证

新增主题保存/损坏/磁盘失败、默认空交易商、最后一个已登录交易商清除、连续坐标几何、轴缩放、二维平移、重置视图、Material 导航和主题取消/保存测试。原有保护价、挂单改价和登录恢复测试继续运行。

Android UI instrumentation 使用独立 `.credentialtest` 应用 ID。登录页使用真实 Activity；图表/导航由测试注入的合成 Demo 数据驱动，不读取真实交易账户、不发送交易。屏幕截图输出到 `artifacts/android-appearance`，Compose 截图输出到 `artifacts/mobile-navigation-chart.png`、`theme-custom-preview.png` 和 `login-light.png`。

验收结果：127 项 JVM/KMP/Compose 测试通过，0 失败、0 跳过；Android instrumentation 返回 `OK (1 test)`，实际验证登录页、主题选择/保存、四项导航、价格轴和时间轴触摸缩放、账户页入口以及 Activity 重建后恢复浅色主题。Android 屏幕截图已检查：底部图标和标签可见，主题编辑页可滚动，保存按钮保持可访问，浅色页面采用深色系统栏图标。

```powershell
./gradlew.bat :broker-api:desktopTest :demo-broker:desktopTest :tradovate-broker:desktopTest :composeApp:desktopTest
./gradlew.bat :androidApp:assembleDebug :androidApp:assembleDebugAndroidTest -PcredentialStoreTest=true
# 安装独立测试应用和测试 APK 后：
adb shell am instrument -w -e class com.unitrading.android.AppearanceUiTest com.unitrading.demo.credentialtest.test/androidx.test.runner.AndroidJUnitRunner
```

UI 设备测试使用真实触摸事件，并等待 Android 无障碍树更新；不依赖点击虚拟节点后立即读取尚未更新的界面状态。图表自由平移、复位和交易线隔离另由 Compose 手势测试覆盖。真机及 iOS 未验证。

正常包名 `com.unitrading.demo` 的 Android APK（1.1.4 / versionCode 6，调试签名）及 Windows 可运行目录均已构建成功。APK：`androidApp/build/outputs/apk/debug/androidApp-debug.apk`，SHA-256 为 `6a5319dae269c01f619a039f5fd2735bf47e49c072d35f75d9174d3ddbfec386`。Windows：`composeApp/build/compose/binaries/main/app/UniTrading/UniTrading.exe`，需保留同目录依赖。
