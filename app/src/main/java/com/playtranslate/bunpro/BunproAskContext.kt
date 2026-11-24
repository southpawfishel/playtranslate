package com.playtranslate.bunpro

import android.content.Context
import com.playtranslate.ask.AskPresets
import com.playtranslate.ask.AskQuestion
import com.playtranslate.ask.AskSentence
import com.playtranslate.cloud.PtCloudBackend

/**
 * Hands the Ask section's grammar breakdown what the Bunpro matcher found in
 * the sentence, so the two answer the same question together instead of side
 * by side.
 *
 * Each is weak where the other is strong. The matcher names real catalogue
 * points and knows the user's reviews, but it matches on form alone: it
 * cannot tell から "because" from から "from", and it misses what its aliases
 * do not spell. A model reads the sentence, but left alone it names grammar
 * in its own words, which the user then cannot find in Bunpro.
 *
 * So the candidates go into the question as HINTS. The model is asked to
 * settle each one (use the catalogue's name where a candidate applies, pick
 * between alternatives on one span, list what it rejected) and, just as
 * much, to keep explaining grammar the list does not have. The list never
 * narrows the answer: a breakdown that only confirmed candidates would
 * inherit every miss the matcher makes.
 *
 * Only the ready-made breakdown is touched. The user's own question and a
 * word's question go out as they were asked.
 */
object BunproAskContext {

    /** Alternatives offered for one span. The matcher ranks them best
     *  first, and past a few the list costs more words than it settles. */
    private const val MAX_ALTERNATIVES = 3

    /** Spans listed. A sentence with more than this is noise or a
     *  paragraph, and the list would outweigh the question. */
    private const val MAX_SPANS = 12

    /** The longest wording a question may have for every service to take
     *  it: PlayTranslate Cloud's cap, which is the only one there is. */
    private const val MAX_QUESTION_CHARS = PtCloudBackend.MAX_ASK_CODE_POINTS

    /** Room left for the wording's language token being filled. */
    private const val TOKEN_MARGIN = 24

    /** A candidate's meaning is a gloss here, not the catalogue's entry. */
    private const val MAX_MEANING = 48

    /**
     * What [AskSession][com.playtranslate.ask.AskSession] runs a question
     * through before putting it: the breakdown with the sentence's
     * candidates added to its wording, anything else unchanged. Unchanged
     * too when Bunpro is off, the catalogue is not synced or nothing
     * matched, since [BunproGrammarLookup.forSentence] is empty for all of
     * those.
     */
    fun preparer(ctx: Context): suspend (AskQuestion, AskSentence) -> AskQuestion {
        val app = ctx.applicationContext
        return prepare@{ question, sentence ->
            val preset = (question as? AskQuestion.Preset)?.preset
            if (preset?.id != AskPresets.GRAMMAR_BREAKDOWN) return@prepare question
            val matches = BunproGrammarLookup.forSentence(app, sentence.text)
            val room = MAX_QUESTION_CHARS - preset.question.length - SEPARATOR.length - TOKEN_MARGIN
            val extra = wording(sentence.text, matches, room) ?: return@prepare question
            AskQuestion.Preset(preset.copy(question = preset.question + SEPARATOR + extra))
        }
    }

    /**
     * The paragraph added to the breakdown's wording for [matches] in
     * [sentence]; null with nothing to add.
     *
     * One line per matched span, in sentence order: the text it covers,
     * then each candidate as the catalogue names it, with its meaning and
     * level. The wording is a template to its reader
     * ([com.playtranslate.ask.AskPrompts.build] fills it), so braces are
     * taken out of the catalogue's text: a meaning that happened to read
     * `{source}` would otherwise be replaced.
     *
     * [room] is the most characters the paragraph may take. A service that
     * takes a question's parts caps the wording (PlayTranslate Cloud) and
     * refuses a longer one outright, so the list is cut to fit: the spans
     * that fit, from the sentence's start, and nothing at all when not even
     * one does.
     */
    internal fun wording(
        sentence: String,
        matches: List<GrammarMatch>,
        room: Int = Int.MAX_VALUE,
    ): String? {
        val all = matches.sortedBy { it.begin }.take(MAX_SPANS).mapNotNull { m ->
            val span = sentence.substring(
                m.begin.coerceIn(0, sentence.length), m.end.coerceIn(0, sentence.length),
            ).ifBlank { m.matchedForm }
            val candidates = m.candidates.take(MAX_ALTERNATIVES).map(::describe)
            if (candidates.isEmpty()) null
            else "- \"${clean(span)}\": " + candidates.joinToString(" OR ")
        }
        // Long arithmetic: the default room is Int.MAX_VALUE.
        var left = room.toLong() - INTRO.length - INSTRUCTIONS.length - 2
        val lines = all.takeWhile { line ->
            left -= line.length + 1
            left >= 0
        }
        if (lines.isEmpty()) return null
        return INTRO + "\n" + lines.joinToString("\n") + "\n\n" + INSTRUCTIONS
    }

    private fun describe(c: GrammarMatch.GrammarCandidate): String = buildString {
        append(clean(c.title))
        BunproHtml.toPlainTextOrNull(c.meaning)?.let { meaning ->
            val gloss = clean(meaning)
            append(" (")
            append(if (gloss.length > MAX_MEANING) gloss.take(MAX_MEANING - 1) + "…" else gloss)
            append(')')
        }
        c.level?.takeIf { it.isNotBlank() }?.let {
            append(" [").append(if (it.startsWith("JLPT")) "N" + it.removePrefix("JLPT") else clean(it)).append(']')
        }
    }

    private fun clean(text: String): String =
        text.replace("{", "").replace("}", "").replace('\n', ' ').trim()

    private const val SEPARATOR = "\n\n"

    // Kept short on purpose: both count against MAX_QUESTION_CHARS, and
    // every word here is a candidate line that does not fit.
    internal const val INTRO =
        "A pattern matcher found these candidate Bunpro grammar points, each after the text it " +
            "matched. They are hints: it matches on form alone, so some do not apply and it misses grammar."

    internal const val INSTRUCTIONS =
        "When a point you explain is one of these, use the catalogue's name and write [Bunpro] " +
            "after it. Where a line has alternatives (OR), choose the one that fits. Skip candidates " +
            "that do not apply. Explain every other grammar point too, listed or not, without the " +
            "tag. If you rejected candidates, end with a line starting \"Not here:\" naming them and why."
}
