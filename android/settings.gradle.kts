pluginManagement {
    includeBuild("build-logic")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "VoiceControl"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

include(":app")

// Core modules
include(":core:model")        // pure Kotlin domain models
include(":core:common")       // pure Kotlin utilities (dispatchers, results)
include(":core:screen")       // pure Kotlin screen parsing (fields/buttons/stable ids/masking)
include(":core:nlp")          // pure Kotlin Hindi/English/Hinglish normalization + command parsing
include(":core:engine")       // pure Kotlin assistant session engine (ports + state machine)
include(":core:accessibility")// AccessibilityService + node adapters + action executor
include(":core:voice")        // Android TTS / SpeechRecognizer implementations
include(":core:network")      // Ktor client + backend API
include(":core:data")         // Room, DataStore, repositories
include(":core:ui")           // Material 3 theme + shared composables

// Feature modules
include(":feature:home")
include(":feature:inspector")
include(":feature:assistant")
include(":feature:auth")
include(":feature:flows")
include(":feature:history")
include(":feature:settings")
include(":feature:onboarding")
include(":feature:care")
