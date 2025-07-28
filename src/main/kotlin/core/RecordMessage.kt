package plugin.core

object RecordMessage {
    private val messages = mutableListOf<String>()

    fun add(message: String) {
        synchronized(messages) {
            messages.add(message)
        }
    }

    fun getAll(): String {
        return synchronized(messages) {
            messages.joinToString("\n")
        }
    }

    fun clear() {
        synchronized(messages) {
            messages.clear()
        }
    }
}
