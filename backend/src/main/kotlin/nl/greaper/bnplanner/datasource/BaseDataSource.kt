package nl.greaper.bnplanner.datasource

import com.mongodb.client.MongoCollection
import org.litote.kmongo.EMPTY_BSON
import org.litote.kmongo.deleteOneById
import org.litote.kmongo.findOne

abstract class BaseDataSource<T : Any> {
    protected val collection by lazy { initCollection() }

    abstract fun initCollection(): MongoCollection<T>

    fun list(): List<T> = collection.find().toList()

    fun findFirst() = collection.findOne()

    fun deleteById(id: String) = collection.deleteOneById(id)

    fun deleteMany() = collection.deleteMany(EMPTY_BSON)

    fun insertOne(value: T) = collection.insertOne(value)
}
