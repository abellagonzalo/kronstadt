package kronstadt.server

import kronstadt.core.application.LookupFailure
import kronstadt.core.domain.SymbolCandidate

/**
 * What every tool tells the agent when a Symbol Path or file names no one Symbol: one wording for all of them. [tool]
 * names the tool, e.g. `kt_read_symbol`, where the message says what it supports or what to do instead. Messages with
 * a list end with its last line; the others end without a period only after a quoted reason. A tool that takes two
 * files names the [argument] a file check's failure came from, as its prefix.
 */
internal fun describe(failure: LookupFailure, tool: String, argument: String? = null): String =
    argument?.let { "$it: " }.orEmpty() + message(failure, tool)

private fun message(failure: LookupFailure, tool: String): String = when (failure) {
    is LookupFailure.InvalidPath -> "Invalid Symbol Path '${failure.symbol}': ${failure.reason}"
    // Resolved project-wide, kt_find_usages's path is what tells candidates apart; its file only filters Usages.
    is LookupFailure.Ambiguous -> if (tool == FIND_USAGES && failure.file == null) {
        "'${failure.symbol}' matches ${failure.candidates.size} symbols; tell them apart with a package prefix or a " +
            "signature, e.g. 'com.example/Greeter/greet(String)':\n" + candidates(failure.candidates, withFiles = true)
    } else {
        "'${failure.symbol}' matches ${failure.candidates.size} symbols${failure.file.inFile()}; pass one of" +
            (if (failure.file == null) " these paths with its file" else "") + ":\n" +
            candidates(failure.candidates, withFiles = failure.file == null)
    }
    is LookupFailure.NotFound -> "No symbol '${failure.symbol}'${failure.file.inFile()}." + when {
        failure.suggestions.isNotEmpty() -> " Close names:\n" + candidates(failure.suggestions, withFiles = failure.file == null)
        failure.file != null -> " Use kt_read_structure to list its declarations."
        else -> " Use kt_find_symbol to search by partial name."
    }
    is LookupFailure.TooManyDeclarations ->
        "'${failure.symbol}' can't be resolved without a file: so many declarations are named '${failure.name}', " +
            "dependencies' included, that $INDEX_STOPS and may not list them all; " +
            if (tool == FIND_USAGES) "pass the file that declares it as declaredIn" else "pass its file"
    is LookupFailure.OutOfSync ->
        "Index out of sync for ${failure.symbol} in ${failure.file}: its name is not where kotlin-lsp says; retry"
    is LookupFailure.SharedLine ->
        "${failure.symbol} shares line ${failure.line} of ${failure.file} with other code, so it has no whole-line span; " +
            // Replacing the enclosing Symbol costs more, and it may share a line too (#99).
            if (tool == EDIT) {
                "use a whole-file edit_in instead: file '${failure.file}' with no symbol, and an 'old' of exact text from that file"
            } else {
                "read the enclosing symbol instead"
            }
    is LookupFailure.FileNotFound -> "File not found: ${failure.path}"
    is LookupFailure.NotKotlin -> "Not a Kotlin file: ${failure.path}. $tool only supports .kt and .kts files."
    is LookupFailure.OutsideProject -> "Path is outside the project root: ${failure.path}"
}

/** [message] made to end a sentence, so more can follow: a period, or a line break after a list. */
internal fun sentence(message: String): String = when {
    '\n' in message -> message + "\n"
    message.endsWith('.') -> message
    else -> "$message."
}

/** One candidate per line; resolved project-wide, each with the file to pass along with its path. */
internal fun candidates(candidates: List<SymbolCandidate>, withFiles: Boolean): String =
    candidates.joinToString("\n") { if (withFiles) "  ${it.path} — ${it.file}" else "  ${it.path}" }

private fun String?.inFile() = this?.let { " in $it" } ?: ""

/** Why a search may be incomplete, as the tools tell the agent. */
internal const val INDEX_STOPS = "the project index stops at a maximum number of results"

private const val FIND_USAGES = "kt_find_usages"
private const val EDIT = "kt_edit"
