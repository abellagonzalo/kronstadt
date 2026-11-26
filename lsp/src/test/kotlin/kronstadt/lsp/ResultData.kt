package kronstadt.lsp

import kronstadt.core.application.ReadStructureResult
import kronstadt.core.application.ReadSymbolResult
import kronstadt.core.application.UsageLine
import kronstadt.core.application.UsagesInFile
import kronstadt.core.application.UsagesInSymbol

/** Where a read Symbol is: its canonical Symbol Path, file and 1-based line span, e.g. `greet in src/A.kt, L3-4`. */
internal val ReadSymbolResult.Found.location: String
    get() = "$symbol in $file, L${span.firstLine + 1}-${span.lastLine + 1}"

/** Each listing of a structure as a row: indented by depth, then its kind, Symbol Path, Signature if any, and line span. */
internal val ReadStructureResult.Structure.rows: List<String>
    get() = file.listings.map { listing ->
        "  ".repeat(listing.depth) + "${listing.kind} ${listing.path}" + (listing.signature?.let { " $it" } ?: "") +
            " L${listing.firstLine}-${listing.lastLine}"
    }

internal fun inFile(file: String, vararg inSymbols: UsagesInSymbol) = UsagesInFile(file, inSymbols.toList())

internal fun inSymbol(symbol: String?, vararg lines: Pair<Int, String>) = UsagesInSymbol(symbol, lines.map { (line, source) -> UsageLine(line, source) })
