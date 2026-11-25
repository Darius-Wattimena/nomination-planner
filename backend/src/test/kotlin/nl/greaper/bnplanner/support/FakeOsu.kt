package nl.greaper.bnplanner.support

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/** A local server pretending to be osu!, which records every request it gets */
object FakeOsu {
    data class Request(
        val method: String,
        val uri: String,
        val headers: Map<String, List<String>>,
        val body: String,
    ) {
        fun header(name: String): String? = headers[name.lowercase()]?.joinToString(",")
    }

    private data class Response(
        val status: Int,
        val body: String,
    )

    private val responses = ConcurrentHashMap<String, Response>()
    val requests = CopyOnWriteArrayList<Request>()

    private val server: HttpServer by lazy {
        HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            executor = Executors.newCachedThreadPool { Thread(it).apply { isDaemon = true } }
            createContext("/") { handle(it) }
            start()
        }
    }

    val url: String get() = "http://127.0.0.1:${server.address.port}"

    fun reset() {
        responses.clear()
        requests.clear()
    }

    fun respond(
        request: String,
        body: String,
        status: Int = 200,
    ) {
        responses[request] = Response(status, body)
    }

    fun calls(): List<String> = requests.map { "${it.method} ${it.uri}" }

    private fun handle(exchange: HttpExchange) {
        exchange.use {
            val headers = it.requestHeaders.entries.associate { (name, values) -> name.lowercase() to values.toList() }
            val request = Request(it.requestMethod, it.requestURI.toString(), headers, it.requestBody.readBytes().decodeToString())
            requests += request

            val response = responses["${request.method} ${request.uri}"] ?: Response(404, """{"error":null}""")
            val bytes = response.body.toByteArray()
            it.responseHeaders.add("Content-Type", "application/json")
            it.sendResponseHeaders(response.status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) it.responseBody.write(bytes)
        }
    }

    private fun HttpExchange.use(block: (HttpExchange) -> Unit) =
        try {
            block(this)
        } finally {
            close()
        }

    fun user(
        id: String,
        username: String,
        groups: String = "[]",
    ) = respond("GET /api/v2/users/$id?key=id", """{"id":$id,"username":"$username","groups":$groups,"is_bot":false}""")

    fun token(
        accessToken: String,
        refreshToken: String = "refresh",
        expiresIn: Int = 86400,
    ) = respond(
        "POST /oauth/token",
        """{"token_type":"Bearer","expires_in":$expiresIn,"access_token":"$accessToken","refresh_token":"$refreshToken"}""",
    )

    fun beatmap(
        id: String,
        artist: String = "Artist $id",
        title: String = "Title $id",
        creator: String = "Mapper",
        userId: String = "10",
        ranked: Int = 0,
        rankedDate: String? = null,
        nominations: String = "[]",
    ) = respond(
        "GET /api/v2/beatmapsets/$id",
        """
        {"id":$id,"artist":"$artist","title":"$title","creator":"$creator","user_id":$userId,"ranked":$ranked,
        "ranked_date":${rankedDate?.let { "\"$it\"" }},"last_updated":"2024-05-06T07:08:09Z","play_count":5,
        "current_nominations":$nominations,
        "beatmaps":[{"total_length":100,"user_id":$userId,"mode":"osu","version":"Hard","bpm":180.5}]}
        """.trimIndent(),
    )
}
