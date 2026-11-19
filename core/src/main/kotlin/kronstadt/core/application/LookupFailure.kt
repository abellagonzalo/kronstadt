package kronstadt.core.application

/**
 * Why a Symbol Path, or the file it is looked up in, names no one Symbol: the one failure vocabulary of every tool that
 * takes a Symbol Path or a file. Paths and files are as the agent passed or should pass them.
 */
public sealed interface LookupFailure {
    public data class InvalidPath(val symbol: String, val reason: String) : LookupFailure

    /** [file] is null when the path was resolved project-wide; each candidate names its own file. */
    public data class Ambiguous(val symbol: String, val file: String?, val candidates: List<SymbolCandidate>) : LookupFailure

    /** [file] is null when the path was resolved project-wide; each suggestion names its own file. */
    public data class NotFound(val symbol: String, val file: String?, val suggestions: List<SymbolCandidate>) : LookupFailure

    /** [symbol], passed without a file: so many declarations are named [name] that the index may not have listed them all. */
    public data class TooManyDeclarations(val symbol: String, val name: String) : LookupFailure

    /** The text at the Symbol's name range isn't its name, so its range can't be trusted. */
    public data class OutOfSync(val symbol: String, val file: String) : LookupFailure

    /** Something other than whitespace shares 1-based [line] with the Symbol, so it has no whole-line span. */
    public data class SharedLine(val symbol: String, val file: String, val line: Int) : LookupFailure
    public data class FileNotFound(val path: String) : LookupFailure
    public data class NotKotlin(val path: String) : LookupFailure
    public data class OutsideProject(val path: String) : LookupFailure
}

/** One step of a lookup: its [Ok] value, or why it [Failed]. */
internal sealed interface Outcome<out T> {
    data class Ok<out T>(val value: T) : Outcome<T>
    data class Failed(val failure: LookupFailure) : Outcome<Nothing>
}

/** The value, or what [onFailure] makes of the failure (typically a `return` of the tool's own result). */
internal inline fun <T> Outcome<T>.getOrElse(onFailure: (LookupFailure) -> T): T = when (this) {
    is Outcome.Ok -> value
    is Outcome.Failed -> onFailure(failure)
}
