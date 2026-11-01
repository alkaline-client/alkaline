plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(25)
}

val ffmpegClassifier = if (extra["alkalinePlatform"] == "windows-x64") "windows-x86_64" else "linux-x86_64"

// The music player: FFmpeg decodes every format and stream into the audio engine's music feed; yt-dlp (when
// installed) resolves video and music sites; playlists and the library are JSON files of the player's own.
dependencies {
    api(project(":modules:audio"))
    implementation(project(":modules:config"))
    implementation(libs.javacpp)
    implementation(libs.ffmpeg)
    runtimeOnly("org.bytedeco:javacpp:${libs.versions.javacpp.get()}:$ffmpegClassifier")
    runtimeOnly("org.bytedeco:ffmpeg:${libs.versions.ffmpeg.get()}:$ffmpegClassifier")
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    for (property in listOf("alkaline.music.probe", "alkaline.music.online")) {
        System.getProperty(property)?.let { systemProperty(property, it) }
    }
    testLogging.showStandardStreams = true
}
