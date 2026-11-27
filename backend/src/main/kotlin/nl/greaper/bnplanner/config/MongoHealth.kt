package nl.greaper.bnplanner.config

import com.mongodb.MongoException
import com.mongodb.client.MongoDatabase
import org.bson.Document
import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.HealthIndicator
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit

/** Nothing works without MongoDB, so the planner is only healthy when it can be reached */
@Component("mongo")
class MongoHealth(
    private val database: MongoDatabase,
) : HealthIndicator {
    override fun health(): Health =
        try {
            // Answers before the health check of the load balancer gives up
            database.withTimeout(2, TimeUnit.SECONDS).runCommand(Document("ping", 1))
            Health.up().build()
        } catch (ex: MongoException) {
            Health.down(ex).build()
        }
}
