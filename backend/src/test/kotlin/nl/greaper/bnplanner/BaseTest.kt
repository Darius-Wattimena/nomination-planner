package nl.greaper.bnplanner

import nl.greaper.bnplanner.support.TestMongo
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.junit.jupiter.SpringExtension

@ExtendWith(SpringExtension::class)
@Import(DefaultTestConfiguration::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class BaseTest {
    companion object {
        /** The sessions need a MongoDB, everything else uses the mocked database */
        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("mongodb.host") { TestMongo.host }
            registry.add("mongodb.port") { TestMongo.port }
            registry.add("mongodb.db") { TestMongo.DATABASE }
        }
    }
}
