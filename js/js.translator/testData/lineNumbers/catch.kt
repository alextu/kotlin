fun box() {
    try {
        println("foo")
    }
    catch (e: RuntimeException) {
        println("bar")
    }
    catch (e: Exception) {
        println("baz")
    }
}

fun bar() {
    try {
        println("foo")
    }
    catch (e: dynamic) {
        println("bar")
    }
}

// LINES(JS):      1 11 3 3 5 5 6 6 8 8 9 9 2 2 * 13    20 15 15    18 18
// LINES(JS_IR): 1 1    3 3 5 * 6 6 * 8 9 9     * 13 13    15 15 17 18 18
