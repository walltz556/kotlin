// IGNORE_BACKEND: WASM
// !DIAGNOSTICS: -UNUSED_PARAMETER

// DONT_RUN_GENERATED_CODE: JS

tailrec fun Int.foo(x: Int) {
    if (x == 0) return
    return 1.foo(x - 1)
}

fun box(): String {
    1.foo(1000000)
    return "OK"
}