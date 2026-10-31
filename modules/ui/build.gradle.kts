plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
}

kotlin {
    jvmToolchain(25)
}

// Alkaline's Material 3 Expressive interface: theme, menu pages, and settings UI. Rendering-host agnostic: the
// same composables run in the in-game Vulkan host and the desktop preview app.
dependencies {
    api(project(":modules:renderer"))
    api(project(":modules:client"))
    api(project(":modules:plugin-api"))
    api(project(":modules:music"))
    implementation(libs.material.color.utilities)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
