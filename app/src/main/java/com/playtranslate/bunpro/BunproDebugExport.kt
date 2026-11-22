package com.playtranslate.bunpro

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import com.playtranslate.dictionary.SudachiJapaneseTokenizer
import com.playtranslate.translationlog.TranslationHistoryStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.InputStream

/**
 * TEMPORARY — DO NOT SHIP. Copies the data needed to evaluate the grammar
 * matcher off-device into `Download/PlayTranslateExport/`:
 *
 *  - `grammar.sqlite` — the catalogue mirror (index, extracted structure
 *    forms, the user's studied set).
 *  - `system_*.dic` — the pack's Sudachi dictionary, so a JVM test can
 *    tokenize real sentences.
 *  - `structure_html_sample.json` — raw structure HTML for a few points. The
 *    store keeps only the forms extracted from it, which is not enough to see
 *    HOW a multi-part pattern was split.
 *  - `sentences_ja.txt` — Japanese source lines from the translation log, as
 *    a real-text corpus.
 *
 * To remove: delete this file, the two `…ForDebugExport` helpers
 * ([SudachiJapaneseTokenizer.Provider.dictionaryFileForDebugExport],
 * [BunproGrammarScraper.fetchStructureHtmlForDebugExport]), and the export row
 * in `BunproSettingsActivity` + `activity_bunpro_settings.xml`.
 */
object BunproDebugExport {

    private const val FOLDER = "PlayTranslateExport"
    private const val STRUCTURE_SAMPLE = 30
    private const val SENTENCE_LIMIT = 5000
    private const val REQUEST_DELAY_MS = 500L

    /** Runs the export and returns a human-readable line per file. */
    suspend fun run(ctx: Context, onStep: (String) -> Unit = {}): String {
        val app = ctx.applicationContext
        val lines = mutableListOf<String>()
        suspend fun step(name: String, block: suspend () -> String) {
            onStep(name)
            lines += try {
                "✓ $name — ${block()}"
            } catch (e: Exception) {
                "✗ $name — ${e.javaClass.simpleName}: ${e.message}"
            }
        }

        step("grammar.sqlite") {
            // Touch the store first so the file exists and is closed-out
            // enough to copy; sidecars go along in case the DB is in WAL mode.
            val points = BunproGrammarStore.indexSize(app)
            val dir = File(app.noBackupFilesDir, "bunpro")
            var bytes = 0L
            for (name in listOf("grammar.sqlite", "grammar.sqlite-wal", "grammar.sqlite-shm")) {
                val f = File(dir, name)
                if (f.isFile) bytes += writeDownload(app, name) { f.inputStream() }
            }
            "$points points, ${bytes / 1024} KB"
        }

        step("Sudachi dictionary") {
            val dic = SudachiJapaneseTokenizer.Provider.dictionaryFileForDebugExport()
                ?: error("no system_*.dic — install the Japanese pack")
            val bytes = writeDownload(app, dic.name) { dic.inputStream() }
            "${dic.name}, ${bytes / (1024 * 1024)} MB"
        }

        step("structure_html_sample.json") {
            val index = BunproGrammarStore.loadIndex(app).associateBy { it.id }
            val forms = BunproGrammarStore.loadStructureForms(app)
            // Points whose tables yielded a short fragment are the ones where
            // the split matters; fall back to catalogue order before a sweep.
            val picked = forms.entries
                .filter { (_, fs) -> fs.any { it.length <= 2 } }
                .sortedByDescending { it.value.size }
                .mapNotNull { index[it.key] }
                .ifEmpty { index.values.toList() }
                .filter { !it.slug.isNullOrBlank() }
                .take(STRUCTURE_SAMPLE)
            var fetched = 0
            val json = buildJsonArray {
                for (p in picked) {
                    val html = BunproGrammarScraper
                        .fetchStructureHtmlForDebugExport(app, p.slug!!)
                    if (html != null) {
                        fetched++
                        add(buildJsonObject {
                            put("id", p.id)
                            put("title", p.title)
                            put("polite_structure", html.first)
                            put("casual_structure", html.second)
                            put("stored_forms", buildJsonArray {
                                forms[p.id].orEmpty().forEach { add(JsonPrimitive(it)) }
                            })
                        })
                    }
                    delay(REQUEST_DELAY_MS)
                }
            }
            writeDownload(app, "structure_html_sample.json") {
                json.toString().byteInputStream()
            }
            "$fetched of ${picked.size} points"
        }

        step("sentences_ja.txt") {
            val sentences = TranslationHistoryStore.recent(app, SENTENCE_LIMIT)
                .filter { it.sourceLang.startsWith("ja") }
                .map { it.sourceText.replace('\n', ' ').trim() }
                .filter { it.isNotEmpty() }
                .distinct()
            writeDownload(app, "sentences_ja.txt") {
                sentences.joinToString("\n").byteInputStream()
            }
            "${sentences.size} lines"
        }

        return "Download/$FOLDER/\n\n" + lines.joinToString("\n")
    }

    /** Writes one file into Downloads via MediaStore (no permission needed on
     *  API 29+), replacing a previous export of the same name. Returns bytes
     *  written. */
    private suspend fun writeDownload(
        ctx: Context,
        name: String,
        open: () -> InputStream,
    ): Long = withContext(Dispatchers.IO) {
        val resolver = ctx.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val relative = Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER + "/"
        resolver.delete(
            collection,
            "${MediaStore.Downloads.RELATIVE_PATH} = ? AND ${MediaStore.Downloads.DISPLAY_NAME} = ?",
            arrayOf(relative, name),
        )
        val uri = resolver.insert(
            collection,
            ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                put(MediaStore.Downloads.RELATIVE_PATH, relative)
            },
        ) ?: error("MediaStore refused $name")
        resolver.openOutputStream(uri)?.use { out ->
            open().use { it.copyTo(out) }
        } ?: error("could not open $name for writing")
    }
}
