import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// Release signing: keystore.properties (default /home/claude/liuying-keys) or LIUYING_* env vars.
// Absent both, release falls back to the debug key (installable, but not upgradable from a real release).
val signingProps = Properties().apply {
    val f = file(System.getenv("LIUYING_KEYSTORE_PROPERTIES") ?: "/home/claude/liuying-keys/keystore.properties")
    if (f.isFile) f.inputStream().use { load(it) }
}
fun signingValue(env: String, prop: String): String? =
    System.getenv(env)?.takeIf { it.isNotBlank() } ?: signingProps.getProperty(prop)?.takeIf { it.isNotBlank() }
val releaseStore = signingValue("LIUYING_KEYSTORE", "storeFile")?.let { file(it) }?.takeIf { it.isFile }

android {
    namespace = "com.liuying.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.liuying.app"
        minSdk = 24
        targetSdk = 35
        versionCode = (project.property("liuying.versionCode") as String).toInt()
        versionName = project.property("liuying.versionName") as String
    }

    signingConfigs {
        if (releaseStore != null) {
            create("release") {
                storeFile = releaseStore
                storePassword = signingValue("LIUYING_KEYSTORE_PASSWORD", "storePassword")
                keyAlias = signingValue("LIUYING_KEY_ALIAS", "keyAlias") ?: "liuying"
                keyPassword = signingValue("LIUYING_KEY_PASSWORD", "keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            // R8 off on purpose: correctness over APK size (0.3.0 failed to add sources when minified).
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures { compose = true }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
            // Robolectric fetches its android-all jar from Maven Central; this sandbox reaches a Central mirror instead.
            all { t ->
                (System.getenv("ROBOLECTRIC_REPO_URL") ?: "https://maven-central.storage-download.googleapis.com/maven2")
                    .let { t.systemProperty("robolectric.dependency.repo.url", it) }
                t.maxHeapSize = "2g"
                // LIUYING_SHOTS=1 writes the Roborazzi screenshots of ui/shots to app/build/shots
                if (System.getenv("LIUYING_SHOTS") != null) t.systemProperty("roborazzi.test.record", "true")
            }
        }
    }

    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.datasource.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
