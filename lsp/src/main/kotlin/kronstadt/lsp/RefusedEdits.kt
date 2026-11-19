package kronstadt.lsp

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.eclipse.lsp4j.ApplyWorkspaceEditParams
import org.eclipse.lsp4j.ApplyWorkspaceEditResponse
import org.eclipse.lsp4j.WorkspaceEdit
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The client's answer to `workspace/applyEdit`: every edit the language server asks to apply is refused, because the
 * Shadow Workspace and Write-back make every change (ADR 0003). One asked for [during] a request is handed to its
 * caller: kotlin-lsp 271.2085.0 answers its organize-imports command that way.
 */
internal class RefusedEdits {
    private val collecting = Mutex()

    @Volatile
    private var sink: MutableList<WorkspaceEdit>? = null

    /** Refuses [params], keeping its edit for the [during] block that is running, if any. */
    fun refuse(params: ApplyWorkspaceEditParams): ApplyWorkspaceEditResponse {
        sink?.add(params.edit)
        return ApplyWorkspaceEditResponse(false).apply { failureReason = "Kronstadt applies no edit a language server asks for" }
    }

    /** Runs [block], one at a time, and returns its result with the edits refused while it ran. */
    suspend fun <T> during(block: suspend () -> T): Pair<T, List<WorkspaceEdit>> = collecting.withLock {
        val edits = CopyOnWriteArrayList<WorkspaceEdit>()
        sink = edits
        try {
            block() to edits.toList()
        } finally {
            sink = null
        }
    }
}
