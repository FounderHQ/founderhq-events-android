plugins {
    // 0.35 supports this SDK's Gradle 8 / AGP 8 toolchain.
    id("com.vanniktech.maven.publish") version "0.35.0" apply false
    id("com.android.application") version "8.11.0" apply false
    id("com.android.library") version "8.11.0" apply false
    id("org.jetbrains.kotlin.android") version "2.1.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.20" apply false
}
