package com.tailcat.vpn.core.update

/**
 * GitHub release bodies are Markdown. The update screen shows this plain text
 * so the whole description can be read in the app.
 */
object ReleaseNotes {
    fun readable(body: String): String {
        if (body.isBlank()) return ""
        val normalized = body.replace("\r\n", "\n").replace('\r', '\n')
        val withoutComments = HTML_COMMENT.replace(normalized, "")
        val lines = mutableListOf<String>()
        var inFence = false
        for (raw in withoutComments.lineSequence()) {
            if (raw.trim().startsWith("```")) {
                inFence = !inFence
                continue
            }
            lines += if (inFence) raw.trimEnd() else formatLine(raw)
        }
        return BLANK_RUNS.replace(lines.joinToString("\n"), "\n\n").trim()
    }

    private fun formatLine(raw: String): String {
        var line = raw.trimEnd()
        if (RULE.matches(line)) return ""
        line = HEADING.replace(line, "")
        line = BLOCKQUOTE.replace(line, "")
        line = TASK.replace(line, "• ")
        line = BULLET.replace(line, "• ")
        return inline(line).trimEnd()
    }

    private fun inline(line: String): String {
        var text = IMAGE.replace(line) { match -> match.groupValues[1] }
        text = LINK.replace(text) { match ->
            match.groupValues[1].ifBlank { match.groupValues[2] }
        }
        text = AUTOLINK.replace(text) { match -> match.groupValues[1] }
        text = BOLD.replace(text) { match -> match.groupValues[2] }
        text = ITALIC.replace(text) { match -> match.groupValues[1] }
        text = CODE.replace(text) { match -> match.groupValues[1] }
        text = TAG.replace(text, "")
        return text
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
    }

    private val HTML_COMMENT = Regex("(?s)<!--.*?-->")
    private val HEADING = Regex("""^\s{0,3}#{1,6}\s+""")
    private val RULE = Regex("""^\s*([-*_])\1{2,}\s*$""")
    private val BLOCKQUOTE = Regex("""^\s{0,3}>\s?""")
    private val TASK = Regex("""^\s*[-*+]\s+\[[ xX]]\s+""")
    private val BULLET = Regex("""^\s*[-*+]\s+""")
    private val IMAGE = Regex("""!\[([^]]*)]\(([^)]*)\)""")
    private val LINK = Regex("""\[([^]]*)]\(([^)]*)\)""")
    private val AUTOLINK = Regex("""<(https?://[^>\s]+)>""")
    private val BOLD = Regex("""(\*\*|__)(.+?)\1""")
    private val ITALIC = Regex("""\*([^*\n]+)\*""")
    private val CODE = Regex("""`([^`]+)`""")
    private val TAG = Regex("""</?[A-Za-z][^>]*>""")
    private val BLANK_RUNS = Regex("""\n{3,}""")
}
