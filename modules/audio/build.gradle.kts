plugins {
    `java-library`
}

val nativeClassifier = when {
    System.getProperty("os.arch") !in listOf("amd64", "x86_64") ->
        error("Alkaline audio currently requires x86_64.")
    System.getProperty("os.name").startsWith("Windows") -> "natives-windows"
    System.getProperty("os.name") == "Linux" -> "natives-linux"
    else -> error("Alkaline audio currently supports Windows and Linux.")
}

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
