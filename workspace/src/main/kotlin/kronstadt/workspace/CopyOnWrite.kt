package kronstadt.workspace

import java.io.IOException
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import java.nio.file.Path

/** Copy-on-write directory operations. Never falls back to a real copy. */
public interface CopyOnWrite {
    /** Clones [source] to [target], which must not exist. Throws [IOException] if no copy-on-write clone is possible. */
    @Throws(IOException::class)
    public fun clone(source: Path, target: Path)

    /** Atomically exchanges the directories [a] and [b], which must both exist. */
    @Throws(IOException::class)
    public fun swap(a: Path, b: Path)
}

/**
 * macOS system calls through the Foreign Function API: `clonefile(2)` (an APFS copy-on-write clone, about 0.01 s for a
 * 1 GB index) and `renamex_np(2)` with `RENAME_SWAP` (atomically exchanges two directories). Elsewhere both are
 * missing, and every call fails with an [IOException].
 */
internal object MacOsCopyOnWrite : CopyOnWrite {
    private const val RENAME_SWAP = 0x2
    // Null where the Foreign Function API or the functions are unavailable; every call then fails with an IOException.
    private val linker: Linker? = runCatching { Linker.nativeLinker() }.getOrNull()
    private val errno = Linker.Option.captureCallState("errno")
    private val errnoOffset = Linker.Option.captureStateLayout()
        .byteOffset(MemoryLayout.PathElement.groupElement("errno"))

    private val clonefile = downcall("clonefile", ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT)
    private val renamexNp = downcall("renamex_np", ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT)
    private val strerror: MethodHandle? = runCatching {
        linker?.defaultLookup()?.find("strerror")?.map {
            linker.downcallHandle(it, FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT))
        }?.orElse(null)
    }.getOrNull()

    override fun clone(source: Path, target: Path): Unit = call(clonefile, "clonefile", source, target, 0)

    override fun swap(a: Path, b: Path): Unit = call(renamexNp, "renamex_np", a, b, RENAME_SWAP)

    private fun downcall(name: String, vararg arguments: ValueLayout): MethodHandle? = runCatching {
        linker?.defaultLookup()?.find(name)?.map {
            linker.downcallHandle(it, FunctionDescriptor.of(ValueLayout.JAVA_INT, *arguments), errno)
        }?.orElse(null)
    }.getOrNull()

    private fun call(function: MethodHandle?, name: String, a: Path, b: Path, flags: Int) {
        if (function == null) throw IOException("$name is not available on this system")
        Arena.ofConfined().use { arena ->
            val state = arena.allocate(Linker.Option.captureStateLayout())
            val result = function.invokeWithArguments(
                state,
                arena.allocateFrom(a.toString()),
                arena.allocateFrom(b.toString()),
                flags,
            ) as Int
            if (result != 0) {
                val code = state.get(ValueLayout.JAVA_INT, errnoOffset)
                throw IOException("$name $a -> $b failed: ${describe(code)}")
            }
        }
    }

    private fun describe(code: Int): String {
        val message = (strerror?.invokeWithArguments(code) as MemorySegment?)
            ?.reinterpret(Long.MAX_VALUE)?.getString(0)
        return if (message == null) "errno $code" else "$message (errno $code)"
    }
}
