plugins {
    `java-library`
}

dependencies {
    api(project(":modules:config"))
    implementation(project(":modules:game"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
