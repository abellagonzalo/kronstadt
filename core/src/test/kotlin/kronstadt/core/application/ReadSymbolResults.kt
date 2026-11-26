package kronstadt.core.application

/** Where a read Symbol is: its canonical Symbol Path, file and 1-based line span, e.g. `greet in src/A.kt, L3-4`. */
internal val ReadSymbolResult.Found.location: String
    get() = "$symbol in $file, L${span.firstLine + 1}-${span.lastLine + 1}"
