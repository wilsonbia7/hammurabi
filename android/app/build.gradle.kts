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
        versionCode = 1
        versionName = "0.1.0"
        // Phones of the last decade; the speech model is too slow on older 32-bit ones anyway.
        ndk { abiFilters += listOf("arm64-v8a") }
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
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // libonnxruntime.so for the engine's voice detector (Silero VAD). Its Java API goes unused.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.27.0")
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
