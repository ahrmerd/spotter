package dev.ahmed.spotter.engine

fun interface EngineLog {
    fun log(level: String, message: String, error: Throwable?)

    fun info(message: String) = log("I", message, null)
    fun warn(message: String, error: Throwable? = null) = log("W", message, error)
    fun error(message: String, error: Throwable? = null) = log("E", message, error)

    companion object {
        val NONE = EngineLog { _, _, _ -> }
        val STDERR = EngineLog { level, message, error ->
            System.err.println("Spotter/$level $message")
            error?.printStackTrace(System.err)
        }
    }
}
