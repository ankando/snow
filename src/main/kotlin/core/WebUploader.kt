package plugin.core

import arc.files.Fi
import arc.struct.ObjectMap
import arc.util.serialization.Json
import fi.iki.elonen.NanoHTTPD
import mindustry.Vars

object WebUploader {
    private var server: MapUploadServer? = null

    fun init() {
        if (server == null) server = MapUploadServer(DataManager.webPort)
    }

    private class MapUploadServer(port: Int) : NanoHTTPD(port) {

        private val mapDir: Fi = Vars.customMapDirectory
        private val recordFile: Fi = Vars.saveDirectory.child("maprecords.json")
        private val uploads = ObjectMap<String, String>()
        private val json = Json()

        init {
            if (!mapDir.exists()) mapDir.mkdirs()
            if (recordFile.exists()) {
                val txt = recordFile.readString()
                if (txt.isNotBlank()) {
                    val raw = json.fromJson(ObjectMap::class.java, txt) as ObjectMap<*, *>
                    raw.entries().forEach { e ->
                        val k = e.key as? String ?: return@forEach
                        val v = e.value as? String ?: return@forEach
                        uploads.put(k, v)
                    }
                }
            }
            start(SOCKET_READ_TIMEOUT, true)
        }

        override fun serve(session: IHTTPSession): Response {
            val token = session.parameters["token"]?.firstOrNull()
            if (token.isNullOrBlank()) return forbidden()

            val uuid = verifyToken(token) ?: return forbidden()

            val isAdmin = try {
                TokensManager.isAdminToken(token)
            } catch (_: Exception) {
                false
            }

            return when (session.method) {
                Method.GET -> handleGet(session, token)
                Method.POST -> handlePost(session, uuid, isAdmin)
                else -> newFixedLengthResponse("Unsupported")
            }
        }
        private fun handleGet(s: IHTTPSession, token: String?): Response {
            val file = s.parameters["file"]?.firstOrNull()
            return when (s.uri) {
                "/download" -> sendFile(file)
                else -> page(token)
            }
        }


        private fun handlePost(s: IHTTPSession, uuid: String, isAdmin: Boolean): Response {
            val files = HashMap<String?, String?>()
            s.parseBody(files)

            val name = s.parameters["file"]?.firstOrNull() ?: return bad("no file")
            val safe = Fi(name).name()

            if (!safe.endsWith(".msav") || !safe.matches("^[A-Za-z0-9_-]{1,30}\\.msav$".toRegex()))
                return bad("bad name")

            val upload = Fi(files["file"])
            if (upload.length() > 200 * 1024) return bad("too big")

            val dst = mapDir.child(safe)
            val uploaderId = DataManager.getIdByUuid(uuid) ?: -1
            val mapData = DataManager.maps.get(safe)

            if (dst.exists() && mapData != null && mapData.uploaderId != uploaderId && !isAdmin) {
                return bad("denied")
            }

            upload.copyTo(dst)

            if (mapData == null) {
                DataManager.registerMap(safe, uploaderId)
            }

            Vars.maps.reload()
            return html("uploaded")
        }


        private fun sendFile(name: String?): Response {
            if (name.isNullOrBlank() || !name.endsWith(".msav")) return bad("no file")
            val f = mapDir.child(name)
            if (!f.exists()) return notFound()
            return newChunkedResponse(Response.Status.OK, "application/octet-stream", f.read()).apply {
                addHeader("Content-Disposition", """attachment; filename="${f.name()}" """)
            }
        }


        private fun page(token: String?): Response {
            val tok = "&token=${token ?: ""}"
            val sb = StringBuilder()

            sb.append(
                """
<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport"
        content="width=device-width,initial-scale=1,viewport-fit=cover">
  <title>Maps</title>
  <style>
    :root{
      --bg:#ffffff;
      --text:#1b1b1b;
      --accent:#52779b;
      --border:#e2e2e2;
    }
    @media (prefers-color-scheme:dark){
      :root{
        --bg:#1b1b1b;
        --text:#d0d0d0;
        --accent:#75adff;
        --border:#333333;
      }
    }
    *{box-sizing:border-box;margin:0;padding:0;}
    body{
      font-family:system-ui,sans-serif;
      background:var(--bg);
      color:var(--text);
      line-height:1.6;
      max-width:720px;
      margin:auto;
      padding:1rem;
      -webkit-font-smoothing:antialiased;
    }
    h1{font-size:1.5rem;margin:1.2rem 0;}
    form{margin-bottom:1rem;}
    input[type=file]{margin-right:.5rem}
    ul{list-style:none;padding-left:0}
    li{border-bottom:1px solid var(--border);padding:.6rem 0}
    a{color:var(--accent);text-decoration:none}
  </style>
</head>
<body>
  <h1>Upload Map</h1>
  <form method="POST" enctype="multipart/form-data">
    <input type="file" name="file" accept=".msav" required>
    <input type="submit" value="Upload">
  </form>
  <ul>
""".trimIndent()
            )

            Vars.maps.customMaps().forEach { m ->
                val fn = m.file.name()
                sb.append("<li><a href='/download?file=$fn$tok'>$fn</a> — ")
                    .append(m.name())
                sb.append("</li>")
            }

            sb.append(
                """
  </ul>
</body>
</html>
""".trimIndent()
            )

            return html(sb.toString())
        }

        private fun html(s: String) = newFixedLengthResponse(s)
        private fun bad(s: String) = newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, s)
        private fun forbidden() = newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "forbidden")
        private fun notFound() = newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "not found")
    }

    private fun verifyToken(token: String?): String? {
        return TokensManager.getTokenOwner(token)
    }

}
