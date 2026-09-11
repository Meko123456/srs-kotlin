pluginManagement {
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
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    // PREFER_SETTINGS, not FAIL_ON_PROJECT_REPOS: the Kotlin/JS and Wasm plugins add a Node
    // distribution repository themselves, from inside the plugin, and the strict mode fails the
    // build over it before anything resolves. Preferring settings ignores the plugin's copy and uses
    // the one declared below, so the repository list here is still the whole truth.
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()

        // The Kotlin/JS and Wasm toolchains fetch a Node runtime to run tests on, and would
        // otherwise add this repository themselves at project level - which FAIL_ON_PROJECT_REPOS
        // rejects. Declaring it here keeps the strict mode instead of trading it for the browser
        // targets. Scoped to org.nodejs:node so it is never consulted for anything else.
        ivy("https://nodejs.org/dist/") {
            name = "Node.js distributions"
            patternLayout { artifact("v[revision]/[artifact](-v[revision]-[classifier]).[ext]") }
            metadataSources { artifact() }
            content { includeModule("org.nodejs", "node") }
        }

        // Same story for Yarn: the Kotlin/JS plugin sets it up even when nothing needs npm packages.
        ivy("https://github.com/yarnpkg/yarn/releases/download") {
            name = "Yarn distributions"
            patternLayout { artifact("v[revision]/[artifact](-v[revision]).[ext]") }
            metadataSources { artifact() }
            content { includeModule("com.yarnpkg", "yarn") }
        }
    }
}

rootProject.name = "srs-kotlin"
include(":srs")
include(":sample")
