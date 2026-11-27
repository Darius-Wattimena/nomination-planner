package nl.greaper.bnplanner.config

import com.mongodb.MongoTimeoutException
import com.mongodb.client.MongoDatabase
import nl.greaper.bnplanner.support.TestMongo
import org.bson.conversions.Bson
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.springframework.boot.health.contributor.Status
import java.util.concurrent.TimeUnit

class MongoHealthTest {
    @Test
    fun `Should be healthy when MongoDB answers`() {
        assertEquals(Status.UP, MongoHealth(TestMongo.database).health().status)
    }

    @Test
    fun `Should be unhealthy when MongoDB can't be reached`() {
        val unreachable = mock<MongoDatabase> { on { runCommand(any<Bson>()) } doThrow MongoTimeoutException("Timed out") }
        val database = mock<MongoDatabase> { on { withTimeout(2, TimeUnit.SECONDS) } doReturn unreachable }

        assertEquals(Status.DOWN, MongoHealth(database).health().status)
    }
}
