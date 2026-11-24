package com.playtranslate.ui

import org.junit.Assert.assertEquals
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
 * no answer about. Four surfaces sat dark that way for months, each with the
 * Anki deck back-fill implemented right beside the gap.
 *
 * Since 4.0.0 every word row in the app is drawn by one list ([WordRowList]),
 * so the rule has one place to hold: the list resolves the standing for every
 * row it is given, and the only other word on screen, the word page's own
 * header, adds its pill natively. What can still go wrong is the list losing
 * that wiring, a second place starting to build word rows, or a host opting
 * out without a reason; each is checked here.
 *
 * A source scan rather than a behavioural test for the same reason as before:
 * the bug is an absence. Same idiom as
 * [com.playtranslate.net.PtHttpEnforcementTest].
 */
class BunproPillCoverageTest {

    /**
     * Files allowed to turn the pill off for their list
     * (`override val bunproBadges ... false`), with the reason. Keep this
     * short and justified — it is the escape hatch the test guards.
     */
    private val optedOut = emptyMap<String, String>()

    private val root: File = listOf(
        File("src/main/java/com/playtranslate/ui"),
        File("app/src/main/java/com/playtranslate/ui"),
    ).firstOrNull { it.isDirectory }
        ?: error("source root not found (cwd=${File(".").absolutePath})")

    private fun sources(): List<File> =
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    @Test
    fun `the one word list resolves and draws the Bunpro standing`() {
        val list = File(root, "WordRowList.kt").readText()
        assertTrue(
            "WordRowList must resolve its words through BunproPills.fill",
            list.contains("BunproPills.fill("),
        )
        assertTrue(
            "WordRowList.publish must put the resolved standing on the row's data",
            Regex("""bunpro\s*=\s*bunproByWord\[""").containsMatchIn(list),
        )
    }

    @Test
    fun `word rows are built in one place`() {
        val builders = sources()
            .filter { Regex("""DefRow\.Word\s*\(""").containsMatchIn(it.readText()) }
            .map { it.name }
        assertEquals(
            "A second file builds word rows, so its words bypass the list that " +
                "resolves the Bunpro standing. Route it through WordRowList, or " +
                "resolve the standing there too and extend this test.",
            listOf("WordRowList.kt"),
            builders,
        )
    }

    @Test
    fun `no list turns the pill off without a reason`() {
        val off = Regex("""override\s+val\s+bunproBadges[^\n]*false""")
        val offenders = sources()
            .filter { off.containsMatchIn(it.readText()) }
            .map { it.name }
            .filter { it !in optedOut }
        assertTrue(
            "These lists turn the Bunpro pill off; add them to optedOut with a " +
                "reason if that is deliberate. Offenders: $offenders",
            offenders.isEmpty(),
        )
        val stale = optedOut.keys.filter { name ->
            val f = File(root, name)
            !f.isFile || !off.containsMatchIn(f.readText())
        }
        assertTrue("Stale Bunpro-pill exemptions, remove them: $stale", stale.isEmpty())
    }

    @Test
    fun `the word page's own header adds its pill`() {
        assertTrue(
            "The word page's header is drawn natively, outside WordRowList, so " +
                "it must add the Bunpro pill itself (maybeAddBunproBadge).",
            File(root, "WordDetailBinder.kt").readText().contains("maybeAddBunproBadge(badgeRow"),
        )
    }
}
