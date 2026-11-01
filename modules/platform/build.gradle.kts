plugins {
    `java-library`
}

val nativeClassifier = if (extra["alkalinePlatform"] == "windows-x64") "natives-windows" else "natives-linux"

dependencies {
    implementation(libs.lwjgl)
    implementation(libs.lwjgl.sdl)
    runtimeOnly("org.lwjgl:lwjgl:${libs.versions.lwjgl.get()}:$nativeClassifier")
    runtimeOnly("org.lwjgl:lwjgl-sdl:${libs.versions.lwjgl.get()}:$nativeClassifier")
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
