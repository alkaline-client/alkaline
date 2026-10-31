pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        google()
    }
}

rootProject.name = "alkaline-legacy"

include(
    ":modules:client",
    ":modules:config",
    ":modules:platform",
    ":modules:renderer",
    ":modules:game",
    ":modules:ui",
    ":modules:plugin-api",
    ":apps:diagnostics",
    ":apps:game-client",
    ":apps:screen-space-check",
    ":apps:hud-preview",
    ":apps:example-plugin",
    ":modules:audio",
    ":modules:music",
)
