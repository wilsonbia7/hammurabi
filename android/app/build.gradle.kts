plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.menzo.hammurabi"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.menzo.hammurabi"
        // Recording other apps' audio (AudioPlaybackCapture) came in Android 10.
        minSdk = 29
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0"
        // Phones of the last decade; the speech model is too slow on older 32-bit ones anyway.
        ndk { abiFilters += listOf("arm64-v8a") }
        // The on-device tests (src/androidTest), run on an emulator in CI.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        getByName("debug") {
            // One key for every build, CI's included (each CI machine would make its own): a new APK
            // installs over the one on the phone and keeps the models it downloaded. A debug key,
            // not secret; a store release needs its own.
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Signed with the debug key so test builds install; sign properly before publishing.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        // build/reports/lint-results-debug.txt, which CI shows.
        textReport = true
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // libonnxruntime.so for the engine's voice detector (Silero VAD). Its Java API goes unused.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.27.0")

    // Translating the screen, on the phone: Google's ML Kit reads the text (its models are in the
    // APK, about 4 MB a script), tells its language, and translates it (a model of about 30 MB per
    // language, downloaded once). Phones and tablets only, as ML Kit's terms allow.
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    implementation("com.google.mlkit:text-recognition-japanese:16.0.1")
    implementation("com.google.mlkit:text-recognition-korean:16.0.1")
    implementation("com.google.mlkit:language-id:17.0.6")
    implementation("com.google.mlkit:translate:17.0.3")

    // The on-device tests only; the app itself uses no AndroidX.
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("junit:junit:4.13.2")
}

// The engine: android/rust, built with cargo-ndk into jniLibs before every build (cargo is
// incremental, so an unchanged engine costs a second). Needs Rust with the aarch64-linux-android
// target, `cargo install cargo-ndk`, and the NDK (ANDROID_NDK_HOME, or under the SDK).
val rustDir = rootProject.file("rust")
val jniLibsDir = file("src/main/jniLibs")
val buildRust by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds libhammurabi_android.so from android/rust with cargo-ndk."
    workingDir = rustDir
    commandLine(
        "cargo", "ndk",
        "--target", "arm64-v8a",
        "--platform", "29",
        "--output-dir", jniLibsDir.absolutePath,
        "build", "--release",
    )
}

tasks.named("preBuild") { dependsOn(buildRust) }
