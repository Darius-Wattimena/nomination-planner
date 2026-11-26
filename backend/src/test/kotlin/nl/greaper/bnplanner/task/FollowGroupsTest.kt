package nl.greaper.bnplanner.task

import nl.greaper.bnplanner.datasource.FollowedDataSource
import nl.greaper.bnplanner.model.Gamemode
import nl.greaper.bnplanner.model.Role
import nl.greaper.bnplanner.model.User
import nl.greaper.bnplanner.model.UserGamemode
import nl.greaper.bnplanner.model.osu.MeGroup
import nl.greaper.bnplanner.support.FakeOsu
import nl.greaper.bnplanner.support.Planner
import nl.greaper.bnplanner.support.TestMongo
import nl.greaper.bnplanner.util.Backoff
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant

class FollowGroupsTest {
    private val planner = Planner()
    private val followed = FollowedDataSource(TestMongo.database)
    private val sleeps = mutableListOf<Long>()
    private var now = Instant.parse("2026-11-26T19:00:00Z")

    private fun follower() = FollowGroups(followed, planner.osuClient, planner.userService, { sleeps += it }, Backoff { now })

    private val follower = follower()

    private fun last(group: String) = followed.findLastId("group-history-$group")

    @Test
    fun `Should start at the newest change of each group the first time, changing nothing`() {
        FakeOsu.groupHistory(MeGroup.BN, FakeOsu.sample("group-history-28.json"))
        FakeOsu.groupHistory(MeGroup.PBN, page(event(3721, "user_add", MeGroup.PBN, "8863359", "taiko")))
        FakeOsu.groupHistory(MeGroup.NAT, page())

        assertEquals(0, follower.follow())

        assertEquals(3720, last(MeGroup.BN))
        assertEquals(3721, last(MeGroup.PBN))
        assertNull(last(MeGroup.NAT))
        assertEquals(emptyList<User>(), planner.users.allUsers())
        assertEquals(listOf(1_000L, 1_000L), sleeps)
        assertEquals(
            listOf("GET /groups/history?group_id=28", "GET /groups/history?group_id=32", "GET /groups/history?group_id=7"),
            FakeOsu.calls(),
        )
        assertEquals("application/json", FakeOsu.requests.first().header("Accept"))
        assertNull(FakeOsu.requests.first().header("Authorization"))
    }

    @Test
    fun `Should apply the newer changes oldest first, reading older pages, and continue there after a restart`() {
        followed.saveLastId("group-history-${MeGroup.BN}", 3700)
        followed.saveLastId("group-history-${MeGroup.PBN}", 3700)
        followed.saveLastId("group-history-${MeGroup.NAT}", 3700)
        FakeOsu.groupHistory(MeGroup.BN, FakeOsu.sample("group-history-28.json"))
        FakeOsu.groupHistory(MeGroup.BN, page(event(3700, "user_add", MeGroup.BN, "1", "osu"), cursor = "next"), cursor = "eyJpZCI6MzU3OH0")
        // The last page, without the last change handled
        FakeOsu.groupHistory(MeGroup.PBN, page(event(3721, "user_add", MeGroup.PBN, "8863359", "taiko")))
        FakeOsu.groupHistory(MeGroup.NAT, page(event(3699, "user_add", MeGroup.NAT, "2")))

        assertEquals(3, follower.follow())

        assertEquals(User("37783133", "nai_", listOf(UserGamemode(Gamemode.mania, Role.Nominator))), planner.users.findUser("37783133"))
        assertEquals(User("6233296", "tilda", listOf(UserGamemode(Gamemode.osu, Role.Nominator))), planner.users.findUser("6233296"))
        assertEquals(
            User("8863359", "user 8863359", listOf(UserGamemode(Gamemode.taiko, Role.Probation))),
            planner.users.findUser("8863359"),
        )
        assertNull(planner.users.findUser("270377"))
        assertEquals(listOf(3720L, 3721L, 3700L), listOf(MeGroup.BN, MeGroup.PBN, MeGroup.NAT).map { last(it) })
        assertEquals(
            listOf(
                "GET /groups/history?group_id=28",
                "GET /groups/history?group_id=28&cursor_string=eyJpZCI6MzU3OH0",
                "GET /groups/history?group_id=32",
                "GET /groups/history?group_id=7",
            ),
            FakeOsu.calls(),
        )

        FakeOsu.requests.clear()
        assertEquals(0, follower().follow())
        assertEquals(3, FakeOsu.calls().size)
    }

