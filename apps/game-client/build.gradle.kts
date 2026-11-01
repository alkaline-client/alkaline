import java.security.MessageDigest
import java.time.Instant
import java.time.temporal.ChronoUnit

plugins {
    application
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
}

kotlin {
    jvmToolchain(25)
}

val alkalinePlatform = extra["alkalinePlatform"] as String

dependencies {
    implementation(project(":modules:client"))
    implementation(project(":modules:game"))
    implementation(project(":modules:platform"))
    implementation(project(":modules:renderer"))
    implementation(project(":modules:ui"))
    implementation(project(":modules:audio"))
    implementation(project(":modules:music"))
    implementation(libs.lwjgl)
    implementation(libs.lwjgl.sdl)
    implementation(libs.lwjgl.vulkan)
    // Skia natives for the in-game Compose layer (CPU raster; no AWT window is created).
    implementation("org.jetbrains.compose.desktop:desktop-jvm-$alkalinePlatform:${libs.versions.compose.get()}")

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

application {
    mainClass = "alkaline.clientapp.ClientMain"
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

tasks.jar {
    manifest.attributes("Implementation-Title" to "Alkaline", "Implementation-Version" to project.version)
}

// The release for Sulfur and anyone who unzips it: alkaline-<version>-<platform>.zip, holding bin/ and lib/.
tasks.distZip {
    archiveFileName = "alkaline-${project.version}-$alkalinePlatform.zip"
}

// The same client as a version of Minecraft's own launcher, unzipped into .minecraft: versions/Alkaline-<version>/
// and libraries/. The launcher gives it Java 25 (Mojang's java-runtime-epsilon) and downloads the vanilla 1.8.9 jar
// from Mojang as the version's jar, which Alkaline reads its assets from; nothing of Mojang's is in the zip.
val minecraftLauncherFiles = tasks.register("minecraftLauncherFiles") {
    val version = project.version.toString()
    val id = "Alkaline-$version"
    val appJar = tasks.jar.flatMap { it.archiveFile }
    val runtime = configurations.runtimeClasspath.get().incoming.artifacts.resolvedArtifacts
    val output = layout.buildDirectory.dir("minecraft-launcher")
    inputs.files(appJar, configurations.runtimeClasspath)
    outputs.dir(output)
    doLast {
        val root = output.get().asFile
        root.deleteRecursively()
        val libraries = mutableListOf<Map<String, Any>>()
        fun add(name: String, path: String, file: File) {
            file.copyTo(root.resolve("libraries/$path"))
            val sha1 = MessageDigest.getInstance("SHA-1").digest(file.readBytes())
                .joinToString("") { "%02x".format(it) }
            // No url: the jar is in the zip, and the launcher only checks it.
            val artifact = mapOf("path" to path, "sha1" to sha1, "size" to file.length(), "url" to "")
            libraries += mapOf("name" to name, "downloads" to mapOf("artifact" to artifact))
        }
        // The start script's order: the client's jar, then its runtime classpath.
        val jar = appJar.get().asFile
        add("alkaline:game-client:$version", "alkaline/game-client/$version/${jar.name}", jar)
        for (artifact in runtime.get()) {
            val file = artifact.file
            when (val component = artifact.id.componentIdentifier) {
                is ModuleComponentIdentifier -> {
                    val base = "${component.module}-${component.version}"
                    val classifier = file.nameWithoutExtension.removePrefix(base).removePrefix("-")
                        .ifEmpty { null }
                    val name = listOfNotNull(component.group, component.module, component.version, classifier)
                    val path = "${component.group.replace('.', '/')}/${component.module}/${component.version}"
                    add(name.joinToString(":"), "$path/${file.name}", file)
                }
                is ProjectComponentIdentifier -> {
                    val module = component.projectPath.removePrefix(":").replace(':', '-')
                    add("alkaline:$module:$version", "alkaline/$module/$version/${file.name}", file)
                }
                else -> error("No library path for $component ($file)")
            }
        }
        val now = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString()
        val json = mapOf(
            "id" to id,
            "type" to "release",
            "time" to now,
            "releaseTime" to now,
            "minimumLauncherVersion" to 21,
            "mainClass" to "alkaline.clientapp.ClientMain",
            "javaVersion" to mapOf("component" to "java-runtime-epsilon", "majorVersion" to 25),
            // Vanilla 1.8.9's client jar and asset index (its sounds), from its own version JSON.
            "downloads" to mapOf(
                "client" to mapOf(
                    "sha1" to "3870888a6c3d349d3771a3e9d16c9bf5e076b908",
                    "size" to 8461484,
                    "url" to "https://launcher.mojang.com/v1/objects/3870888a6c3d349d3771a3e9d16c9bf5e076b908/client.jar",
                ),
            ),
            "assets" to "1.8",
            "assetIndex" to mapOf(
                "id" to "1.8",
                "sha1" to "f6ad102bcaa53b1a58358f16e376d548d44933ec",
                "size" to 78494,
                "totalSize" to 114885064,
                "url" to "https://launchermeta.mojang.com/v1/packages/f6ad102bcaa53b1a58358f16e376d548d44933ec/1.8.json",
            ),
            "arguments" to mapOf(
                "game" to emptyList<String>(),
                "jvm" to listOf(
                    "--enable-native-access=ALL-UNNAMED",
                    "-Dalkaline.minecraftJar=\${library_directory}/../versions/\${version_name}/\${version_name}.jar",
                    "-cp",
                    "\${classpath}",
                ),
            ),
            "libraries" to libraries,
        )
        root.resolve("versions/$id/$id.json").apply { parentFile.mkdirs() }
            .writeText(groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(json)) + "\n")
    }
}

tasks.register<Zip>("minecraftLauncherZip") {
    group = "distribution"
    description = "Packages the client as a version of Minecraft's own launcher."
    from(minecraftLauncherFiles)
    archiveFileName = "alkaline-${project.version}-$alkalinePlatform-minecraft-launcher.zip"
    destinationDirectory = layout.buildDirectory.dir("distributions")
}
