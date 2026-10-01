pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "voicecontrol-backend"

include(":domain")          // entities, value objects, ports (no framework deps)
include(":application")     // use cases / services orchestrating ports
include(":infrastructure")  // Postgres, Redis, LLM + embedding providers, event bus
include(":api")             // Ktor HTTP layer, auth, OpenAPI, composition root (main)
