// TARGET_BACKEND: JVM
// WITH_RUNTIME

// IGNORE_BACKEND_FIR: JVM_IR
//  - FIR2IR should generate call to fake override

// FILE: A.kt
package a
import b.*

class A {
    fun foo() = ok

    companion object : B()
}

fun box(): String {
    return A().foo()
}

// FILE: B.kt
package b

open class B {
    @JvmField protected val ok = "OK"
}
