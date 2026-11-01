plugins {
    `java-library`
}

val nativeClassifier = if (extra["alkalinePlatform"] == "windows-x64") "natives-windows" else "natives-linux"

dependencies {
    implementation(project(":modules:config"))
    implementation(libs.lwjgl)
    implementation(libs.lwjgl.openal)
    implementation(libs.lwjgl.stb)
    runtimeOnly("org.lwjgl:lwjgl:${libs.versions.lwjgl.get()}:$nativeClassifier")
    runtimeOnly("org.lwjgl:lwjgl-openal:${libs.versions.lwjgl.get()}:$nativeClassifier")
    runtimeOnly("org.lwjgl:lwjgl-stb:${libs.versions.lwjgl.get()}:$nativeClassifier")
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
