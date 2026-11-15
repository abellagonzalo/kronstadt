package fixture

import fixture.beta.Shape

/** Test source using main declarations, for kt_find_usages. */
fun checkShapes(): Boolean {
    val triangle = Shape(3)
    return Calculator("c").parse("2") == 2 && triangle.sides == 3 && Operation.PLUS.apply(1, 2) == 3
}
