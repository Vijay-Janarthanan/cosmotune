import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin {
    androidTarget { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }
    jvm { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }
    applyDefaultHierarchyTemplate()
    val jvmSharedMain by sourceSets.creating { dependsOn(sourceSets.commonMain.get()) }
    sourceSets.androidMain.get().dependsOn(jvmSharedMain)
    sourceSets.jvmMain.get().dependsOn(jvmSharedMain)
    jvmSharedMain.dependencies { api(project(":shared")) }
    sourceSets.androidMain.dependencies { implementation("io.github.dokar3:quickjs-kt-android:1.0.14") }
    sourceSets.androidInstrumentedTest.dependencies {
        implementation("androidx.test.ext:junit:1.3.0")
        implementation("androidx.test:runner:1.7.0")
        implementation("androidx.test:core:1.7.0")
    }
    sourceSets.jvmMain.dependencies {
        implementation("net.java.dev.jna:jna-platform:5.19.1")
        implementation("org.graalvm.polyglot:polyglot:24.1.2")
        implementation("org.graalvm.polyglot:js:24.1.2")
    }
    sourceSets.jvmTest.dependencies {
        implementation(kotlin("test"))
        implementation("junit:junit:4.13.2")
        implementation("com.squareup.okhttp3:mockwebserver:5.3.2")
        implementation("com.squareup.okhttp3:okhttp-tls:5.3.2")
    }
}

android {
    namespace = "com.cosmotune.providers"
    compileSdk = 37
    defaultConfig { minSdk = 26; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
    sourceSets.getByName("main").resources.srcDir("src/jvmSharedMain/resources")
}

tasks.withType<Test>().configureEach {
    systemProperty("cosmotune.provider.live", providers.gradleProperty("cosmotune.provider.live").orElse("").get())
    systemProperty("cosmotune.provider.ffmpeg", providers.gradleProperty("cosmotune.provider.ffmpeg").orElse("").get())
}
