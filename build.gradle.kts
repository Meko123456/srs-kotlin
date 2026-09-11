plugins {
    alias(libs.plugins.binary.compatibility.validator)
    alias(libs.plugins.android.kmp.library) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
}

// This project has zero npm dependencies, so Yarn never runs and never writes build/js/yarn.lock -
// but the Kotlin/JS plugin still registers the tasks that copy that file into kotlin-js-store, and
// they fail on the missing input. There is no lock to keep, so there is nothing for them to do.
tasks.matching { it.name == "kotlinStoreYarnLock" || it.name == "kotlinUpgradeYarnLock" }
    .configureEach { enabled = false }

// The public ABI is dumped to api/ and checked on every build. explicitApi() makes the API
// deliberate; this makes a *change* to it deliberate too - it turns up as a diff in review, and a
// change nobody meant to make fails the build instead of reaching Maven Central and staying there.
apiValidation {
    // The sample is an example, not a published artifact.
    ignoredProjects += "sample"

    @OptIn(kotlinx.validation.ExperimentalBCVApi::class)
    klib {
        // Without this only the JVM ABI is guarded, and the native, JS and Wasm artifacts - most of
        // the targets - could change shape unnoticed.
        enabled = true
    }
}
