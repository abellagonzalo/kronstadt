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
    public suspend operator fun invoke(path: String): ReadStructureResult {
        val file = files.classify(path).getOrElse { return ReadStructureResult.LookupFailed(it) }
        val document = files.read(file) ?: return ReadStructureResult.LookupFailed(LookupFailure.FileNotFound(path))
        val analysed = intelligence.session { it.analyse(files, file, document) }
        val text = StructureRenderer.render(analysed)
        return if (text.isEmpty()) ReadStructureResult.NoDeclarations(analysed.shown) else ReadStructureResult.Structure(text)
    }
}

public sealed interface ReadStructureResult {
    public data class Structure(val text: String) : ReadStructureResult

    /** The file declares no Symbol, e.g. a `build.gradle.kts`. */
    public data class NoDeclarations(val path: String) : ReadStructureResult

    /** The file is outside the project, not Kotlin, or doesn't exist. */
    public data class LookupFailed(val failure: LookupFailure) : ReadStructureResult
}
