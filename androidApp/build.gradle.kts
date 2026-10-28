import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}
android {
    namespace = "com.unitrading.android"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.unitrading.demo"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "1.1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures.compose = true
}
kotlin { compilerOptions.jvmTarget.set(JvmTarget.JVM_17) }
dependencies {
    implementation(project(":composeApp"))
    implementation(libs.activity.compose)
    implementation("androidx.webkit:webkit:1.18.0")
}
