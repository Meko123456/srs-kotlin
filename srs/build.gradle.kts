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
        // Deliberately 36 while every app in this fleet is on 37, and not an oversight.
        //
        // AGP writes a library's compileSdk straight into the published AAR as minCompileSdk, so
        // this value is a requirement placed on everyone who depends on the library rather than a
        // private build detail. Verified on heatmap-compose rather than assumed: building that
        // module on 37 produced minCompileSdk=37 in its aar-metadata.properties. It is the exact
        // mechanism by which Compose BOM 2026.09.00 and okhttp 5.5.0 broke projects across this
        // fleet all week.
        //
        // This library has no dependencies whatsoever, so there is nothing that could drag it forward —
        // and asking an adopter to move compile target to get a scheduling algorithm would be absurd.
        // It moves when something here actually needs an API newer than 36, and not before.
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
            "Spaced-repetition scheduling for Kotlin Multiplatform — configurable, exhaustively " +
                "tested SM-2 and FSRS implementations with no dependencies.",
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
