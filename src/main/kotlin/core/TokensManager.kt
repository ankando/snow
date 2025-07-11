package plugin.core

import java.util.concurrent.ConcurrentHashMap

object TokensManager {
    private const val EXPIREMS = 5 * 60 * 1000L
    val validTokens = ConcurrentHashMap<String, TokenInfo>()

    fun create(uuid: String): String {
        cleanup()
        validTokens.entries.forEach { (token, info) ->
            if (info.uuid == uuid && System.currentTimeMillis() <= info.expiry) {
                return token
            }
        }
        val token = buildString(16) { repeat(16) { append("0123456789abcdef".random()) } }
        validTokens[token] = TokenInfo(System.currentTimeMillis() + EXPIREMS, uuid)
        return token
    }

    fun verifyToken(token: String?, uuid: String): Boolean {
        if (token == null) return false
        cleanup()
        val info = validTokens[token] ?: return false
        if (System.currentTimeMillis() > info.expiry) {
            validTokens.remove(token)
            return false
        }
        return info.uuid == uuid
    }

    fun isAdminToken(token: String?): Boolean {
        cleanup()
        val info = validTokens[token] ?: return false
        if (System.currentTimeMillis() > info.expiry) return false
        return PermissionManager.isCoreAdmin(info.uuid)
    }

    private fun cleanup() {
        val now = System.currentTimeMillis()
        validTokens.entries.removeIf { now > it.value.expiry }
    }

    data class TokenInfo(val expiry: Long, val uuid: String)
}
