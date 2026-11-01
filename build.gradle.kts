plugins {
    base
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.compose) apply false
}

// Alkaline's version is <year>.<drop>.<update>[.<hotfix>], for example 2026.1.0, 2026.1.2 or 2026.1.2.1; see
// decision 0025. Builds between releases carry the next update's number with -dev.
val alkalineVersion = "2026.1.1"

// The platform whose natives a build bundles: the host's, or another for a release with
// -Palkaline.platform=linux-x64|windows-x64.
val alkalinePlatform = providers.gradleProperty("alkaline.platform").orNull ?: when {
    System.getProperty("os.arch") !in listOf("amd64", "x86_64") -> error("Alkaline currently requires x86_64.")
    System.getProperty("os.name").startsWith("Windows") -> "windows-x64"
    System.getProperty("os.name") == "Linux" -> "linux-x64"
    else -> error("Alkaline currently supports Windows and Linux.")
}
require(alkalinePlatform in listOf("linux-x64", "windows-x64")) {
    "alkaline.platform must be linux-x64 or windows-x64, not $alkalinePlatform."
}

allprojects {
    group = "alkaline"
    version = alkalineVersion
    extra["alkalinePlatform"] = alkalinePlatform
}

subprojects {
    apply(plugin = "base")
    plugins.withType<JavaPlugin> {
        extensions.configure<JavaPluginExtension> {
            toolchain.languageVersion.set(JavaLanguageVersion.of(25))
        }
        tasks.withType<JavaCompile>().configureEach {
            options.release.set(25)
            options.encoding = "UTF-8"
            options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
        }
        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
            testLogging { events("passed", "skipped", "failed") }
        }
    }
}

tasks.named("check") {
    dependsOn(subprojects.map { "${it.path}:check" })
}

tasks.named("assemble") {
    dependsOn(subprojects.map { "${it.path}:assemble" })
}

tasks.wrapper {
    gradleVersion = "9.7.0"
    distributionType = Wrapper.DistributionType.BIN
    distributionSha256Sum = "84fbba45c7f4c64abc77460e1c00f541e9f960e3c7ed2538f1ede19eacd873ae7"
}
