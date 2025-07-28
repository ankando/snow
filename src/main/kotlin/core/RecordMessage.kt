package plugin.core

object RecordMessage {
    private val messages = mutableListOf<String>()
    private val disabledUuids = mutableSetOf<String>()

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

    fun isDisabled(uuid: String): Boolean {
        return synchronized(disabledUuids) {
            uuid in disabledUuids
        }
    }

    fun setDisabled(uuid: String, disabled: Boolean) {
        synchronized(disabledUuids) {
            if (disabled) disabledUuids.add(uuid) else disabledUuids.remove(uuid)
        }
    }

    fun getDisabledUuids(): List<String> {
        return synchronized(disabledUuids) {
            disabledUuids.toList()
        }
    }
}
