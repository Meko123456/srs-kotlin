plugins {
    alias(libs.plugins.android.kmp.library) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
}

// This project has zero npm dependencies, so Yarn never runs and never writes build/js/yarn.lock -
// but the Kotlin/JS plugin still registers the tasks that copy that file into kotlin-js-store, and
// they fail on the missing input. There is no lock to keep, so there is nothing for them to do.
tasks.matching { it.name == "kotlinStoreYarnLock" || it.name == "kotlinUpgradeYarnLock" }
    .configureEach { enabled = false }
