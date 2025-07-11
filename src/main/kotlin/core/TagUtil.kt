package plugin.core

object TagUtil {
    fun getTags(desc: String): List<String> {
        val regex = Regex("""\[@([a-zA-Z0-9_-]+)(=[^]]+)?]""")
        return regex.findAll(desc.lowercase())
            .map { it.groupValues[1] }
            .distinct()
            .toList()
    }
    /*
        fun getTagValue(desc: String, tag: String): String? {
            val regex = Regex("""\[@${Regex.escape(tag.lowercase())}=([^]]+)]""")
            return regex.find(desc.lowercase())?.groupValues?.getOrNull(1)
        }
     */
}
