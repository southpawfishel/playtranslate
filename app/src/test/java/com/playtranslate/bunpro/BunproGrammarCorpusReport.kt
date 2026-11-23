package com.playtranslate.bunpro

import com.playtranslate.dictionary.SudachiJapaneseTokenizer
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Opt-in corpus report: runs the REAL matcher, with a REAL Sudachi dictionary,
 * over real sentences, and writes what it found to a text file for reading.
 *
 * Skipped unless `BUNPRO_EVAL_DIR` names a directory holding:
 *  - `system_*.dic`      — a Sudachi system dictionary
 *  - `points.tsv`        — id, title, level, slug, meaning, aliases (`|`-joined)
 *  - `forms.tsv`         — point id, structure forms (`|`-joined)   [optional]
 *  - `studied.tsv`       — point id, streak                          [optional]
 *  - `sentences_ja.txt`  — one sentence per line
 *
 * None of that data belongs in the repo (catalogue content, a 200 MB
 * dictionary, the user's own captures), which is why this reads a directory
 * instead of test resources. Output: `report[_LABEL].txt` in the same
 * directory, `BUNPRO_EVAL_LABEL` being optional.
 *
 * This is a REPORT, not an assertion: it exists so a matcher change can be
 * compared before/after on real text rather than argued from examples.
 */
class BunproGrammarCorpusReport {

    @Test
    fun `write corpus report`() {
        val dir = System.getenv("BUNPRO_EVAL_DIR")?.let(::File)
        assumeTrue("BUNPRO_EVAL_DIR not set", dir != null && dir.isDirectory)
        dir!!
        val dic = dir.listFiles { f -> f.name.startsWith("system") && f.name.endsWith(".dic") }
            ?.minByOrNull { it.name }
        assumeTrue("no system_*.dic in $dir", dic != null)

        fun rows(name: String): List<List<String>> =
            File(dir, name).takeIf { it.isFile }?.readLines()
                ?.filter { it.isNotBlank() }?.map { it.split('\t') }.orEmpty()

        val points = rows("points.tsv").map { r ->
            BunproGrammarPoint(
                id = r[0].toLong(), title = r[1], level = r.getOrNull(2),
                slug = r.getOrNull(3), meaning = r.getOrNull(4),
                metadata = r.getOrNull(5).orEmpty().replace("|", ", "),
            )
        }
        val forms = rows("forms.tsv").associate { r ->
            r[0].toLong() to r.getOrNull(1).orEmpty().split('|').filter { it.isNotBlank() }
        }
        val studied = rows("studied.tsv").associate { r -> r[0].toLong() to r[1].toInt() }
        val sentences = File(dir, "sentences_ja.txt").readLines().filter { it.isNotBlank() }

        val index = BunproGrammarIndex(points, extraForms = forms)
        // What the alias filter removed, for eyeballing the rules themselves.
        File(dir, "aliases_dropped.txt").writeText(
            points.mapNotNull { p ->
                val listed = (p.japaneseAliases() + forms[p.id].orEmpty()).distinct()
                val kept = index.admittedFor(p.id)
                val dropped = listed - kept.aliases.toSet()
                if (dropped.isEmpty() && kept.requiresAfter.isEmpty()) null
                else listOfNotNull(
                    p.title.trim(),
                    "  dropped: ${dropped.joinToString(" ")}",
                    "  kept: ${kept.aliases.joinToString(" ")}",
                    kept.requiresAfter.takeIf { it.isNotEmpty() }?.let { "  conditional: $it" },
                ).joinToString("\n")
            }.joinToString("\n"),
        )
        val out = StringBuilder()
        // form → primary title, counted, for each relevance setting.
        val all = HashMap<String, Int>()
        val shown = HashMap<String, Int>()
        var linesAll = 0
        var linesShown = 0
        var ambiguous = 0
        var total = 0

        SudachiJapaneseTokenizer.create(dic!!).use { tok ->
            for (s in sentences) {
                val tokens = tok.analyze(s)
                val everything = index.match(s, tokens)
                val relevant = index.match(s, tokens, srsOf = { studied[it] })
                if (everything.isNotEmpty()) linesAll++
                if (relevant.isNotEmpty()) linesShown++
                everything.forEach { all.merge(key(it), 1, Int::plus) }
                relevant.forEach {
                    shown.merge(key(it), 1, Int::plus)
                    total++
                    if (it.isAmbiguous) ambiguous++
                }
                out.append(s).append('\n')
                out.append("  tok: ").append(tokens.joinToString(" ") { t ->
                    if (t.dictionaryForm == t.surface) t.surface else "${t.surface}<${t.dictionaryForm}>"
                }).append('\n')
                val relevantSpans = relevant.map { it.begin to it.end }.toSet()
                everything.forEach { m ->
                    val mark = if ((m.begin to m.end) in relevantSpans) "*" else " "
                    out.append("  $mark ${s.substring(m.begin, m.end)} [${m.via} ${m.matchedForm}] → ")
                    out.append(m.candidates.joinToString(" | ") { c ->
                        c.title.trim() + (c.streak?.let { "(s$it)" } ?: "")
                    }).append('\n')
                }
            }
        }

        val head = StringBuilder()
        head.append("sentences: ${sentences.size}\n")
        head.append("with any match: $linesAll   with a SHOWN match (studied filter): $linesShown\n")
        head.append("shown matches: $total   ambiguous: $ambiguous\n\n")
        for ((label, counts) in listOf("SHOWN (after studied filter)" to shown, "ALL" to all)) {
            head.append("== $label: form → primary point, by frequency ==\n")
            counts.entries.sortedByDescending { it.value }.take(150).forEach {
                head.append("${it.value}\t${it.key}\n")
            }
            head.append('\n')
        }
        head.append("== per sentence (* = shown after studied filter) ==\n")

        val label = System.getenv("BUNPRO_EVAL_LABEL")?.takeIf { it.isNotBlank() }
        File(dir, if (label == null) "report.txt" else "report_$label.txt")
            .writeText(head.toString() + out)
    }

    private fun key(m: GrammarMatch): String =
        "${m.via} ${m.matchedForm} → ${m.primary.title.trim()}" +
            if (m.candidates.size > 1) " (+${m.candidates.size - 1})" else ""
}
