package dev.menzo.hammurabi

import android.content.Context
import java.io.File

/**
 * The screen recognizer's models (PaddleOCR PP-OCRv4): packed in the APK's assets by the build, and
 * copied out once to the folder the engine reads them from (`files/ocr`, android/rust `ocr_dir`).
 */
object OcrModels {
    private val NAMES = listOf("ch_PP-OCRv4_det_infer.onnx", "ch_PP-OCRv4_rec_infer.onnx")

    /** Changes when the models do, so a new APK's are copied out again. */
    private const val VERSION = "pp-ocrv4-rapidocr-1.4.4"

    /** False when the APK has no models (built without the fetchOcrModels task). */
    fun ensure(context: Context): Boolean {
        val dir = File(context.filesDir, "ocr").apply { mkdirs() }
        val marker = File(dir, "version")
        if (marker.isFile && marker.readText() == VERSION && NAMES.all { File(dir, it).isFile }) return true
        return try {
            for (name in NAMES) {
                val part = File(dir, "$name.part")
                context.assets.open("ocr/$name").use { input -> part.outputStream().use { input.copyTo(it) } }
                check(part.renameTo(File(dir, name))) { "could not place $name" }
            }
            marker.writeText(VERSION)
            true
        } catch (e: java.io.IOException) {
            false
        } catch (e: IllegalStateException) {
            // The copy could not be put in place: reported as no models, not a crash.
            false
        }
    }
}
