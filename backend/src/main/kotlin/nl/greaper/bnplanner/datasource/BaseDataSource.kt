package nl.greaper.bnplanner.datasource

import com.mongodb.client.MongoCollection
import com.mongodb.client.MongoDatabase
import org.bson.Document

/** A collection whose values are turned into documents by hand, so the stored shape is always known */
abstract class BaseDataSource<T : Any>(
    private val database: MongoDatabase,
    private val collectionName: String,
) {
    protected val collection: MongoCollection<Document> by lazy { database.getCollection(collectionName) }

    protected abstract fun toDocument(value: T): Document

    protected abstract fun fromDocument(document: Document): T

    fun list(): List<T> = collection.find().toList().map { fromDocument(it) }

    fun insertOne(value: T) = collection.insertOne(toDocument(value))
}
