package nl.greaper.bnplanner.task

import nl.greaper.bnplanner.datasource.FollowedDataSource
import nl.greaper.bnplanner.model.osu.MeGroup
import nl.greaper.bnplanner.support.ApiTest
import nl.greaper.bnplanner.support.FakeOsu
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.getBean
import org.springframework.test.context.TestPropertySource

/** Both are off by default, and Spring can start them once turned on */
@TestPropertySource(properties = ["osu.follow-events.enabled=true", "osu.follow-groups.enabled=true"])
class FollowContextTest : ApiTest() {
    @Test
    fun `Should follow osu once turned on`() {
        FakeOsu.token("own")
        FakeOsu.beatmapsetEvents(1, FollowBeatmapEventsTest.page(FollowBeatmapEventsTest.event(5, "qualify", "1")))
        FollowGroups.GROUPS.forEach {
            FakeOsu.groupHistory(
                it,
                FollowGroupsTest.page(FollowGroupsTest.event(7, "user_add", it, "1", "osu")),
            )
        }

        context.getBean<FollowBeatmapEvents>().follow()
        context.getBean<FollowGroups>().follow()

        val followed = context.getBean<FollowedDataSource>()
        assertEquals(5, followed.findLastId(FollowBeatmapEvents.NAME))
        assertEquals(7, followed.findLastId("group-history-${MeGroup.NAT}"))
        // The sleeper of the application
        assertEquals(listOf(1_000L, 1_000L), sleeper.sleeps)
    }
}
