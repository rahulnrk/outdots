package app.mini.hub.android.intelligence

/**
 * Removes markdown and wrapper artefacts the local model sometimes adds, so the recipient gets
 * clean text. Only applied to AI output; text the user types is never changed.
 */
object PlainTextDraft {
    private val BOLD = Regex("\\*\\*(.+?)\\*\\*|__(.+?)__")
    private val ITAL = Regex("(?<![\\w*])\\*(?!\\s)([^*\\n]+?)(?<!\\s)\\*(?![\\w*])")
    private val HEAD = Regex("(?m)^\\s{0,3}#{1,6}\\s+")
    private val BULLET = Regex("(?m)^\\s*[*\\-\u2022]\\s+")
    private val CODE = Regex("`{1,3}")
    private val LABEL = Regex("^(?i)(reply|draft|response|message)\\s*:\\s*")

    fun clean(raw: String): String {
        var t = raw.trim()
        t = LABEL.replace(t, "")
        if (t.length >= 2 && (t.first() == '"' && t.last() == '"' || t.first() == '\u201c' && t.last() == '\u201d')) {
            t = t.substring(1, t.length - 1).trim()
        }
        t = BOLD.replace(t) { it.groupValues[1].ifEmpty { it.groupValues[2] } }
        t = ITAL.replace(t) { it.groupValues[1] }
        t = HEAD.replace(t, "")
        t = BULLET.replace(t, "- ")
        t = CODE.replace(t, "")
        return t.replace(Regex("\n{3,}"), "\n\n").trim()
    }
}
