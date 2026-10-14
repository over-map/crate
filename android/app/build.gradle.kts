import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Release signing: android/keystore.properties locally (git-ignored), or CRATE_KEYSTORE_* env vars in GitHub Actions.
val keystore = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use(::load)
}
fun signing(key: String): String? = System.getenv("CRATE_KEYSTORE_${key.uppercase()}") ?: keystore.getProperty(key)

android {
    namespace = "com.overmap.crate"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.overmap.crate"
        minSdk = 29
        targetSdk = 36
        versionCode = (System.getenv("CRATE_VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("CRATE_VERSION_NAME") ?: "1.0"
    }

    signingConfigs {
        create("release") {
            val path = signing("file")
            if (path != null) {
                storeFile = rootProject.file(path)
                storePassword = signing("password")
                keyAlias = signing("alias") ?: "crate"
                keyPassword = signing("password")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release").takeIf { it.storeFile != null }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isReturnDefaultValues = true }
}

kotlin { jvmToolchain(17) }

// Debug builds bundle a copy of data/ as demo data (assets/demo/), so screens can be checked without logging in
// (the twin of iOS's `make ios-demo`). Release builds never include it.
abstract class CopyDemoData : DefaultTask() {
    @get:InputDirectory abstract val data: DirectoryProperty
    @get:OutputDirectory abstract val out: DirectoryProperty

    @TaskAction fun copy() {
        val dir = out.get().asFile.resolve("demo")
        dir.deleteRecursively()
        dir.mkdirs()
        val names = listOf("library", "overrides", "presets", "vibes", "taxonomy", "weekly", "state", "health", "vectors.idx")
        for (n in names) data.get().asFile.resolve("$n.json").takeIf { it.exists() }?.copyTo(dir.resolve("$n.json"))
        data.get().asFile.resolve("vectors.bin").takeIf { it.exists() }?.copyTo(dir.resolve("vectors.bin"))
    }
}

val copyDemoData = tasks.register<CopyDemoData>("copyDemoData") {
    data.set(rootProject.layout.projectDirectory.dir("../data"))
    out.set(layout.buildDirectory.dir("generated/demo"))
}

androidComponents {
    // Only when data/ is there (it's a link to the private data repo; a fresh clone of the public repo has none).
    if (rootProject.file("../data/library.json").exists()) {
        onVariants(selector().withBuildType("debug")) { variant ->
            variant.sources.assets?.addGeneratedSourceDirectory(copyDemoData, CopyDemoData::out)
        }
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.serialization.json)
    implementation(libs.coroutines.android)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.activity.compose)
    implementation(libs.navigation.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.core.ktx)
    implementation(libs.browser)
    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)
    implementation(libs.datastore.preferences)
    implementation(libs.work.runtime)
    implementation(libs.coil.compose)
    implementation(libs.coil.network)
    testImplementation(libs.junit)
}
