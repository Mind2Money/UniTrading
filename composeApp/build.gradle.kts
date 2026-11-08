import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.android.multiplatform.library)
}
kotlin {
    jvm("desktop") { compilerOptions.jvmTarget.set(JvmTarget.JVM_17) }
    android {
        namespace = "com.unitrading.shared"
        compileSdk = 37
        minSdk = 26
        compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
        androidResources.enable = true
    }
    listOf(iosArm64(), iosSimulatorArm64()).forEach {
        it.binaries.framework {
            baseName = "UniTrading"
            isStatic = true
        }
    }
    jvmToolchain(21)
    sourceSets {
        commonMain.dependencies {
            api(project(":broker-api"))
            api(project(":tradovate-broker"))
            api(project(":rithmic-broker"))
            implementation(project(":demo-broker"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(libs.navigationevent.compose)
            implementation(libs.serialization.json)
        }
        getByName("desktopMain").dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.coroutines.swing)
            implementation("net.java.dev.jna:jna-platform:5.14.0")
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.coroutines.test)
        }
        getByName("desktopTest").dependencies { implementation(compose.desktop.uiTestJUnit4) }
    }
}
val isWindows = System.getProperty("os.name").startsWith("Windows")
val isMac = System.getProperty("os.name").startsWith("Mac")
// Compose packages only appResources/{common,<os>,<os>-<arch>}. `run` and the probes read this folder as the resources dir.
val desktopAppResources = layout.buildDirectory.dir(if (isMac) "appResources/macos" else "appResources/windows")
val buildTradovateLoginHost = tasks.register<Exec>("buildTradovateLoginHost") {
    onlyIf { isWindows || isMac }
    val output = rootProject.file("native-auth/build/1.1.1")
    if (isMac) {
        inputs.files(rootProject.file("native-auth/TradovateLogin.swift"), rootProject.file("native-auth/build-macos.sh"))
        outputs.file(output.resolve("UniTrading.TradovateLogin"))
        commandLine("bash", rootProject.file("native-auth/build-macos.sh").absolutePath, output.absolutePath)
    } else {
        inputs.files(rootProject.file("native-auth/TradovateLogin.cs"), rootProject.file("native-auth/build.ps1"))
        outputs.file(output.resolve("UniTrading.TradovateLogin.exe"))
        commandLine("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", rootProject.file("native-auth/build.ps1").absolutePath,
            "-OutputDir", output.absolutePath)
    }
}
val stageTradovateLoginHost = tasks.register<Copy>("stageTradovateLoginHost") {
    dependsOn(buildTradovateLoginHost)
    onlyIf { isWindows || isMac }
    from(rootProject.file("native-auth/build/1.1.1")) { include("*.exe", "*.dll", "*.txt", "UniTrading.TradovateLogin") }
    into(desktopAppResources.map { it.dir("tradovate-login/1.1.1") })
}
val desktopTestCompilation = kotlin.targets.getByName("desktop").compilations.getByName("test")
tasks.register<JavaExec>("tradovateReadOnlyLoginProbe") {
    group = "verification"
    description = "Explicit Demo-only browser login diagnostic; all order routes are blocked."
    dependsOn("desktopTestClasses", stageTradovateLoginHost)
    classpath = files(desktopTestCompilation.output.allOutputs, desktopTestCompilation.runtimeDependencyFiles)
    mainClass.set("com.unitrading.TradovateReadOnlyProbeKt")
    args(when {
        providers.gradleProperty("probeDailyDiagnostic").orNull == "true" -> "--demo-daily-diagnostic-read-only"
        providers.gradleProperty("probeSavedContinuous").orNull == "true" -> "--demo-continuous-read-only"
        providers.gradleProperty("probeChartHistory").orNull == "true" -> "--demo-chart-history-read-only"
        else -> "--demo-read-only"
    })
    systemProperty("file.encoding", "UTF-8")
    systemProperty("sun.stdout.encoding", "UTF-8")
    systemProperty("sun.stderr.encoding", "UTF-8")
    systemProperty("compose.application.resources.dir", desktopAppResources.get().asFile.absolutePath)
    doFirst {
        check(isWindows && providers.gradleProperty("confirmTradovateReadOnly").orNull == "true") {
            "This Windows-only diagnostic requires -PconfirmTradovateReadOnly=true and never permits orders."
        }
    }
}

tasks.register<JavaExec>("tradovateReadOnlyCapacityProbe") {
    group = "verification"
    description = "Saved Demo session capacity and slider diagnostic; all trading writes are blocked."
    dependsOn("desktopTestClasses")
    classpath = files(desktopTestCompilation.output.allOutputs, desktopTestCompilation.runtimeDependencyFiles)
    mainClass.set("com.unitrading.TradovateCapacityReadOnlyProbeKt")
    args("--saved-demo-read-only")
    systemProperty("file.encoding", "UTF-8")
    doFirst {
        check(isWindows && providers.gradleProperty("confirmTradovateReadOnly").orNull == "true") {
            "Requires -PconfirmTradovateReadOnly=true; only saved Demo credentials and read-only routes are allowed."
        }
    }
}

tasks.matching { it.name in setOf("run", "prepareAppResources", "createDistributable", "packageMsi", "packageExe", "packageDmg") }.configureEach { dependsOn(stageTradovateLoginHost) }
compose.desktop {
    application {
        mainClass = "com.unitrading.MainKt"
        nativeDistributions {
            appResourcesRootDir.set(layout.buildDirectory.dir("appResources"))
            targetFormats(TargetFormat.Msi, TargetFormat.Dmg, TargetFormat.Deb)
            packageName = "UniTrading"
            packageVersion = "1.3.1"
            // jlink keeps only listed modules; `suggestRuntimeModules` lists what the jars need beyond the defaults.
            modules("java.instrument", "java.management", "jdk.unsupported")
            windows { iconFile.set(project.file("icons/unitrading.ico")) }
            macOS {
                bundleID = "com.unitrading.desktop"
                iconFile.set(project.file("icons/unitrading.icns"))
                minimumSystemVersion = "13.0"
            }
        }
    }
}

apply(from = rootProject.file("gradle/desktop-run.gradle.kts"))
