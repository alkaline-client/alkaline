import java.util.Base64

plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
}

kotlin {
    jvmToolchain(25)
}

val jmhSourceSet = sourceSets.create("jmh") {
    java.srcDir("src/jmh/java")
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += output + compileClasspath
}

dependencies {
    api(project(":modules:platform"))
    implementation(project(":modules:config"))
    implementation(libs.lwjgl)
    implementation(libs.lwjgl.sdl)
    implementation(libs.lwjgl.vulkan)
    api(libs.compose.material3)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)

    // JMH is isolated to the module-local benchmark source set.
    add("jmhImplementation", "org.openjdk.jmh:jmh-core:1.37")
    add("jmhAnnotationProcessor", "org.openjdk.jmh:jmh-generator-annprocess:1.37")
}

val vulkanShaderDirectory = layout.projectDirectory.dir("src/main/resources/alkaline/renderer/vulkan/shaders")
// Every GLSL stage beside the packaged resources is compiled, validated, and checked for staleness.
val vulkanShaderNames = vulkanShaderDirectory.asFile.listFiles { file ->
    file.isFile && (file.name.endsWith(".vert") || file.name.endsWith(".frag"))
}.orEmpty().map { it.name }.sorted()
val vulkanShaderOutputDirectory = layout.buildDirectory.dir("generated/vulkan-shaders")
val generateVulkanShaderBinaries = tasks.register("generateVulkanShaderBinaries") {
    group = "build"
    description = "Compiles owned Vulkan GLSL shader sources to SPIR-V under the build directory."
    inputs.files(vulkanShaderNames.map { vulkanShaderDirectory.file(it) })
    // Shared GLSL included by stages (#include "x.glsl"); not compiled on its own.
    inputs.files(vulkanShaderDirectory.asFile.listFiles { file -> file.name.endsWith(".glsl") }.orEmpty())
    outputs.dir(vulkanShaderOutputDirectory)
    doLast {
        val output = vulkanShaderOutputDirectory.get().asFile
        if (!output.exists() && !output.mkdirs()) {
            throw GradleException("Unable to create Vulkan shader output directory: $output")
        }
        val glslc = providers.gradleProperty("glslc").orElse("glslc").get()
        val available = try {
            ProcessBuilder(glslc, "--version").redirectErrorStream(true).start().let { probe ->
                probe.inputStream.readAllBytes()
                probe.waitFor() == 0
            }
        } catch (missing: java.io.IOException) {
            false
        }
        if (!available) {
            // Builds without the Vulkan SDK keep using the checked-in binaries; verifyVulkanShaders still
            // requires glslc and reports stale ones.
            logger.warn("glslc not found: using the checked-in SPIR-V resources without regenerating them.")
            return@doLast
        }
        vulkanShaderNames.forEach { shader ->
            val source = vulkanShaderDirectory.file(shader).asFile
            val binary = output.resolve("$shader.spv")
            val compile = ProcessBuilder(
                glslc, "--target-env=vulkan1.3", "-o", binary.absolutePath, source.absolutePath,
            ).redirectErrorStream(true).start()
            val outputText = compile.inputStream.bufferedReader().use { it.readText() }
            if (compile.waitFor() != 0) {
                throw GradleException("glslc failed for ${source.path}:\n$outputText")
            }
        }
    }
}

val packageVulkanShaders = tasks.register("packageVulkanShaders") {
    group = "build"
    description = "Regenerates the checked-in base64 SPIR-V resources from freshly compiled shaders."
    dependsOn(generateVulkanShaderBinaries)
    inputs.dir(vulkanShaderOutputDirectory)
    outputs.files(vulkanShaderNames.map { vulkanShaderDirectory.file("$it.spv.base64") })
    doLast {
        // One unwrapped line: a wrapped line could begin with "//" and be mistaken for a comment. SPIR-V's magic
        // number always encodes as "AwIjBw", so the payload line itself can never look like a comment.
        val encoder = Base64.getEncoder()
        vulkanShaderNames.forEach { shader ->
            val binary = vulkanShaderOutputDirectory.get().asFile.resolve("$shader.spv")
            if (!binary.exists()) {
                return@forEach
            }
            val packaged = vulkanShaderDirectory.file("$shader.spv.base64").asFile
            val header = vulkanShaderDirectory.file(shader).asFile.readLines()
                .takeWhile { it.startsWith("//") }
                .joinToString("") { "$it\n" }
            val encoded = encoder.encodeToString(binary.readBytes())
            val content = header + encoded + "\n"
            val current = if (packaged.exists()) {
                Base64.getMimeDecoder().decode(packaged.readLines()
                    .filterNot { it.trimStart().startsWith("//") }.joinToString(""))
            } else {
                null
            }
            val wrapped = packaged.exists() && packaged.readLines().count { !it.trimStart().startsWith("//") } > 1
            if (current == null || wrapped || !current.contentEquals(binary.readBytes())) {
                packaged.writeText(content)
                logger.lifecycle("Packaged ${packaged.name}")
            }
        }
    }
}

// Every build, run and test packages freshly compiled shaders first, so edited GLSL never runs as stale SPIR-V.
tasks.named("processResources") {
    dependsOn(packageVulkanShaders)
}

tasks.register("verifyVulkanShaders") {
    group = "verification"
    description = "Validates generated SPIR-V and checks the packaged shader binaries against GLSL sources."
    dependsOn(generateVulkanShaderBinaries)
    // When a build packages and verifies in one run, check the freshly packaged binaries.
    mustRunAfter(packageVulkanShaders)
    inputs.files(vulkanShaderNames.map { vulkanShaderDirectory.file("$it.spv.base64") })
    doLast {
        val spirvVal = providers.gradleProperty("spirvVal").orElse("spirv-val").get()
        val decoder = Base64.getMimeDecoder()
        vulkanShaderNames.forEach { shader ->
            val binary = vulkanShaderOutputDirectory.get().asFile.resolve("$shader.spv")
            val validation = ProcessBuilder(
                spirvVal, "--target-env", "vulkan1.3", binary.absolutePath,
            ).redirectErrorStream(true).start()
            val outputText = validation.inputStream.bufferedReader().use { it.readText() }
            if (validation.waitFor() != 0) {
                throw GradleException("spirv-val failed for ${binary.path}:\n$outputText")
            }
            val packaged = vulkanShaderDirectory.file("$shader.spv.base64").asFile
            val checkedInBytes = try {
                decoder.decode(packaged.readLines()
                    .filterNot { it.trimStart().startsWith("//") }
                    .joinToString(""))
            } catch (failure: IllegalArgumentException) {
                throw GradleException("Invalid base64 SPIR-V resource ${packaged.path}", failure)
            }
            if (!checkedInBytes.contentEquals(binary.readBytes())) {
                throw GradleException("SPIR-V resource ${packaged.path} is stale; regenerate it from ${shader}.")
            }
        }
    }
}

tasks.register<JavaExec>("jmh") {
    group = "benchmark"
    description = "Runs the isolated world mesher JMH benchmarks."
    dependsOn(tasks.named(jmhSourceSet.classesTaskName))
    classpath = jmhSourceSet.runtimeClasspath
    mainClass.set("org.openjdk.jmh.Main")
    val jmhArgs = providers.gradleProperty("jmhArgs").orNull
    args(jmhArgs?.split(" ") ?: listOf("alkaline.renderer.world.SectionMesherBenchmark"))
}
