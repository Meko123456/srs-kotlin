plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.maven.publish)
    alias(libs.plugins.dokka)
}

kotlin {
    // A published library's API surface should be deliberate: explicit mode makes every public
    // declaration say so, and turns an accidentally-public helper into a compile error rather than
    // a support burden after release.
    explicitApi()

    jvmToolchain(17)

    jvm()

    // AGP 9 replaced com.android.library-plus-androidTarget for multiplatform modules. Using the
    // dedicated KMP library plugin keeps this on the supported path instead of the
    // android.builtInKotlin=false escape hatch. minSdk 21 because nothing here touches the platform.
    android {
        namespace = "io.github.meko123456.srs"
        compileSdk = 36
        minSdk = 21
    }

    iosArm64()
    iosSimulatorArm64()

    // Pure Kotlin with no dependencies and no expect/actual, so the browser targets cost nothing but
    // a line each - and they are what makes this usable from a Kotlin/JS or Compose-for-Web review
    // app rather than only from a phone.
    // nodejs() only, no browser(): these declarations choose where this library's *own tests* run,
    // not where consumers can use it - the published artifacts work in a browser either way. Browser
    // test tasks drag in Yarn and a webpack bundle to serve a suite that touches no DOM and no
    // network, which is machinery for nothing.
    js(IR) {
        nodejs()
    }

    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        nodejs()
    }

    sourceSets {
        // commonMain deliberately declares no dependencies at all, not even kotlinx-datetime: dates
        // enter and leave as epoch-day Longs, so the algorithm stays pure arithmetic and the
        // consumer keeps whatever date library it already has.
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

mavenPublishing {
    publishToMavenCentral(automaticRelease = true)
    signAllPublications()
    coordinates("io.github.meko123456", "srs", "0.1.0")

    pom {
        name.set("srs-kotlin")
        description.set(
            "Spaced-repetition scheduling for Kotlin Multiplatform — a configurable, " +
                "exhaustively tested SM-2 implementation with no dependencies.",
        )
        url.set("https://github.com/Meko123456/srs-kotlin")
        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/licenses/MIT")
            }
        }
        developers {
            developer {
                id.set("Meko123456")
                name.set("Merab Kochlamazashvili")
                url.set("https://github.com/Meko123456")
            }
        }
        scm {
            url.set("https://github.com/Meko123456/srs-kotlin")
            connection.set("scm:git:git://github.com/Meko123456/srs-kotlin.git")
            developerConnection.set("scm:git:ssh://git@github.com/Meko123456/srs-kotlin.git")
        }
    }
}
