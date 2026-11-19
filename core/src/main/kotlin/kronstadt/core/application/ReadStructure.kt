package kronstadt.core.application

import kronstadt.core.domain.StructureRenderer
import kronstadt.core.port.CodeIntelligence
import kronstadt.core.port.FileStore

/**
 * Use case behind `kt_read_structure`: a skeleton of one Kotlin file instead of its full text.
 *
 * Syntax-only: answers from the file's `documentSymbol` tree without waiting for the project index.
 */
public class ReadStructure(
    private val files: FileStore,
    private val intelligence: CodeIntelligence,
) {
    public suspend operator fun invoke(path: String, xml: Boolean = true): ReadStructureResult {
        val file = files.resolve(path) ?: return ReadStructureResult.OutsideProject(path)
        if (!AnalysedFile.isKotlin(file)) return ReadStructureResult.NotKotlin(path)
        val document = files.read(file) ?: return ReadStructureResult.FileNotFound(path)
        val symbols = intelligence.session { it.documentSymbols(file, document) }
        val shown = files.display(file)
        val text = StructureRenderer.render(shown, document, symbols, xml)
        return if (text.isEmpty()) ReadStructureResult.NoDeclarations(shown) else ReadStructureResult.Structure(text)
    }
}

public sealed interface ReadStructureResult {
    public data class Structure(val text: String) : ReadStructureResult

    /** The file declares no Symbol, e.g. a `build.gradle.kts`. */
    public data class NoDeclarations(val path: String) : ReadStructureResult
    public data class FileNotFound(val path: String) : ReadStructureResult
    public data class NotKotlin(val path: String) : ReadStructureResult
    public data class OutsideProject(val path: String) : ReadStructureResult
}
