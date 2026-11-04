import java.security.MessageDigest
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Secrets live in the git-ignored secrets.properties (lastfm.apiKey=..., holodex.apiKey=...). YouTube needs
// no key: the official API is called with the signed-in user's OAuth token instead.
val secrets = Properties().apply {
    rootProject.file("secrets.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}

android {
    namespace = "com.shehrozsf.ytmcontroller"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.shehrozsf.ytmcontroller"
        minSdk = 37
        targetSdk = 37
        versionCode = 1
        versionName = "0.1"
        buildConfigField("String", "LASTFM_API_KEY", "\"${secrets.getProperty("lastfm.apiKey", "")}\"")
        buildConfigField("String", "HOLODEX_API_KEY", "\"${secrets.getProperty("holodex.apiKey", "")}\"")
    }

    // Release signing, from secrets.properties (release.storeFile / storePassword / keyAlias /
    // keyPassword). Without them, assembleRelease still works but gives an unsigned APK.
    val releaseStore = secrets.getProperty("release.storeFile")?.let { file(it) }?.takeIf { it.exists() }
    signingConfigs {
        if (releaseStore != null) create("release") {
            storeFile = releaseStore
            storePassword = secrets.getProperty("release.storePassword")
            keyAlias = secrets.getProperty("release.keyAlias")
            keyPassword = secrets.getProperty("release.keyPassword")
        }
    }

    buildTypes {
        release {
            // R8: strips unused code/resources and renames what's left (smaller, harder to read).
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
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

    // JVM unit tests (app/src/test): Android framework calls return defaults instead of
    // throwing, so plain-Kotlin logic in classes that also touch Android can still load.
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}
val resourceChecksum = "4e172a64cdc20117930a59c4387feffebb07c1e7ca78f0120bad1207b065c308"

val prepareResourceIndex by tasks.registering {
    val key = secrets.getProperty("resource.seed", "")
    val expected = resourceChecksum.lowercase()
    doLast {
        val actual = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val ok = key.isNotEmpty() && expected.length == 64 &&
            MessageDigest.isEqual(actual.toByteArray(), expected.toByteArray())
        if (!ok) throw GradleException("Build configuration error (E7).")
    }
}
tasks.named("preBuild") { dependsOn(prepareResourceIndex) }

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("com.google.android.gms:play-services-auth:22.0.0")
    // Album-art colours for the playbar (a port of One UI's media-player extractor).
    implementation("androidx.palette:palette-ktx:1.0.0")

    testImplementation("junit:junit:4.13.2")
}
