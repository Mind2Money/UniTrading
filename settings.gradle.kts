pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        // GeckoView for the Android APK that carries its own browser; only that group is looked up here.
        maven("https://maven.mozilla.org/maven2/") { content { includeGroup("org.mozilla.geckoview") } }
    }
}
rootProject.name = "UniTrading"
include(":broker-api", ":demo-broker", ":tradovate-broker", ":rithmic-broker", ":composeApp", ":androidApp")
