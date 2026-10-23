package com.playtranslate.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Holds the styled meta row in step with the flat one.
 *
 * The Yomitan renderer does not decorate the flat body, it REPLACES it
 * (`body.visibility = GONE` in `MagnifierLens.attachSectionStyled`) and
 * rebuilds the meta row from [styledMetaChips]. So a chip added to
 * [WordDefinitionsView.buildMetaRow] and not mirrored there vanishes on
 * exactly the surfaces an imported dictionary styles — and nowhere else,
 * which is what makes it so hard to spot: the same word keeps its chip in the
 * flat word list while losing it in the lens.
 *
 * That is precisely how the Bunpro pill went missing. This test compares the
 * `data.<field>` reads of the two builders, so the next chip can't repeat it.
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

    private val fieldRef = Regex("""\bdata\.([A-Za-z][A-Za-z0-9]*)""")

    private fun fieldsRead(body: String): Set<String> =
        fieldRef.findAll(body).map { it.groupValues[1] }.toSet()

    @Test
    fun `the styled meta row reads every field the flat meta row does`() {
        val flat = fieldsRead(functionBody(source("WordDefinitionsView.kt"), "buildMetaRow"))
        val styled = fieldsRead(functionBody(source("YomitanStyledData.kt"), "styledMetaChips"))

        // Sanity: if the extraction broke, the comparison below would pass
        // vacuously and guard nothing.
        assertTrue("extracted no fields from buildMetaRow — selector broken", flat.isNotEmpty())

        val missing = flat - styled
        assertEquals(
            "These meta fields drive a chip in WordDefinitionsView.buildMetaRow but " +
                "are never read by styledMetaChips, so their chip disappears wherever " +
                "a Yomitan dictionary styles the panel (the lens, the styled word " +
                "cells) while surviving in the flat list. Mirror them — via the " +
                "badge's own metaChip() builder, not a label-only assembly. Missing:",
            emptySet<String>(),
            missing,
        )
    }

    @Test
    fun `the Bunpro chip survives the styled renderer`() {
        // The regression itself, pinned behaviourally rather than structurally:
        // the field-parity check above would also pass if styledMetaChips merely
        // mentioned data.bunpro without emitting anything.
        val styled = functionBody(source("YomitanStyledData.kt"), "styledMetaChips")
        assertTrue(
            "styledMetaChips must build the Bunpro chip through BunproBadge.metaChip, " +
                "so the styled row and the native pill can't drift in label or glyph.",
            styled.contains("BunproBadge.metaChip("),
        )
    }
}
