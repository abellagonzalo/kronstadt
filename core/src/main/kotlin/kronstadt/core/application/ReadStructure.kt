package kronstadt.core.application

import kronstadt.core.domain.StructureRenderer
import kronstadt.core.port.CodeIntelligence
import kronstadt.core.port.FileStore

/** Use case behind `kt_read_structure`: a skeleton of one Kotlin file instead of its full text. */
public class ReadStructure(
    private val files: FileStore,
    private val intelligence: CodeIntelligence,
) {
    public suspend operator fun invoke(path: String, xml: Boolean = true): ReadStructureResult {
        val file = files.resolve(path) ?: return ReadStructureResult.OutsideProject(path)
        if (!file.fileName.toString().let { it.endsWith(".kt") || it.endsWith(".kts") }) {
            return ReadStructureResult.NotKotlin(path)
        }
        val document = files.read(file) ?: return ReadStructureResult.FileNotFound(path)
        val symbols = intelligence.documentSymbols(file, document)
        return ReadStructureResult.Structure(StructureRenderer.render(files.display(file), document, symbols, xml))
    }
}

public sealed interface ReadStructureResult {
    public data class Structure(val text: String) : ReadStructureResult
    public data class FileNotFound(val path: String) : ReadStructureResult
    public data class NotKotlin(val path: String) : ReadStructureResult
    public data class OutsideProject(val path: String) : ReadStructureResult
}
