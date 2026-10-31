plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
}

kotlin {
    jvmToolchain(25)
    // The API's binary interface is checked on every build (`check`) against api/plugin-api.api: a change that
    // would break built plugins fails until the dump is updated on purpose (`./gradlew :modules:plugin-api:updateKotlinAbi`).
    @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
    abiValidation()
}

// The one module a plugin compiles against: its entry point, the context Alkaline hands it, and the game (see
// docs/plugins). Compose and Material 3 come along so a plugin's interface uses the same components as Alkaline's;
// at run time the plugin shares the client's copies of them (and of this API).
dependencies {
    api(libs.compose.material3)
}
