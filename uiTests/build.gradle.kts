plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin { jvmToolchain(21) }

// A separate test JVM prevents Compose's dispatcher test hooks from affecting the offscreen regression harness.
val desktopCompose = if (System.getProperty("os.name").contains("Windows", ignoreCase = true)) "1.10.3" else "1.12.0"
dependencies {
    testImplementation(project(":sharedUi"))
    // Exercise the Material ABI shipped by desktopApp, not just sharedUi's Android anchor.
    testImplementation(compose.material3)
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.compose.ui:ui-test-junit4:$desktopCompose")
    testImplementation(compose.desktop.currentOs)
}

tasks.test {
    useJUnit()
    // Without a display on every system, as on the Linux runner, so a test that reaches for AWT fails on Windows too.
    systemProperty("java.awt.headless", "true")
}
