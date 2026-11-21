plugins {
    // AGP 9.1: built-in Kotlin support (no separate kotlin-android plugin; required by
    // androidx.a2ui:*-alpha02 AAR metadata: compileSdk 37 + AGP 9.1.0+).
    id("com.android.application") version "9.1.0" apply false
    id("com.android.library") version "9.1.0" apply false
    // Pure-Kotlin JVM modules (core:domain / core:data) still use KGP.
    id("org.jetbrains.kotlin.jvm") version "2.2.20" apply false
    // Compose Compiler Gradle plugin (required since Kotlin 2.0).
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.20" apply false
}
