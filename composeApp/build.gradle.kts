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
val buildTradovateLoginHost = tasks.register<Exec>("buildTradovateLoginHost") {
    onlyIf { isWindows }
    inputs.files(rootProject.file("native-auth/TradovateLogin.cs"), rootProject.file("native-auth/build.ps1"))
    outputs.file(rootProject.file("native-auth/build/1.1.1/UniTrading.TradovateLogin.exe"))
    commandLine("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", rootProject.file("native-auth/build.ps1").absolutePath,
        "-OutputDir", rootProject.file("native-auth/build/1.1.1").absolutePath)
}
val stageTradovateLoginHost = tasks.register<Copy>("stageTradovateLoginHost") {
    dependsOn(buildTradovateLoginHost)
    onlyIf { isWindows }
    from(rootProject.file("native-auth/build/1.1.1")) { include("*.exe", "*.dll", "*.txt") }
    into(layout.buildDirectory.dir("appResources/tradovate-login/1.1.1"))
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
        else -> "--demo-read-only"
    })
    systemProperty("file.encoding", "UTF-8")
    systemProperty("sun.stdout.encoding", "UTF-8")
    systemProperty("sun.stderr.encoding", "UTF-8")
    systemProperty("compose.application.resources.dir", layout.buildDirectory.dir("appResources").get().asFile.absolutePath)
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

tasks.matching { it.name in setOf("run", "createDistributable", "packageMsi", "packageExe") }.configureEach { dependsOn(stageTradovateLoginHost) }
compose.desktop {
    application {
        mainClass = "com.unitrading.MainKt"
        nativeDistributions {
            appResourcesRootDir.set(layout.buildDirectory.dir("appResources"))
            targetFormats(TargetFormat.Msi, TargetFormat.Dmg, TargetFormat.Deb)
            packageName = "UniTrading"
            packageVersion = "1.1.4"
        }
    }
}

apply(from = rootProject.file("gradle/desktop-run.gradle.kts"))
