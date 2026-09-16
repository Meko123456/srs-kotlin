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

        // The Node runtime the tests run on, declared here so the version in use is visible in the
        // repository list rather than only inside the plugin. Note this does *not* buy back the
        // strict mode: FAIL_ON_PROJECT_REPOS rejects the plugin's own copy by name whatever is
        // declared here, which is why the mode above is PREFER_SETTINGS. Scoped to org.nodejs:node
        // so it is never consulted for anything else.
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
