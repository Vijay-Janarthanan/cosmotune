import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.library")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin {
    androidTarget { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }
    jvm { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }
    applyDefaultHierarchyTemplate()
    compilerOptions { freeCompilerArgs.add("-Xexpect-actual-classes") }
    val jvmSharedMain by sourceSets.creating { dependsOn(sourceSets.commonMain.get()) }
    sourceSets.androidMain.get().dependsOn(jvmSharedMain)
    sourceSets.jvmMain.get().dependsOn(jvmSharedMain)
    sourceSets.commonMain.dependencies {
        api(project(":sharedUi"))
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
        implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
        implementation("org.jetbrains.kotlinx:kotlinx-serialization-protobuf:1.7.3")
        implementation("io.coil-kt.coil3:coil-network-okhttp:3.0.4")
    }
    jvmSharedMain.dependencies {
        api("com.github.clquwu:kotatsu-parsers-redo:dc223c3d80")
    }
    sourceSets.androidMain.dependencies {
        implementation("com.squareup.okhttp3:okhttp-brotli:5.3.2")
        implementation("com.squareup.okhttp3:okhttp-zstd:5.3.2")
        implementation("io.github.dokar3:quickjs-kt-android:1.0.14")
        implementation("androidx.activity:activity-compose:1.9.3")
        implementation("androidx.preference:preference-ktx:1.2.1")
        implementation("uy.kohesive.injekt:injekt-core:1.16.1")
        implementation("io.reactivex:rxjava:1.3.8")
        implementation("io.reactivex:rxandroid:1.2.1")
        implementation("io.github.pdvrieze.xmlutil:core-android:1.0.2")
        implementation("io.github.pdvrieze.xmlutil:serialization:1.0.2")
    }
    sourceSets.jvmMain.dependencies {
        implementation("net.java.dev.jna:jna-platform:5.19.1")
        implementation("org.graalvm.polyglot:polyglot:24.1.2")
        implementation("org.graalvm.polyglot:js:24.1.2")
    }
    sourceSets.getByName("androidInstrumentedTest").dependencies {
        implementation("androidx.test.ext:junit:1.3.0")
        implementation("androidx.test:runner:1.7.0")
    }
    sourceSets.jvmTest.dependencies {
        implementation(kotlin("test"))
        implementation("junit:junit:4.13.2")
        implementation("com.squareup.okhttp3:mockwebserver:5.3.2")
        implementation("com.squareup.okhttp3:okhttp-tls:5.3.2")
    }
}

android {
    packaging.resources.excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
    namespace = "com.cosmotune.manga"
    compileSdk = 37
    defaultConfig { minSdk = 26; consumerProguardFiles("consumer-rules.pro"); testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
}

compose.resources {
    publicResClass = true
    packageOfResClass = "com.cosmotune.manga.resources"
}

tasks.withType<Test>().configureEach {
    systemProperty("java.awt.headless", "true")
    systemProperty("cosmotune.manga.live", providers.gradleProperty("cosmotune.manga.live").orElse("").get())
    systemProperty("cosmotune.manga.storeLive", providers.gradleProperty("cosmotune.manga.storeLive").orElse("false").get())
}

// The signed fixture is test-only and is never bundled in production apps.
val fixtureAssets = layout.buildDirectory.dir("generated/source-api-fixture")
if (findProject(":mangaExtensionFixture") != null) {
    val bundleFixture by tasks.registering(Copy::class) {
        dependsOn(":mangaExtensionFixture:assembleDebug")
        from(project(":mangaExtensionFixture").layout.buildDirectory.dir("outputs/apk/debug")) { include("*.apk"); rename { "source-api-fixture.apk" } }
        into(fixtureAssets)
    }
    android.sourceSets.getByName("androidTest").assets.srcDir(fixtureAssets)
    tasks.matching { it.name == "preDebugAndroidTestBuild" }.configureEach { dependsOn(bundleFixture) }
}
configurations.configureEach {
    if (name.contains("android", true) || name in setOf("debugRuntimeClasspath", "releaseRuntimeClasspath", "debugCompileClasspath", "releaseCompileClasspath")) {
        exclude(group = "org.json", module = "json")
    }
}
