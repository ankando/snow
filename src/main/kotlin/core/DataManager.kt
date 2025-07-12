package plugin.core

import arc.struct.ObjectMap
import arc.util.serialization.Json
import mindustry.Vars

data class PlayerData(
    val id: Int,
    var account: String,
    var password: String,
    var uuids: MutableList<String>,
    var lang: String,
    var score: Int,
    var wins: Int,
    var banUntil: Long,
    var isAdmin: Boolean,
    var isLock: Boolean
) {
    companion object {
        fun createDefault(
            id: Int,
            account: String,
            password: String,
            uuid: String,
            lang: String
        ) = PlayerData(
            id, account, password, mutableListOf(uuid), lang,
            0, 0, 0, isAdmin = false, isLock = true
        )
    }
}

data class MapData(
    val name: String,
    var uploaderId: Int
)

data class ConfigJson(
    val webUrl: String = "127.0.0.1",
    val webPort: Int = 8080
)

object DataManager {
    val players = ObjectMap<Int, PlayerData>()
    val maps = ObjectMap<String, MapData>()

    var webUrl: String = "127.0.0.1"
    var webPort: Int = 8080

    private var nextId = 1
    @Volatile
    var needSave = false

    private val json = Json().apply {
        setUsePrototypes(false)
        ignoreUnknownFields = true
    }

    private val playersFile = Vars.saveDirectory.child("players.json")
    private val mapsFile = Vars.saveDirectory.child("maps.json")
    private val configFile = Vars.saveDirectory.child("config.json")

    fun init() {
        loadPlayers()
        loadMaps()
        loadConfig()
        if (players.any()) nextId = (players.keys().maxOrNull() ?: 0) + 1
    }

    private fun loadPlayers() {
        if (!playersFile.exists()) return
        val root = json.fromJson(java.util.LinkedHashMap::class.java, playersFile.readString()) as Map<*, *>
        players.clear()
        for ((k, v) in root) {
            val id = when (k) {
                is Number -> k.toInt()
                is String -> k.toIntOrNull() ?: continue
                else -> continue
            }
            val js = v as? Map<*, *> ?: continue
            val uuids = (js["uuids"] as? Iterable<*>)?.mapNotNull { it as? String }?.toMutableList() ?: mutableListOf()
            val data = PlayerData(
                id = id,
                account = js["account"]?.toString() ?: "",
                password = js["password"]?.toString() ?: "",
                uuids = uuids,
                lang = js["lang"]?.toString() ?: "en",
                score = (js["score"] as? Number)?.toInt() ?: 0,
                wins = (js["wins"] as? Number)?.toInt() ?: 0,
                banUntil = (js["banUntil"] as? Number)?.toLong() ?: 0,
                isAdmin = js["isAdmin"] as? Boolean ?: false,
                isLock = js["isLock"] as? Boolean ?: true
            )
            players.put(id, data)
        }
    }

    private fun loadMaps() {
        if (!mapsFile.exists()) return
        val root = json.fromJson(java.util.LinkedHashMap::class.java, mapsFile.readString()) as Map<*, *>
        maps.clear()
        for ((k, v) in root) {
            val name = k?.toString() ?: continue
            val js = v as? Map<*, *> ?: continue
            val uploaderId = (js["uploaderId"] as? Number)?.toInt() ?: 0
            maps.put(name, MapData(name, uploaderId))
        }
    }

    private fun loadConfig() {
        if (!configFile.exists()) return
        val conf = json.fromJson(ConfigJson::class.java, configFile.readString())
        webUrl = conf.webUrl
        webPort = conf.webPort
    }

    fun registerPlayer(
        account: String,
        password: String,
        uuid: String,
        lang: String
    ): PlayerData {
        val id = nextId++
        val data = PlayerData.createDefault(id, account, password, uuid, lang)
        players.put(id, data)
        requestSave()
        return data
    }

    fun getIdByUuid(uuid: String): Int? {
        for (id in players.keys()) {
            val data = players[id] ?: continue
            if (data.uuids.contains(uuid)) return id
        }
        return null
    }

    fun getPlayerDataByUuid(uuid: String): PlayerData? {
        for (data in players.values()) {
            if (data.uuids.contains(uuid)) return data
        }
        return null
    }

    fun updatePlayer(id: Int, block: (PlayerData) -> Unit) {
        val data = players[id] ?: return
        block(data)
        requestSave()
    }

    fun updateMapInfo(name: String, block: (MapData) -> Unit) {
        val data = maps[name] ?: return
        block(data)
        requestSave()
    }

    fun requestSave() {
        needSave = true
    }

    fun saveAll() {
        val playersOut = java.util.LinkedHashMap<Int, Any>()
        players.forEach {
            playersOut[it.key] = mapOf(
                "id" to it.value.id,
                "account" to it.value.account,
                "password" to it.value.password,
                "uuids" to it.value.uuids,
                "lang" to it.value.lang,
                "score" to it.value.score,
                "wins" to it.value.wins,
                "banUntil" to it.value.banUntil,
                "isAdmin" to it.value.isAdmin,
                "isLock" to it.value.isLock
            )
        }
        playersFile.writeString(json.toJson(playersOut))

        val mapsOut = java.util.LinkedHashMap<String, Any>()
        maps.forEach {
            mapsOut[it.key] = mapOf(
                "name" to it.value.name,
                "uploaderId" to it.value.uploaderId
            )
        }
        mapsFile.writeString(json.toJson(mapsOut))

        configFile.writeString(json.toJson(ConfigJson(webUrl, webPort)))
        needSave = false
    }
}