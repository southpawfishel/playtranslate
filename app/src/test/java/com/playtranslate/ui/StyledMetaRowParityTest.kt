package com.playtranslate.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Holds the Bunpro chip in the definitions document's meta row.
 *
 * Every word row is drawn by the document, and its meta row is built by
 * [styledMetaChips] alone. Before 4.0.0 there was a second, native builder
 * and this test compared the two, because a chip added to one and not the
 * other vanished on exactly the surfaces an imported dictionary styled —
 * which is how the Bunpro pill once went missing from the lens. With one
 * builder left there is nothing to compare, but the chip can still be
 * dropped from it, and that would remove the pill from every list at once.
 */
class StyledMetaRowParityTest {

    private fun source(name: String): String =
        listOf(
            File("src/main/java/com/playtranslate/ui/$name"),
            File("app/src/main/java/com/playtranslate/ui/$name"),
        ).firstOrNull { it.isFile }?.readText()
            ?: error("$name not found (cwd=${File(".").absolutePath})")

    /** The body of `fun <name>(` up to the matching close, by brace depth. */
    private fun functionBody(src: String, name: String): String {
        val start = src.indexOf("fun $name(")
        assertTrue("fun $name( not found", start >= 0)
        val open = src.indexOf('{', start)
        var depth = 0
        for (i in open until src.length) {
            when (src[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return src.substring(open, i + 1)
            }
        }
        error("unbalanced braces in $name")
    }

    @Test
    fun `the Bunpro chip is built by the meta row`() {
        val styled = functionBody(source("YomitanStyledData.kt"), "styledMetaChips")
        assertTrue(
            "styledMetaChips must build the Bunpro chip through BunproBadge.metaChip, " +
                "so the document's row and the native pill can't drift in label or glyph.",
            styled.contains("BunproBadge.metaChip("),
        )
    }
}
