plugins {
    application
}

dependencies {
    implementation(project(":modules:game"))
    implementation(project(":modules:client"))
    implementation(project(":modules:platform"))
    implementation(project(":modules:renderer"))
    implementation(libs.lwjgl)
    implementation(libs.lwjgl.sdl)
    implementation(libs.lwjgl.vulkan)
}

application {
    mainClass = "alkaline.diagnostics.DiagnosticsMain"
}
