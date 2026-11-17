package kronstadt.evaluation

import java.nio.file.Path

/** Port: whether a commit broke compilation of the Kotlin it touched. */
internal fun interface Builds {
    fun breakage(repo: Path, commit: String): Breakage
}

internal sealed interface Breakage {
    /** The commit touches no `.kt` file. */
    data object NotKotlin : Breakage

    /** The commit's affected modules compile. */
    data object Compiles : Breakage

    /** The commit doesn't compile, but neither did its parent: not the commit's breakage. */
    data object ParentBroken : Breakage

    /** The commit doesn't compile while its parent did; [files] (absolute, in the repo) hold the errors. */
    data class Breaks(val files: Set<Path>) : Breakage

    /** It could not be checked, e.g. the repo or commit is gone. */
    data class Unavailable(val reason: String) : Breakage
}
