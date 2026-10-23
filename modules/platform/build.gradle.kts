plugins {
    `java-library`
}

val nativeClassifier = when {
    System.getProperty("os.arch") !in listOf("amd64", "x86_64") ->
        error("Alkaline SDL diagnostics currently require x86_64.")
    System.getProperty("os.name").startsWith("Windows") -> "natives-windows"
    System.getProperty("os.name") == "Linux" -> "natives-linux"
    else -> error("Alkaline SDL diagnostics currently support Windows and Linux.")
}

dependencies {
    implementation(libs.lwjgl)
    implementation(libs.lwjgl.sdl)
    runtimeOnly("org.lwjgl:lwjgl:${libs.versions.lwjgl.get()}:$nativeClassifier")
    runtimeOnly("org.lwjgl:lwjgl-sdl:${libs.versions.lwjgl.get()}:$nativeClassifier")
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
