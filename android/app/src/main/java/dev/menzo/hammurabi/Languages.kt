package dev.menzo.hammurabi

import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import java.text.Collator
import java.util.Locale
import java.util.concurrent.TimeUnit

/** The languages ML Kit translates, by their BCP-47 codes ("en", "tr", "zh"). */
object Languages {
    fun all(): List<String> = TranslateLanguage.getAllLanguages().toList()

    /** ML Kit's code for [tag] ("en-US", "iw"), or null when it does not translate that language. */
    fun supported(tag: String): String? {
        // Written in Latin letters ("hi-Latn"): the language's model reads its own script.
        if (tag.isBlank() || tag.endsWith("-Latn", ignoreCase = true)) return null
        val code = when (val language = tag.substringBefore('-').substringBefore('_').lowercase(Locale.ROOT)) {
            // Java's old codes, which Android still gives for these.
            "iw" -> "he"
            "in" -> "id"
            "ji" -> "yi"
            else -> language
        }
        return TranslateLanguage.fromLanguageTag(code)
    }

    /** The phone's language when ML Kit translates into it, else English. */
    fun defaultTarget(): String = supported(Locale.getDefault().language) ?: TranslateLanguage.ENGLISH

    /** In the phone's language: "İngilizce", "Çince". */
    fun name(code: String, locale: Locale = Locale.getDefault()): String =
        Locale.forLanguageTag(code).getDisplayName(locale)
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }

    /** All, in the order of their names in the phone's language. */
    fun byName(locale: Locale = Locale.getDefault()): List<String> {
        val collator = Collator.getInstance(locale)
        return all().sortedWith { a, b -> collator.compare(name(a, locale), name(b, locale)) }
    }
}

/**
 * ML Kit's translation, on the phone. A language's model (about 30 MB) is downloaded the first time it is
 * needed; English is the bridge between two others. All calls but [close] wait: off the main thread only.
 */
object Translations {
    private val translators = HashMap<String, Translator>()
    private val models by lazy { RemoteModelManager.getInstance() }

    /** Any network: a model is downloaded only when the user asks for a translation that needs it. */
    private fun conditions() = DownloadConditions.Builder().build()

    @Synchronized
    private fun translator(from: String, to: String): Translator = translators.getOrPut("$from>$to") {
        Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(from).setTargetLanguage(to).build())
    }

    /** Whether [lang]'s model is on the phone. */
    fun isDownloaded(lang: String): Boolean = try {
        Tasks.await(models.isModelDownloaded(TranslateRemoteModel.Builder(lang).build()), 10, TimeUnit.SECONDS)
    } catch (e: Exception) {
        AppLog.w("translate: could not tell whether $lang is downloaded", e)
        false
    }

    /** Downloads [lang]'s model if it is not on the phone; throws when that fails. */
    fun download(lang: String) {
        Tasks.await(models.download(TranslateRemoteModel.Builder(lang).build(), conditions()), 10, TimeUnit.MINUTES)
    }

    /** Gets both models of [from] → [to] onto the phone; throws when that fails. */
    fun ensure(from: String, to: String) {
        Tasks.await(translator(from, to).downloadModelIfNeeded(conditions()), 10, TimeUnit.MINUTES)
    }

    fun translate(text: String, from: String, to: String): String =
        Tasks.await(translator(from, to).translate(text), 60, TimeUnit.SECONDS)

    /** Frees the translators' memory; they open again when needed. */
    @Synchronized
    fun close() {
        translators.values.forEach { runCatching { it.close() } }
        translators.clear()
    }
}
