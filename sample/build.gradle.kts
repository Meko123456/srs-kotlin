plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // The library as a consumer sees it, by project rather than by coordinate so the sample is
    // always exercising the working tree.
    implementation(project(":srs"))
}

application {
    mainClass.set("io.github.meko123456.srs.sample.MainKt")
}
