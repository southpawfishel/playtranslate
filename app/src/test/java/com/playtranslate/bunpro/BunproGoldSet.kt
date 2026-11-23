package com.playtranslate.bunpro

import java.io.File
import kotlin.random.Random

/**
 * A hand-labelled sample of sentences, and the scoring of a matcher run
 * against it. Used only by [BunproGrammarCorpusReport]; the file itself lives
 * in the eval directory, never in the repo.
 *
 * ## File format (`gold.txt`)
 *
 * ```
 * #12 永遠に消え去ることになるのよ。
 * + 7-12 ことになる | #123 ことになる (it has been decided that)
 * 2 3-5 ない | #51 る-Verb (Negative) ; #290 い-Adjectives くない
 * - 0-2 てき | #300 的
 * ! しまった | #140
 * ```
 *
 * One line per judgement, the first character being the verdict:
 *  - `+`  the FIRST point listed is what this span is.
 *  - `2`…`9`  that numbered point in the list is the right one instead.
 *  - `-`  this is not grammar at all here; every point listed is wrong.
 *  - `!`  something the matcher MISSED: the text, then `#id` of the point.
 *
 * Only the verdict character, the offsets and the `#id`s are read back; the
 * titles and glosses are there for the person labelling.
 */
internal object BunproGoldSet {

    class Judgement(val begin: Int, val end: Int, val correct: Long?, val wrong: Set<Long>)

    class Labelled(val judgements: List<Judgement>, val missed: List<Long>)

    private val ID = Regex("#(\\d+)")
    private val SPAN = Regex("^(\\d+)-(\\d+)\\b")

    // ── Draft ────────────────────────────────────────────────────────────

    /** Picks [count] sentences worth labelling: long enough to hold grammar,
     *  mostly Japanese (OCR noise is common in captures), fixed seed so the
     *  sample is reproducible. */
    fun sample(sentences: List<String>, count: Int): List<String> =
        sentences.distinct()
            .filter { s ->
                s.length in 10..90 && s.any { it in '぀'..'ゟ' } &&
                    s.count { it in '぀'..'ヿ' || it in '一'..'鿿' } >= s.length * 0.75
            }
            .shuffled(Random(20261122))
            .take(count)

    /** A draft with every match pre-marked `+`, for a person to correct. */
    fun draft(sample: List<String>, matchesOf: (String) -> List<GrammarMatch>): String =
        sample.mapIndexed { i, s ->
            (listOf("#${i + 1} $s") + matchesOf(s).map { line('+', s, it) }).joinToString("\n")
        }.joinToString("\n\n") + "\n"

    /** One judgement line for [m], in the file's own format. */
    private fun line(verdict: Char, s: String, m: GrammarMatch): String =
        "$verdict ${m.begin}-${m.end} ${s.substring(m.begin, m.end)} | " +
            m.candidates.joinToString(" ; ") { c ->
                "#${c.pointId} ${c.title.trim()}" +
                    (c.meaning?.takeIf { it.isNotBlank() }?.let { " (${it.trim()})" } ?: "")
            }

    // ── Read ─────────────────────────────────────────────────────────────

    fun read(file: File): Map<String, Labelled> {
        val out = LinkedHashMap<String, Labelled>()
        var sentence: String? = null
        var judgements = mutableListOf<Judgement>()
        var missed = mutableListOf<Long>()
        fun close() {
            sentence?.let { out[it] = Labelled(judgements, missed) }
            judgements = mutableListOf()
            missed = mutableListOf()
        }
        for (raw in file.readLines()) {
            val line = raw.trimEnd()
            if (line.isBlank()) continue
            if (line.startsWith("#")) {
                close()
                sentence = line.substringAfter(' ')
                continue
            }
            val verdict = line[0]
            val rest = line.drop(1).trim()
            val ids = ID.findAll(rest.substringAfter('|', rest)).map { it.groupValues[1].toLong() }.toList()
            if (verdict == '!') {
                missed += ids
                continue
            }
            val span = SPAN.find(rest) ?: continue
            val correct = when {
                verdict == '+' -> ids.firstOrNull()
                verdict.isDigit() -> ids.getOrNull(verdict.digitToInt() - 1)
                else -> null
            }
            judgements += Judgement(
                span.groupValues[1].toInt(), span.groupValues[2].toInt(),
                correct, ids.filter { it != correct }.toSet(),
            )
        }
        close()
        return out
    }

    // ── Score ────────────────────────────────────────────────────────────

    /**
     * Scores what the matcher puts FIRST on each span, ignoring the studied
     * filter — whether a match is correct does not depend on what the user
     * has mastered.
     *
     * Spans are compared by OVERLAP, not equality: a later matcher may report
     * ていた where this one reported て and いた, and that is the same finding.
     * A match the gold file says nothing about is counted as unlabelled
     * rather than guessed at.
     */
    fun score(
        gold: Map<String, Labelled>,
        matchesOf: (String) -> List<GrammarMatch>,
        titleOf: (Long) -> String,
    ): String {
        var right = 0
        var wrong = 0
        var unlabelled = 0
        var expected = 0
        var found = 0
        val wrongLines = mutableListOf<String>()
        val missLines = mutableListOf<String>()
        val newLines = mutableListOf<String>()

        for ((s, labelled) in gold) {
            val matches = matchesOf(s)
            for (m in matches) {
                val near = labelled.judgements.filter { it.begin < m.end && m.begin < it.end }
                val id = m.primary.pointId
                val text = "${s.substring(m.begin, m.end)} → ${m.primary.title.trim()}"
                when {
                    near.any { it.correct == id } || id in labelled.missed -> right++
                    near.any { id in it.wrong } -> {
                        wrong++
                        wrongLines += "$text\n      $s"
                    }
                    else -> {
                        unlabelled++
                        // Printed as a ready-made judgement line, so labelling
                        // it is a paste into gold.txt and a verdict.
                        newLines += "$text\n      $s\n      ${line('?', s, m)}"
                    }
                }
            }
            for (j in labelled.judgements) {
                val want = j.correct ?: continue
                expected++
                if (matches.any { it.primary.pointId == want && it.begin < j.end && j.begin < it.end }) {
                    found++
                } else {
                    missLines += "${s.substring(j.begin, j.end)} → ${titleOf(want)}\n      $s"
                }
            }
            for (want in labelled.missed) {
                expected++
                if (matches.any { it.primary.pointId == want }) found++
                else missLines += "(never matched) → ${titleOf(want)}\n      $s"
            }
        }

        fun pct(a: Int, b: Int) = if (b == 0) "n/a" else "%.1f%%".format(100.0 * a / b)
        return buildString {
            append("gold sentences: ${gold.size}\n")
            append("precision: ${pct(right, right + wrong)}  ($right right, $wrong wrong)\n")
            append("recall:    ${pct(found, expected)}  ($found of $expected expected points found)\n")
            append("unlabelled matches: $unlabelled\n")
            for ((title, lines) in listOf(
                "WRONG" to wrongLines, "MISSED" to missLines, "UNLABELLED" to newLines,
            )) {
                append("\n== $title (${lines.size}) ==\n")
                lines.forEach { append("  ").append(it).append('\n') }
            }
        }
    }
}
