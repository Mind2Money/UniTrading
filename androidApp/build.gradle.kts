import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import java.net.URLClassLoader
import java.util.zip.CRC32

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}
android {
    namespace = "com.unitrading.android"
    // GeckoView 159 refuses to link against anything older than 37.2. targetSdk, not this, sets runtime behavior.
    compileSdk { version = release(37) { minorApiLevel = 2 } }
    defaultConfig {
        applicationId = "com.unitrading.demo"
        minSdk = 26
        targetSdk = 36
        versionCode = 12
        versionName = "1.6.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testProguardFiles("test-r8-rules.pro")
        // Separate application ID for credential tests; never replace the normal installation.
        if (providers.gradleProperty("credentialStoreTest").orNull == "true") applicationIdSuffix = ".credentialtest"
    }
    // The two published APKs differ only in the browser behind the Tradovate login window.
    // Same application ID and certificate: either one installs over the other and keeps the app's data.
    flavorDimensions += "loginBrowser"
    productFlavors {
        // Uses the phone's Android System WebView.
        create("standard") { dimension = "loginBrowser"; isDefault = true }
        // Carries GeckoView for phones whose system WebView cannot run the login.
        create("gecko") { dimension = "loginBrowser" }
    }
    buildTypes {
        // CI publishes this variant: R8 code shrinking plus resource shrinking.
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            if (providers.gradleProperty("credentialStoreTest").orNull == "true") signingConfig = signingConfigs.getByName("debug")
            // Device tests link against the shrunk app; the published APK is built without this property.
            if (providers.gradleProperty("instrumentationBuildType").orNull == "release") proguardFiles("tested-app-r8-rules.pro")
        }
    }
    testBuildType = providers.gradleProperty("instrumentationBuildType").getOrElse("debug").also {
        require(it in setOf("debug", "release"))
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures.compose = true
}
kotlin { compilerOptions.jvmTarget.set(JvmTarget.JVM_17) }

/** The JVM build of the Tradovate module: its login script is rendered into the GeckoView extensions at build time. */
val tradovateDesktopRuntime = configurations.create("tradovateDesktopRuntime") {
    isCanBeConsumed = false
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(KotlinPlatformType.attribute, KotlinPlatformType.jvm)
    }
}

/**
 * Writes one extension per environment around TradovateLoginScript, so GeckoView runs the script the other platforms
 * run. A content script has no synchronous way to learn the environment before the page's own scripts start, so the
 * login window installs the folder of the environment it is opening.
 */
abstract class GeckoLoginExtensions : DefaultTask() {
    @get:Classpath abstract val scriptClasses: ConfigurableFileCollection
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE) abstract val template: DirectoryProperty
    @get:Input abstract val appVersionCode: Property<Int>
    @get:OutputDirectory abstract val assets: DirectoryProperty

    @TaskAction fun render() {
        val root = assets.get().asFile.resolve("tradovate-login").apply { deleteRecursively() }
        URLClassLoader(scriptClasses.map { it.toURI().toURL() }.toTypedArray(), ClassLoader.getPlatformClassLoader()).use { loader ->
            val script = loader.loadClass("com.unitrading.tradovate.TradovateLoginScript")
            val environments = loader.loadClass("com.unitrading.tradovate.TradovateEnvironment")
            val forEnvironment = script.getMethod("forEnvironment", environments)
            val instance = script.getField("INSTANCE").get(null)
            environments.enumConstants.forEachIndexed { index, environment ->
                val text = forEnvironment.invoke(instance, environment) as String
                val folder = root.resolve(environments.getMethod("getWire").invoke(environment) as String)
                template.get().asFile.copyRecursively(folder)
                folder.resolve("login.js").writeText(text)
                // Both folders share one extension ID. ensureBuiltIn replaces the installed copy only when this string differs.
                val version = "${appVersionCode.get()}.$index.${CRC32().apply { update(text.toByteArray()) }.value % 1_000_000_000}"
                folder.resolve("manifest.json").apply { writeText(readText().replace("@VERSION@", version)) }
            }
        }
    }
}
val geckoLoginExtensions = tasks.register<GeckoLoginExtensions>("geckoLoginExtensions") {
    scriptClasses.from(tradovateDesktopRuntime)
    template.set(layout.projectDirectory.dir("src/gecko/loginExtension"))
    appVersionCode.set(android.defaultConfig.versionCode)
    assets.set(layout.buildDirectory.dir("generated/geckoLoginExtensions"))
}
androidComponents.onVariants(androidComponents.selector().withFlavor("loginBrowser" to "gecko")) { variant ->
    variant.sources.assets?.addGeneratedSourceDirectory(geckoLoginExtensions, GeckoLoginExtensions::assets)
    // Compressed in the APK: stored uncompressed, Gecko's libraries more than double the download.
    variant.packaging.jniLibs.useLegacyPackaging.set(true)
}

// One ABI, because each one adds about 90 MB. An x86_64 emulator needs -PgeckoAbi=x86_64; the published APK is arm64-v8a.
val geckoAbi = providers.gradleProperty("geckoAbi").getOrElse("arm64-v8a").also { require(it in setOf("arm64-v8a", "x86_64")) }

dependencies {
    implementation(project(":composeApp"))
    implementation(libs.activity.compose)
    "standardImplementation"("androidx.webkit:webkit:1.18.0")
    // Follows Firefox releases. The engine updates only when this version does, so raise it with each app release.
    "geckoImplementation"("org.mozilla.geckoview:geckoview-$geckoAbi:160.0.20261104181552")
    tradovateDesktopRuntime(project(":tradovate-broker"))
    // NotificationCompat Live Update helpers (promotion request, status-bar chip text) and startForegroundService.
    implementation("androidx.core:core:1.18.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    // AndroidX Test references these CLASS-retained annotations when its release APK is shrunk.
    androidTestImplementation("com.google.errorprone:error_prone_annotations:2.36.0")
}
