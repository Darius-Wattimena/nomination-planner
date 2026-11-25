package nl.greaper.bnplanner.support

import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import com.mongodb.client.MongoDatabase
import de.flapdoodle.commons.reverse.transitions.Start
import de.flapdoodle.embed.mongo.distribution.Version
import de.flapdoodle.embed.mongo.transitions.Mongod
import de.flapdoodle.embed.mongo.transitions.RunningMongodProcess
import de.flapdoodle.embed.process.io.ProcessOutput
import nl.greaper.bnplanner.config.DatabaseConfig
import nl.greaper.bnplanner.config.MongoProperties
import org.bson.Document

/** A real MongoDB on a random port, started once for all tests. Never connects to a MongoDB running on this machine. */
object TestMongo {
    const val DATABASE = "bnplanner-test"

    private val process: RunningMongodProcess by lazy {
        val silent = Start.to(ProcessOutput::class.java).initializedWith(ProcessOutput.silent())
        val running =
            Mongod
                .instance()
                .withProcessOutput(silent)
                .start(Version.Main.V8_0)
                .current()
        Runtime.getRuntime().addShutdownHook(Thread { running.stop() })
        running
    }

    val host: String get() = process.serverAddress.host
    val port: Int get() = process.serverAddress.port

    val properties: MongoProperties
        get() = MongoProperties(db = DATABASE, username = "", password = "", authDb = "admin", host = host, port = port)

    /** The client as configured for production */
    val client: MongoClient by lazy { DatabaseConfig().mongoClient(properties) }

    /** The database as seen by the application */
    val database: MongoDatabase by lazy { DatabaseConfig().mongoDatabase(properties, client) }

    /** The stored documents, without any mapping of the application */
    val raw: MongoDatabase by lazy { MongoClients.create("mongodb://$host:$port").getDatabase(DATABASE) }

    fun clear() = raw.listCollectionNames().toList().forEach { raw.getCollection(it).deleteMany(Document()) }

    fun insert(
        collection: String,
        json: String,
    ) = raw.getCollection(collection).insertOne(Document.parse(json))

    fun documents(collection: String): List<Document> = raw.getCollection(collection).find().toList()
}
