plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
}

kotlin {
    jvmToolchain(25)
}

// Example Alkaline plugins, each built as a plain jar to drop into the plugin folder: Click Guard (input and a
// HUD element) and Waypoints (commands, an overlay that follows the world, a screen, the world and entities).
// They compile against the plugin API only; the client provides the API, Kotlin and Compose at run time, so none
// of them go into the jars.
val examples = mapOf("clickguard" to "click-guard.jar", "waypoints" to "waypoints.jar")

sourceSets {
    for (name in examples.keys) create(name)
}

dependencies {
    for (name in examples.keys) "${name}CompileOnly"(project(":modules:plugin-api"))
}

for ((name, file) in examples) {
    val jar = tasks.register<Jar>("${name}Jar") {
        from(sourceSets[name].output)
        archiveFileName = file
        destinationDirectory = layout.buildDirectory.dir("plugins")
    }
    tasks.assemble { dependsOn(jar) }
}
