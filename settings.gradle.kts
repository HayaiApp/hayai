pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
        maven { url=uri("https://jitpack.io") }
    }
}

rootProject.name = "Hayai"
include(":app")
