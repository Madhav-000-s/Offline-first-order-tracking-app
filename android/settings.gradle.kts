pluginManagement {
    repositories {
        google()
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

rootProject.name = "offline-order-tracking"

// Modules are added incrementally, phase by phase (see /README.md's build
// plan) rather than declared all at once with empty placeholders.
include(":core:model")
include(":core:common")
include(":core:database")
include(":core:datastore")
