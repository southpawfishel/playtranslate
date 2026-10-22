package com.playtranslate.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Enforces the rule that a word the user can tap or hover always states its
 * Bunpro standing.
 *
 * This exists because the violation is INVISIBLE. A surface nobody wired
 * renders [com.playtranslate.bunpro.BunproLookup.Outcome.Unavailable], and
 * that is the one outcome [BunproBadge] deliberately draws nothing for — so a
 * surface that had never been connected looked exactly like a word Bunpro had
 * no answer about. Four surfaces sat dark that way for months (the source-text
 * lens on both hosts, the dictionary lookup page, the word-detail page's
 * related rows, and both lenses' secondary sections), each with the Anki deck
 * back-fill implemented right beside the gap.
 *
 * A source scan rather than a behavioural test for the same reason: the bug is
 * an absence, and no amount of exercising the surfaces that DO work would ever
 * have caught it. Same idiom as
 * [com.playtranslate.net.PtHttpEnforcementTest].
 */
class BunproPillCoverageTest {

    /** Builds the body for a word, a word row, or a lens section — i.e. puts
     *  a word in front of the user. */
    private val showsWords =
        Regex("""WordDefinitionData\s*\(|WordResultCell\s*\(|LensSection\s*\(""")

    /** Resolves or applies a Bunpro standing, by any of the routes. */
    private val resolvesBunpro = Regex("""BunproPills\.|updateBunpro\s*\(|bunpro\s*=|withBunpro\s*\(""")

    /**
     * Files that show words but must NOT resolve a standing themselves, with
     * the reason. Keep this short and justified — it is the escape hatch the
     * test guards, not a dumping ground.
     */
    private val notLookupSurfaces = mapOf(
        "SentenceAnkiContentView.kt" to
            "Anki card editor: tapping a word includes/excludes it from the card, " +
                "it is not a lookup, and SRS standing is not part of what is being edited.",
        "WordAnkiReviewBinder.kt" to
            "Anki card editor for one word, reached FROM a surface that already " +
                "showed the pill; it edits card fields, not the word's standing.",
        "MagnifierLens.kt" to
            "The lens VIEW. It renders the sections it is handed; the standing is " +
                "resolved by its drivers (SourceTextLens, DragLookupController), " +
                "which have the scope and the word identities.",
        "SourceWordLookup.kt" to
            "Resolver, not a surface: it builds the data a lens then binds, and " +
                "SourceTextLens fills the standing onto it.",
        "WordDefinitionData.kt" to
            "The data class that DECLARES the bunpro field; matched only because " +
                "its own constructor shares the name.",
    )

    @Test
    fun `every surface that shows a word also resolves its Bunpro standing`() {
        // testDebugUnitTest runs with the module dir as cwd; fall back to the
        // repo-root-relative path just in case.
        val root = listOf(
            File("src/main/java/com/playtranslate/ui"),
            File("app/src/main/java/com/playtranslate/ui"),
        ).firstOrNull { it.isDirectory }
            ?: error("source root not found (cwd=${File(".").absolutePath})")

        val offenders = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.name !in notLookupSurfaces }
            .filter { f ->
                val text = f.readText()
                showsWords.containsMatchIn(text) && !resolvesBunpro.containsMatchIn(text)
            }
            .map { it.relativeTo(root).path }
            .toList()

        assertTrue(
            "These surfaces show a word the user can tap but never resolve its " +
                "Bunpro standing, so the pill is silently missing there. Fill it " +
                "with BunproPills.fill/fillOne, or — if the surface genuinely is " +
                "not a lookup — add it to notLookupSurfaces with a reason. " +
                "Offenders: $offenders",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `the exemption list stays honest`() {
        val root = listOf(
            File("src/main/java/com/playtranslate/ui"),
            File("app/src/main/java/com/playtranslate/ui"),
        ).firstOrNull { it.isDirectory } ?: error("source root not found")

        // An exemption is stale when the file is gone or no longer shows words
        // (it would then silently excuse a future file taking that name), and
        // redundant when the file resolves a standing anyway — either way it
        // should not be carrying an excuse.
        val stale = notLookupSurfaces.keys.filter { name ->
            val f = File(root, name)
            !f.isFile || !showsWords.containsMatchIn(f.readText())
        }
        val redundant = notLookupSurfaces.keys.filter { name ->
            val f = File(root, name)
            f.isFile && resolvesBunpro.containsMatchIn(f.readText())
        }
        assertTrue("Stale Bunpro-pill exemptions, remove them: $stale", stale.isEmpty())
        assertTrue(
            "These files resolve a Bunpro standing and need no exemption: $redundant",
            redundant.isEmpty(),
        )
    }
}
