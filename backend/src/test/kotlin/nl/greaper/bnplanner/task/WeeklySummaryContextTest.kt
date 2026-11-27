package nl.greaper.bnplanner.task

import nl.greaper.bnplanner.support.ApiTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.getBean
import org.springframework.test.context.TestPropertySource

/** Off by default, and Spring can start it once turned on */
@TestPropertySource(properties = ["discord.weekly-summary.enabled=true"])
class WeeklySummaryContextTest : ApiTest() {
    @Test
    fun `Should post the weekly summary once turned on`() {
        context.getBean<WeeklySummary>().post()

        assertEquals(4, discord.sent.size)
    }
}
