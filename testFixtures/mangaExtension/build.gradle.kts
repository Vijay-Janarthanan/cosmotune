plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.cosmotune.manga.fixture"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.cosmotune.manga.fixture"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.6.1"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    // A test APK links to the host API; those host classes must not be packaged in the extension.
    compileOnly(project(":manga"))
    compileOnly("androidx.preference:preference-ktx:1.2.1")
    compileOnly("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    compileOnly("com.squareup.okhttp3:okhttp:5.3.2")
}
configurations.configureEach {
    if (name.endsWith("RuntimeClasspath")) exclude(group = "org.jetbrains.kotlin")
}