    @Test
    fun `Should stop reading after a number of pages`() {
        followed.saveLastId("group-history-${MeGroup.BN}", 1)
        followed.saveLastId("group-history-${MeGroup.PBN}", 1)
        followed.saveLastId("group-history-${MeGroup.NAT}", 1)
        (0 until FollowGroups.MAX_PAGES).forEach { index ->
            FakeOsu.groupHistory(
                MeGroup.BN,
                page(event(1000L - index, "user_set_default", MeGroup.BN, "1"), cursor = "c${index + 1}"),
                "c$index".takeIf {
                    index >
                        0
                },
            )
        }
        FakeOsu.groupHistory(MeGroup.PBN, page())
        FakeOsu.groupHistory(MeGroup.NAT, page())

        follower.follow()

        assertEquals(FollowGroups.MAX_PAGES, FakeOsu.calls().count { it.contains("group_id=28") })
        assertEquals(1000, last(MeGroup.BN))
    }

    @Test
    fun `Should wait after a failure, without asking about the other groups`() {
        FakeOsu.groupHistory(MeGroup.BN, "{}", status = 500)

        assertEquals(0, follower.follow())
        assertEquals(0, follower.follow())
        assertEquals(1, FakeOsu.calls().size)

        FakeOsu.groupHistory(MeGroup.BN, "")
        now = now.plusSeconds(60)
        follower.follow()
        assertEquals(2, FakeOsu.calls().size)

        FakeOsu.groupHistory(MeGroup.BN, page())
        FakeOsu.groupHistory(MeGroup.PBN, page())
        FakeOsu.groupHistory(MeGroup.NAT, page())
        now = now.plusSeconds(120)
        follower.follow()
        assertEquals(5, FakeOsu.calls().size)
    }

    @Test
    fun `Should start at the newest change in any order, by default`() {
        FakeOsu.groupHistory(MeGroup.BN, page(event(5, "user_add", MeGroup.BN, "1"), event(7, "user_add", MeGroup.BN, "1")))
        FakeOsu.groupHistory(MeGroup.PBN, page())
        FakeOsu.groupHistory(MeGroup.NAT, page())

        FollowGroups(followed, planner.osuClient, planner.userService).follow()

        assertEquals(7, last(MeGroup.BN))
    }

    @Test
    fun `Should send the cursor as osu gave it`() {
        FakeOsu.groupHistory(MeGroup.BN, page(), cursor = "a%2Bb%3D")

        assertEquals(emptyList<Any>(), planner.osuClient.findGroupHistory(MeGroup.BN, "a+b=").events)
    }

    companion object {
        fun page(
            vararg events: String,
            cursor: String? = null,
        ) = """{"cursor":null,"cursor_string":${cursor?.let { "\"$it\"" }},"events":[${events.joinToString(",")}],"params":{}}"""

        fun event(
            id: Long,
            type: String,
            group: String,
            user: String,
            vararg modes: String,
        ) = """
            {"created_at":"2026-11-24T19:51:17Z","group_id":$group,"hidden":false,"id":$id,"type":"$type","user_id":$user,
            "playmodes":${if (modes.isEmpty()) "null" else modes.joinToString(",", "[", "]") { "\"$it\"" }},"user_name":"user $user"}
            """.trimIndent()
    }
}
