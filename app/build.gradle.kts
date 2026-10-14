import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing comes from environment variables (GitHub Actions secrets) or a local
// keystore.properties file. Without either, release builds fall back to the debug key so the
// build never fails — but then updates won't install over a properly signed version.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingValue(env: String, prop: String): String? =
    System.getenv(env)?.takeIf { it.isNotBlank() } ?: keystoreProps.getProperty(prop)

val releaseStoreFile = signingValue("LTOTS_KEYSTORE_FILE", "storeFile")
val hasReleaseKey = releaseStoreFile != null && file(releaseStoreFile).exists()

val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "com.ltots.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.ltots.app"
        minSdk = 31
        targetSdk = 36
        versionCode = buildNumber
        versionName = "0.1.$buildNumber"
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = signingValue("LTOTS_KEYSTORE_PASSWORD", "storePassword")
                keyAlias = signingValue("LTOTS_KEY_ALIAS", "keyAlias")
                keyPassword = signingValue("LTOTS_KEY_PASSWORD", "keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasReleaseKey) signingConfigs.getByName("release")
            else signingConfigs.getByName("debug")
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    lint {
        // Media3's @UnstableApi opt-in is a lint check; this app uses those APIs on purpose.
        disable += "UnsafeOptInUsageError"
        checkReleaseBuilds = false
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")

    val media3 = "1.11.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-session:$media3")
    implementation("androidx.media3:media3-common:$media3")

    // Liquid-glass backdrop effects (blur, lens refraction, highlights) for Compose.
    implementation("io.github.kyant0:backdrop:2.0.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("com.google.guava:guava:33.7.2-android")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20260814")
}
