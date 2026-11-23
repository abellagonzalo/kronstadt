package kronstadt.server

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kronstadt.core.application.Create
import kronstadt.core.application.Delete
import kronstadt.core.application.EditIn
import kronstadt.core.application.InsertAfter
import kronstadt.core.application.InsertBefore
import kronstadt.core.application.Operation
import kronstadt.core.application.Replace

/**
 * The Operation types `kt_edit` takes, by wire name, in the order its schema and messages list them. The parser and the
 * schema's `type` enum both read this table, so a new Operation type needs one entry here and its branch in `Edit.apply`.
 */
internal val operationTypes: List<OperationType> = listOf(
    OperationType("replace") { Replace(file, requireSymbol(), requireCode(), imports) },
    OperationType("insert_before") { InsertBefore(file, requireSymbol(), requireCode(), imports) },
    OperationType("insert_after") { InsertAfter(file, requireSymbol(), requireCode(), imports) },
    OperationType("delete") { Delete(file, requireSymbol(), imports) },
    OperationType("edit_in") {
        if (symbol == null && file == null) missing("a 'symbol', a 'file', or both")
        val old = string("old")?.takeIf { it.isNotEmpty() } ?: missing("a non-empty 'old'")
        EditIn(file, symbol, old, string("new") ?: missing("'new'"), imports)
    },
    OperationType("create") {
        if (symbol != null) refuse("'create' takes no 'symbol', only the new 'file' and its 'code'.")
        Create(file ?: missing("'file'"), requireCode(), imports)
    },
)

/** One Operation type of `kt_edit`: its wire [name] and how its Operation is built from the fields the agent sent. */
internal class OperationType(val name: String, val build: OperationFields.() -> Operation)

/**
 * Operation [index] of a `kt_edit` request, paired with its type as the agent named it, for the messages of its
 * failures. Throws [Refused] if it is malformed.
 */
internal fun parseOperation(index: Int, element: JsonElement): Pair<String, Operation> {
    val json = element as? JsonObject ?: throw Refused("Operation $index must be an object. Nothing written.")
    val type = json.string("type") ?: throw Refused("Operation $index: missing 'type'. Nothing written.")
    val fields = OperationFields(index, type, json)
    val operationType = operationTypes.find { it.name == type } ?: fields.refuse(
        "unsupported type '$type'; use ${operationTypes.dropLast(1).joinToString(", ") { it.name }} or " +
            "${operationTypes.last().name}, and kt_rename_symbol for renames, on its own.",
    )
    return type to operationType.build(fields)
}

/**
 * The fields of Operation [index] of a `kt_edit` request, of [type], as the agent sent them; [symbol] and [file] are null
 * when blank. A missing or malformed one refuses the request: see [Refused].
 */
internal class OperationFields(private val index: Int, private val type: String, private val json: JsonObject) {
    val symbol: String? = json.string("symbol")?.takeIf { it.isNotBlank() }
    val file: String? = json.string("file")?.takeIf { it.isNotBlank() }
    val imports: List<String> = when (val raw = json["imports"]) {
        null, JsonNull -> emptyList()
        is JsonArray -> raw.map { element -> (element as? JsonPrimitive)?.takeIf { it.isString }?.content ?: notStrings() }
        else -> notStrings()
    }

    fun string(key: String): String? = json.string(key)

    fun requireSymbol(): String = symbol ?: missing("'symbol'")

    fun requireCode(): String = string("code") ?: missing("'code'")

    fun missing(what: String): Nothing = refuse("missing $what, which '$type' needs.")

    fun refuse(why: String): Nothing = throw Refused("Operation $index: $why Nothing written.")

    private fun notStrings(): Nothing = refuse("'imports' must be an array of strings.")
}

/** A malformed `kt_edit` request, refused with [message] before any Operation is applied. */
internal class Refused(override val message: String) : Exception(message)

internal fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
