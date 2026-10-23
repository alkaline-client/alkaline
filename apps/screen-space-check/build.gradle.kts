plugins {
    application
}

dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

application {
    mainClass = "alkaline.compare.ScreenSpaceCheck"
    applicationDefaultJvmArgs = listOf("-Djava.awt.headless=true", "-Xmx512m")
}
