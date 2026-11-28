package com.tailcat.vpn.core.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseNotesTest {

    @Test
    fun blankBodyStaysBlank() {
        assertEquals("", ReleaseNotes.readable("  \n\t"))
    }

    @Test
    fun markdownBecomesReadableTextAndKeepsTheWholeBody() {
        val body = """
            ## What's changed

            **Fixed** the tunnel. See [the notes](https://example.com/notes) and <https://example.com/raw>.

            - first item
            * [x] done item
            1. numbered item

            ```
            keep `this` fence
            ```

            <!-- hidden -->
            <details><summary>More</summary>
            The rest of a long release note that used to be cut off after a few lines.
            </details>

            ---
            > Quoted line with *emphasis* and `code`.
        """.trimIndent()

        val text = ReleaseNotes.readable(body)

        assertTrue(text.startsWith("What's changed"))
        assertTrue(text.contains("Fixed the tunnel. See the notes and https://example.com/raw."))
        assertTrue(text.contains("• first item"))
        assertTrue(text.contains("• done item"))
        assertTrue(text.contains("1. numbered item"))
        assertTrue(text.contains("keep `this` fence"))
        assertTrue(text.contains("More"))
        assertTrue(text.contains("The rest of a long release note"))
        assertTrue(text.contains("Quoted line with emphasis and code."))
        assertFalse(text.contains("##"))
        assertFalse(text.contains("**"))
        assertFalse(text.contains("<!--"))
        assertFalse(text.contains("<details>"))
        assertFalse(text.contains("---"))
        assertFalse(text.contains("https://example.com/notes"))
        assertTrue(text.endsWith("Quoted line with emphasis and code."))
    }
}
