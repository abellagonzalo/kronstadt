package kronstadt.core.domain

/** Where a name's declaration is, as the code intelligence finds it. */
public sealed interface Definition {
    /** In a source file on disk, normally a project file; [location] covers the declaration's name. */
    public data class InSource(val location: SourceLocation) : Definition

    /**
     * In a library or the JDK, which agents can read but never edit. [origin] names where it lives as shown to the
     * agent: the jar's file name, or `JDK <module>`. [entry] is the file inside it, e.g. `java/io/File.class` or
     * `commonMain/kotlin/collections/Collections.kt` (for the JDK, without the module), and [range] covers the
     * declaration's name in that file. [source] is set when the entry is source text (from a sources jar or the JDK's
     * `src.zip`), null for a class file.
     */
    public data class InLibrary(
        val origin: String,
        val entry: String,
        val range: TextRange,
        val source: LibrarySource?,
    ) : Definition
}

/** A library source file's text and its Symbols, as `documentSymbols` would give them for a project file. */
public data class LibrarySource(val document: TextDocument, val symbols: List<CodeSymbol>)
