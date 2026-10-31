plugins {
    application
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
}

kotlin {
    jvmToolchain(25)
}

dependencies {
    implementation(project(":modules:client"))
    implementation(project(":modules:game"))
    implementation(project(":modules:platform"))
    implementation(project(":modules:renderer"))
    implementation(project(":modules:ui"))
    implementation(project(":modules:audio"))
    implementation(project(":modules:music"))
    implementation(libs.lwjgl)
    implementation(libs.lwjgl.sdl)
    implementation(libs.lwjgl.vulkan)
    // Skia natives for the in-game Compose layer (CPU raster; no AWT window is created).
    implementation(compose.desktop.currentOs)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

application {
    mainClass = "alkaline.clientapp.ClientMain"
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}
