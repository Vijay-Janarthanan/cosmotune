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
    jvmSharedMain.dependencies {
        api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
        implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    }
    sourceSets.jvmTest.dependencies {
        implementation(kotlin("test"))
        implementation("junit:junit:4.13.2")
    }
}

android {
    namespace = "com.cosmotune.remote"
    compileSdk = 37
    defaultConfig { minSdk = 26 }
}

// A localhost-only fixture for browser QA; test code and fixture metadata never ship.
tasks.register<JavaExec>("previewLanRemote") {
    group = "verification"
    description = "Serve the local remote UI against an isolated test player for ten minutes"
    dependsOn("jvmTestClasses")
    val tests = tasks.named<Test>("jvmTest")
    classpath = files(tests.map { it.testClassesDirs }, tests.map { it.classpath })
    mainClass.set("com.cosmotune.remote.LanRemotePreview")
    maxHeapSize = "128m"
    args(layout.buildDirectory.file("reports/remote-preview-link.txt").get().asFile.absolutePath)
}
