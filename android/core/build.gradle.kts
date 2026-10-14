// Pure Kotlin/JVM: models, query engine, vibes, Spotify and GitHub clients. The Android twin of ios/CrateKit;
// both must pass spec/*.json (tests read them from the repo).
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(libs.serialization.json)
    implementation(libs.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}

tasks.test {
    systemProperty("crate.spec", rootProject.projectDir.resolve("../spec").absolutePath)
}
