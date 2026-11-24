package fixture

import fixture.beta.Shape

/** Test source using main declarations, for kt_find_usages. */
fun checkShapes(): Boolean {
    val circle = Shape(1.0)
    return Calculator("c").parse("2") == 2 && circle.radius == 1.0 && Operation.PLUS.apply(1, 2) == 3
}
