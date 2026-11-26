package nl.greaper.bnplanner.support

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
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
        val headers: Map<String, String> = emptyMap(),
    )

    private val responses = ConcurrentHashMap<String, Response>()

    // Answered once each, before the usual answer
    private val firstResponses = ConcurrentHashMap<String, ConcurrentLinkedQueue<Response>>()
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
        firstResponses.clear()
        requests.clear()
    }

    fun respond(
        request: String,
        body: String,
        status: Int = 200,
    ) {
        responses[request] = Response(status, body)
    }

    /** Answers "too many requests" the given number of times first, with the Retry-After header when given */
    fun refuse(
        request: String,
        times: Int,
        retryAfter: String? = null,
    ) {
        val response = Response(429, """{"error":"too many requests"}""", retryAfter?.let { mapOf("Retry-After" to it) }.orEmpty())
        firstResponses.getOrPut(request) { ConcurrentLinkedQueue() }.addAll(List(times) { response })
    }

    /** Responds like this only while performing the block, keeping what a test set up itself. "unavailable" answers 503. */
    fun <T> temporarily(
        vararg answers: Pair<String, String>,
        block: () -> T,
    ): T {
        val before = answers.associate { (request, _) -> request to responses[request] }
        answers.forEach { (request, body) -> if (body == "unavailable") respond(request, "{}", 503) else respond(request, body) }
        try {
            return block()
        } finally {
            before.forEach { (request, response) -> if (response == null) responses.remove(request) else responses[request] = response }
        }
    }

    fun calls(): List<String> = requests.map { "${it.method} ${it.uri}" }

    private fun handle(exchange: HttpExchange) {
        exchange.use {
            val headers = it.requestHeaders.entries.associate { (name, values) -> name.lowercase() to values.toList() }
            val request = Request(it.requestMethod, it.requestURI.toString(), headers, it.requestBody.readBytes().decodeToString())
            requests += request

            val key = "${request.method} ${request.uri}"
            val response = firstResponses[key]?.poll() ?: responses[key] ?: Response(404, """{"error":null}""")
            val bytes = response.body.toByteArray()
            it.responseHeaders.add("Content-Type", "application/json")
            response.headers.forEach { (name, value) -> it.responseHeaders.add(name, value) }
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

    fun tokenBody(
        accessToken: String,
        refreshToken: String = "refresh",
        expiresIn: Int = 86400,
    ) = """{"token_type":"Bearer","expires_in":$expiresIn,"access_token":"$accessToken","refresh_token":"$refreshToken"}"""

    fun token(
        accessToken: String,
        refreshToken: String = "refresh",
        expiresIn: Int = 86400,
    ) = respond("POST /oauth/token", tokenBody(accessToken, refreshToken, expiresIn))

    fun beatmap(
        id: String,
        artist: String = "Artist $id",
        title: String = "Title $id",
        creator: String = "Mapper",
        userId: String = "10",
        ranked: Int = 0,
        rankedDate: String? = null,
        nominations: String = "[]",
        summary: String = "null",
    ) = respond(
        "GET /api/v2/beatmapsets/$id",
        """
        {"id":$id,"artist":"$artist","title":"$title","creator":"$creator","user_id":$userId,"ranked":$ranked,
        "ranked_date":${rankedDate?.let { "\"$it\"" }},"last_updated":"2024-05-06T07:08:09Z","play_count":5,
        "current_nominations":$nominations,"nominations_summary":$summary,
        "beatmaps":[{"total_length":100,"user_id":$userId,"mode":"osu","version":"Hard","bpm":180.5}]}
        """.trimIndent(),
    )

    /** A recorded answer of osu!, from src/test/resources/osu */
    fun sample(name: String): String = FakeOsu::class.java.getResource("/osu/$name")!!.readText()

    const val EVENT_TYPES =
        "types%5B%5D=nominate&types%5B%5D=qualify&types%5B%5D=rank&types%5B%5D=nomination_reset" +
            "&types%5B%5D=nomination_reset_received&types%5B%5D=disqualify"

    fun beatmapsetEvents(
        page: Int,
        body: String,
        status: Int = 200,
    ) = respond("GET /api/v2/beatmapsets/events?$EVENT_TYPES&page=$page", body, status)

    fun groupHistory(
        groupId: String,
        body: String,
        cursor: String? = null,
        status: Int = 200,
    ) = respond("GET /groups/history?group_id=$groupId${cursor?.let { "&cursor_string=$it" }.orEmpty()}", body, status)
}
