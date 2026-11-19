package fixture

/** Read-only and mutable declarations: constructor properties, a plain parameter, body properties and locals. */
class Mutability(val ctorVal: Int, var ctorVar: Int, plain: Int) {
    val bodyVal: Int = plain

    var bodyVar: Int = plain

    fun withLocals(): Int {
        val localVal = 1
        var localVar = 2
        localVar += localVal
        return localVar
    }
}

val topLevelVal: Int = 1

var topLevelVar: Int = 2
