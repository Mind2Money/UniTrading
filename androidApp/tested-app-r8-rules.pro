# Applied to the app only when device tests target the shrunk release build
# (-PinstrumentationBuildType=release); the published APK never uses these rules.
# AGP leaves dependencies the app already ships out of the test APK, so everything
# the tests and AndroidX Test call must survive app shrinking and class merging.

# AndroidX Test runtime dependencies shared with the app.
-keep class androidx.tracing.** { *; }
-keep class androidx.concurrent.futures.** { *; }
-keep interface com.google.common.util.concurrent.ListenableFuture { *; }
-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }

# Application, Compose and AndroidX APIs the device tests call directly.
-keep class com.unitrading.android.AndroidCredentialStore { *; }
-keep class com.unitrading.android.TradingRuntimeModel { *; }
-keep class com.unitrading.android.ApkUpdateTarget { *; }
-keep class com.unitrading.android.TradovateLoginActivityKt { *; }
-keep class com.unitrading.android.LoginBrowser { *; }
-keep class com.unitrading.tradovate.TradovateLoginScript { *; }
-keep class com.unitrading.tradovate.TradovateEnvironment { *; }
-keep class org.mozilla.geckoview.GeckoResult { *; }
-keep class org.mozilla.geckoview.GeckoRuntime { *; }
-keep class org.mozilla.geckoview.WebExtension { *; }
-keep class org.mozilla.geckoview.WebExtension$MetaData { *; }
-keep class org.mozilla.geckoview.WebExtensionController { *; }
-keep class com.unitrading.app.ReleasePackage { *; }
-keep class com.unitrading.app.UpdateFailure { *; }
-keep class androidx.core.content.FileProvider { *; }
-keep class com.unitrading.app.MemorySnapshotStore { *; }
-keep class com.unitrading.app.LicenseKt { *; }
-keep class com.unitrading.app.LicenseKey { *; }
-keep class com.unitrading.app.ThemeController { *; }
-keep class com.unitrading.app.ThemePreset { *; }
-keep class com.unitrading.app.ThemeSettings { *; }
-keep class com.unitrading.app.TradingRuntime { *; }
-keep class com.unitrading.app.TradingPreferencesController { *; }
-keep class com.unitrading.app.TradingPreferences { *; }
-keep class com.unitrading.broker.BrokerRegistry { *; }
-keep interface com.unitrading.broker.BrokerPlugin { *; }
-keep interface com.unitrading.broker.AccountSession { *; }
-keep class com.unitrading.ui.TradingScreenKt { *; }
-keep class com.unitrading.android.LivePnlNotifier { *; }
-keep class com.unitrading.app.LivePnlUpdate { *; }
-keep class com.unitrading.app.LivePnlUpdate$Companion { *; }
-keep class com.unitrading.broker.AccountInfo { *; }
-keep class com.unitrading.broker.AccountSnapshot { *; }
-keep class com.unitrading.broker.BrokerDescriptor { *; }
-keep class androidx.activity.SystemBarStyle** { *; }
-keep class androidx.activity.compose.ComponentActivityKt { *; }
-keep class androidx.lifecycle.ViewModelProvider { *; }
-keep interface androidx.compose.runtime.Composer { *; }
# Compose strips these tracing helpers from release apps, but the test's own composable lambda calls them.
-keep class androidx.compose.runtime.ComposerKt { *; }
-keep class androidx.compose.runtime.internal.ComposableLambdaKt { *; }
