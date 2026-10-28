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
        versionCode = 6
        versionName = "1.1.4"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Separate application ID for credential tests; never replace the normal installation.
        if (providers.gradleProperty("credentialStoreTest").orNull == "true") applicationIdSuffix = ".credentialtest"
    }
    buildTypes {
        // CI publishes this variant: R8 code shrinking plus resource shrinking.
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            if (providers.gradleProperty("credentialStoreTest").orNull == "true") signingConfig = signingConfigs.getByName("debug")
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
dependencies {
    implementation(project(":composeApp"))
    implementation(libs.activity.compose)
    implementation("androidx.webkit:webkit:1.18.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    // AndroidX Test references these CLASS-retained annotations when its release APK is shrunk.
    androidTestImplementation("com.google.errorprone:error_prone_annotations:2.36.0")
}
