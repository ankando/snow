package plugin.core

import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

object TokensManager {
    private const val TOKEN_LENGTH = 16
    private const val EXPIRE_MS = 5 * 60 * 1000L
    private val CHAR_POOL = "0123456789abcdef".toCharArray()
    private val random = SecureRandom()
    private val validTokens = ConcurrentHashMap<String, TokenInfo>()

    fun create(uuid: String): String {
        cleanupExpired()
        validTokens.entries.find { it.value.uuid == uuid && !it.value.isExpired() }?.let {
            return it.key
        }

        val token = generateToken()
        validTokens[token] = TokenInfo(System.currentTimeMillis() + EXPIRE_MS, uuid)
        return token
    }

    fun getTokenOwner(token: String?): String? {
        if (token == null) return null
        val info = validTokens[token] ?: return null
        return if (!info.isExpired()) info.uuid else null
    }


    fun isAdminToken(token: String?): Boolean {
        val info = validTokens[token] ?: return false
        return !info.isExpired() && PermissionManager.isCoreAdmin(info.uuid)
    }

    private fun cleanupExpired() {
        val now = System.currentTimeMillis()
        validTokens.entries.removeIf { it.value.expiry <= now }
    }

    private fun generateToken(): String {
        val chars = CharArray(TOKEN_LENGTH) {
            CHAR_POOL[random.nextInt(CHAR_POOL.size)]
        }
        return String(chars)
    }

    data class TokenInfo(val expiry: Long, val uuid: String) {
        fun isExpired(): Boolean = System.currentTimeMillis() > expiry
    }
}
