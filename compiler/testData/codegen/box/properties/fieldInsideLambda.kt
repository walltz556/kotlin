// IGNORE_BACKEND: WASM
val my: String = "O"
    get() = { field }() + "K"

fun box() = my
