import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.multiplatform.library)
}
kotlin {
    jvm("desktop") { compilerOptions.jvmTarget.set(JvmTarget.JVM_17) }
    android {
        namespace = "com.unitrading.broker.tradovate"
        compileSdk = 37
        minSdk = 26
        compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
    }
    iosArm64()
    iosSimulatorArm64()
    jvmToolchain(21)
    sourceSets {
        commonMain.dependencies {
            api(project(":broker-api"))
            implementation("io.ktor:ktor-client-core:3.1.0")
            implementation("io.ktor:ktor-client-websockets:3.1.0")
        }
        getByName("desktopMain").dependencies { implementation("io.ktor:ktor-client-cio:3.1.0") }
        androidMain.dependencies { implementation("io.ktor:ktor-client-okhttp:3.1.0") }
        iosMain.dependencies { implementation("io.ktor:ktor-client-darwin:3.1.0") }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.coroutines.test)
        }
    }
}
