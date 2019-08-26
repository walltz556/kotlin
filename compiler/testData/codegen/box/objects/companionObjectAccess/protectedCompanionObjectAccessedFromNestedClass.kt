// !LANGUAGE: +ProperVisibilityForCompanionObjectInstanceField
// IGNORE_BACKEND: WASM

class Outer {
    protected companion object {
        val result = "OK"
    }

    class Nested {
        fun foo() = result
    }

    fun test() = Nested().foo()
}

fun box() = Outer().test()