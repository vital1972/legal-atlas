pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        id("com.android.application") version "8.9.1"
        id("org.jetbrains.kotlin.android") version "2.1.21"
        id("org.jetbrains.kotlin.jvm") version "2.1.21"
        id("org.jetbrains.kotlin.plugin.compose") version "2.1.21"
        id("org.jetbrains.kotlin.plugin.serialization") version "2.1.21"
    }
}

dependencyResolutionManagement {
    repositories {
        if (System.getenv("SKIP_ANDROID") == null) google()
        mavenCentral()
    }
}

rootProject.name = "legal-atlas"

include(":core")
// Модуль приложения требует Android SDK. Без него (например, в облачной
// среде) можно собрать и протестировать только :core: SKIP_ANDROID=1.
if (System.getenv("SKIP_ANDROID") == null) {
    include(":app")
}
