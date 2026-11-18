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

// The screen recognizer's models (PaddleOCR PP-OCRv4, Apache-2.0) as RapidOCR ships them in its
// wheel on PyPI, checked against the wheel's hash and packed into the APK's assets (OcrModels.kt).
val ocrWheelUrl = "https://files.pythonhosted.org/packages/ba/12/" +
    "1e5497183bdbe782dbb91bad1d0d2297dba4d2831b2652657f7517bfc6df/rapidocr_onnxruntime-1.4.4-py3-none-any.whl"
val ocrWheelSha256 = "971d7d5f223a7a808662229df1ef69893809d8457d834e6373d3854bc1782cbf"
val ocrModels = listOf("ch_PP-OCRv4_det_infer.onnx", "ch_PP-OCRv4_rec_infer.onnx")
val ocrAssets = layout.buildDirectory.dir("generated/ocr-assets").get().asFile
val fetchOcrModels by tasks.registering {
    group = "build"
    description = "Fetches the screen recognizer's models into the APK's assets."
    outputs.dir(ocrAssets)
    doLast {
        val dir = File(ocrAssets, "ocr").apply { mkdirs() }
        if (ocrModels.all { File(dir, it).isFile }) return@doLast
        val wheel = File(temporaryDir, "rapidocr.whl")
        java.net.URI(ocrWheelUrl).toURL().openStream().use { input ->
            wheel.outputStream().use { input.copyTo(it) }
        }
        val sha = java.security.MessageDigest.getInstance("SHA-256").digest(wheel.readBytes())
            .joinToString("") { "%02x".format(it) }
        check(sha == ocrWheelSha256) { "RapidOCR's wheel has hash $sha, not $ocrWheelSha256" }
        java.util.zip.ZipFile(wheel).use { zip ->
            for (name in ocrModels) {
                val entry = zip.getEntry("rapidocr_onnxruntime/models/$name") ?: error("$name is not in the wheel")
                zip.getInputStream(entry).use { input -> File(dir, name).outputStream().use { input.copyTo(it) } }
            }
        }
    }
}
android.sourceSets["main"].assets.srcDir(ocrAssets)

tasks.named("preBuild") { dependsOn(buildRust, fetchOcrModels) }
