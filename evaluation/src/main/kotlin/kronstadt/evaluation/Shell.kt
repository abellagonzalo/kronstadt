package kronstadt.evaluation

import java.nio.file.Path

/**
 * Just enough shell lexing to see what a Bash command does to files: simple commands split on unquoted `;`, `&&`,
 * `||`, `|`, `&` and newlines, words unquoted, redirections as their own operator words, and heredoc bodies dropped
 * (they are data, not commands).
 */
internal object Shell {
    /** One simple command: its source text (without any heredoc body) and its words. */
    data class Segment(val text: String, val words: List<String>, val redirects: List<Redirect>) {
        /** The program, after any leading `VAR=value` assignments. */
        val program: String? get() = args.firstOrNull()

        /** The words without leading assignments and without redirections. */
        val args: List<String> get() = words.dropWhile { Regex("""^\w+=.*""").matches(it) }

        /** `sed` or `perl` with an in-place flag (`-i`, `-pi`, `-i.bak`, `--in-place`): it rewrites existing files. */
        val editsInPlace: Boolean
            get() = program in setOf("sed", "perl") && args.drop(1).any { Regex("""^-[a-zA-Z]*i""").containsMatchIn(it) || it.startsWith("--in-place") }
    }

    /** A redirection `op target`, e.g. `>` / `>>` / `2>` to a file. */
    data class Redirect(val op: String, val target: String) {
        val writes: Boolean get() = op.endsWith(">") || op.endsWith(">>")
    }

    fun segments(command: String): List<Segment> = Lexer(command).run()

    /** Each segment of [command] with the directory it runs in: [cwd], moved by any earlier `cd <dir>` in the command. */
    fun located(command: String, cwd: Path): List<Pair<Segment, Path>> {
        var dir = cwd
        return segments(command).map { segment ->
            val located = segment to dir
            val target = segment.args.getOrNull(1)
            if (segment.program == "cd" && target != null && target != "-" && !target.startsWith("~")) dir = dir.resolve(target).normalize()
            located
        }
    }

    private class Lexer(private val s: String) {
        private val segments = mutableListOf<Segment>()
        private var words = mutableListOf<String>()
        private var redirects = mutableListOf<Redirect>()
        private var pendingRedirect: String? = null
        private var pendingHeredocs = mutableListOf<Pair<String, Boolean>>()
        private val word = StringBuilder()
        private var inWord = false
        private var segmentStart = 0
        private var i = 0

        fun run(): List<Segment> {
            while (i < s.length) {
                val c = s[i]
                when {
                    c == '\'' -> { inWord = true; val end = s.indexOf('\'', i + 1).let { if (it < 0) s.length else it }; word.append(s, i + 1, end); i = end + 1 }
                    c == '"' -> doubleQuoted()
                    c == '\\' && i + 1 < s.length -> { inWord = true; if (s[i + 1] != '\n') word.append(s[i + 1]); i += 2 }
                    c == '#' && !inWord && (i == 0 || s[i - 1].isWhitespace()) -> { while (i < s.length && s[i] != '\n') i++ }
                    c == '\n' -> { endSegment(i); i++; skipHeredocBodies(); segmentStart = i }
                    c == ' ' || c == '\t' -> { endWord(); i++ }
                    c == ';' || c == '|' || c == '&' && !s.startsWith("&>", i) -> {
                        endSegment(i)
                        i += if (i + 1 < s.length && (s[i + 1] == c || c == '|' && s[i + 1] == '&')) 2 else 1
                        segmentStart = i
                    }
                    c == '<' || c == '>' || c == '&' -> operator()
                    c.isDigit() && !inWord && i + 1 < s.length && s[i + 1] == '>' -> operator()
                    else -> { inWord = true; word.append(c); i++ }
                }
            }
            endSegment(s.length)
            return segments
        }

        private fun doubleQuoted() {
            inWord = true
            i++
            while (i < s.length && s[i] != '"') {
                if (s[i] == '\\' && i + 1 < s.length) { word.append(s[i + 1]); i += 2 } else word.append(s[i++])
            }
            i++
        }

        private fun operator() {
            endWord()
            val op = Regex("""^(\d?>>|\d?>&?|&>>?|<<<|<<-|<<|<&|<>|<)""").find(s.substring(i))!!.value
            i += op.length
            when {
                op == "<<" || op == "<<-" -> {
                    while (i < s.length && s[i].isWhitespace() && s[i] != '\n') i++
                    val start = i
                    while (i < s.length && !s[i].isWhitespace() && s[i] !in ";|&<>") i++
                    pendingHeredocs += s.substring(start, i).trim('\'', '"') to (op == "<<-")
                }
                op.endsWith("&") -> { // fd duplication such as 2>&1: its target is a descriptor, not a file
                    while (i < s.length && (s[i].isDigit() || s[i] == '-')) i++
                }
                else -> pendingRedirect = op
            }
        }

        private fun skipHeredocBodies() {
            for ((delimiter, stripTabs) in pendingHeredocs) {
                while (i < s.length) {
                    val end = s.indexOf('\n', i).let { if (it < 0) s.length else it }
                    val line = s.substring(i, end).let { if (stripTabs) it.trimStart('\t') else it }
                    i = minOf(end + 1, s.length)
                    if (line == delimiter) break
                }
            }
            pendingHeredocs.clear()
        }

        private fun endWord() {
            if (!inWord) return
            val text = word.toString()
            val op = pendingRedirect
            if (op != null) { redirects += Redirect(op, text); pendingRedirect = null } else words += text
            word.clear()
            inWord = false
        }

        private fun endSegment(end: Int) {
            endWord()
            if (words.isNotEmpty() || redirects.isNotEmpty()) {
                segments += Segment(s.substring(segmentStart, end).trim(), words, redirects)
            }
            words = mutableListOf()
            redirects = mutableListOf()
            pendingRedirect = null
        }
    }
}
