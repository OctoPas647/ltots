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

// A desktop build of the Rubber Band JNI library, so unit tests run the real pitch engine.
val hostLibDir = layout.buildDirectory.dir("hostlib")
val buildHostRubberBand by tasks.registering(Exec::class) {
    val rb = file("src/main/cpp/rubberband")
    val out = hostLibDir.map { it.file("librubberband-jni.so") }
    val javaHome = File(System.getProperty("java.home"))
    inputs.dir(rb)
    outputs.file(out)
    doFirst { hostLibDir.get().asFile.mkdirs() }
    commandLine(
        "g++", "-O3", "-ffast-math", "-fPIC", "-shared", "-std=c++17",
        "-I$rb", "-I$rb/src",
        "-I${javaHome}/include", "-I${javaHome}/include/linux",
        "$rb/single/RubberBandSingle.cpp", "$rb/src/jni/RubberBandStretcherJNI.cpp",
        "-o", out.get().asFile.absolutePath,
    )
}

android {
    namespace = "com.ltots.app"
    compileSdk = 37
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "com.ltots.app"
        minSdk = 31
        targetSdk = 36
        versionCode = buildNumber
        versionName = "0.1.$buildNumber"
        // The download engine (Python, ffmpeg) is native code; ship only the 64-bit ARM build
        // that every current phone uses, which keeps the APK roughly half the size.
        ndk { abiFilters += "arm64-v8a" }
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
            // R8 on, in a careful form: it optimises only LToTS's own code, Kotlin and AndroidX
            // (Compose, Media3) — that's what makes Compose run smoothly — while every other
            // library (the yt-dlp download engine and what it uses) is kept exactly as shipped,
            // and nothing is renamed. Full shrinking once broke yt-dlp (NoClassDefFoundError
            // for a renamed class); see proguard-rules.pro.
            isMinifyEnabled = true
            isShrinkResources = false
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

    // Rubber Band (pitch & speed engine), compiled from source for the phone.
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "4.1.2"
        }
    }

    lint {
        // Media3's @UnstableApi opt-in is a lint check; this app uses those APIs on purpose.
        disable += "UnsafeOptInUsageError"
        checkReleaseBuilds = false
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
        unitTests.all { test ->
            // Audio tests run the real Rubber Band engine, built for this computer.
            test.dependsOn(buildHostRubberBand)
            test.systemProperty("java.library.path", hostLibDir.get().asFile.absolutePath)
            test.systemProperty("roborazzi.test.record", "true")
            // Live tests (-Dltots.live=true) and their local files (-Dltots.models=…, -Dltots.clips=…).
            System.getProperties().stringPropertyNames().filter { it.startsWith("ltots.") }
                .forEach { test.systemProperty(it, System.getProperty(it)) }
            test.maxHeapSize = "2g"
            // A native library can only be loaded by one class loader per JVM, and Robolectric
            // tests use their own; so every test class gets a fresh JVM.
            test.setForkEvery(1)
            // Robolectric (Android 16 sandbox) needs these on JDK 21.
            test.jvmArgs(
                "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED",
                "--add-opens=java.base/java.io=ALL-UNNAMED",
            )
        }
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        // yt-dlp's Python and ffmpeg run as programs from the native-library folder,
        // so they must be extracted on install rather than kept inside the APK.
        jniLibs.useLegacyPackaging = true
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
    // Background jobs that wait for the right moment (charging / online): waveform indexing and
    // lyrics lookup for the whole library.
    implementation("androidx.work:work-runtime-ktx:2.11.2")
    // On-device speech recognition that times plain lyrics (offline; the model is downloaded on request).
    implementation("com.alphacephei:vosk-android:0.3.75")
    implementation("com.google.guava:guava:33.7.2-android")

    // yt-dlp + Python + ffmpeg for Android (the engine Seal uses).
    val ytdl = "0.18.1"
    implementation("io.github.junkfood02.youtubedl-android:library:$ytdl")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:$ytdl")

    // Song finder: on-device AI (music separation model) runs on ONNX Runtime.
    val ort = "1.31.0"
    implementation("com.microsoft.onnxruntime:onnxruntime-android:$ort")

    testImplementation("junit:junit:4.13.2")
    // The same ONNX Runtime for the desktop, so separation tests run the real model.
    testImplementation("com.microsoft.onnxruntime:onnxruntime:$ort")
    // UI tests on the desktop JVM: Robolectric runs the real Android/Compose code, Roborazzi
    // captures screenshots from the app.
    testImplementation("org.robolectric:robolectric:4.17")
    // Media3's own Robolectric helpers: run the real ExoPlayer + audio output in tests.
    testImplementation("androidx.media3:media3-test-utils:$media3")
    testImplementation("androidx.media3:media3-test-utils-robolectric:$media3")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.76.0")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("androidx.test:core:1.7.0")
    testImplementation("androidx.test.ext:junit:1.3.0")
    testImplementation("androidx.work:work-testing:2.11.2")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("org.json:json:20260814")
}
