package nl.greaper.bnplanner.service

import nl.greaper.bnplanner.model.Gamemode
import nl.greaper.bnplanner.model.Role
import nl.greaper.bnplanner.model.User
import nl.greaper.bnplanner.model.UserGamemode
import nl.greaper.bnplanner.model.beatmap.BeatmapStatus
import nl.greaper.bnplanner.model.osu.GroupEvent
import nl.greaper.bnplanner.model.osu.MeGroup
import nl.greaper.bnplanner.support.FakeOsu
import nl.greaper.bnplanner.support.Planner
import nl.greaper.bnplanner.support.Planner.Companion.beatmap
import nl.greaper.bnplanner.support.Planner.Companion.mode
import nl.greaper.bnplanner.support.Planner.Companion.nominator
import nl.greaper.bnplanner.util.getHighestRoleForUser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant

/** Someone who nominated before, but has no role in that gamemode now */
class FormerNominatorTest {
    private val planner = Planner(withDiscord = false)
    private val former = { gamemode: Gamemode -> UserGamemode(gamemode, Role.Former) }

    @Test
    fun `Should mark who nominated ranked beatmaps of a gamemode they have no role in`() {
        planner.user("20", "BN", nominator(Gamemode.osu))
        planner.user("21", "Left")
        planner.user("22", "Already", former(Gamemode.mania))
        val beatmaps =
            listOf(
                beatmap(
                    "1",
                    mode(Gamemode.osu, "20", "21", true, true),
                    mode(Gamemode.taiko, "20", "99", true, true),
                    status = BeatmapStatus.Ranked,
                ),
                beatmap("2", mode(Gamemode.mania, "22", "21", true, true), status = BeatmapStatus.Ranked),
                // Only ranked beatmaps count
                beatmap("3", mode(Gamemode.fruits, "21", firstNominated = true), status = BeatmapStatus.Nominated),
            )

        assertEquals(2, planner.userService.markFormerNominators(beatmaps))

        assertEquals(listOf(nominator(Gamemode.osu), former(Gamemode.taiko)), planner.users.findUser("20")!!.gamemodes)
        assertEquals(listOf(former(Gamemode.osu), former(Gamemode.mania)), planner.users.findUser("21")!!.gamemodes)
        assertEquals(listOf(former(Gamemode.mania)), planner.users.findUser("22")!!.gamemodes)
        // Unknown to the planner, nothing to mark
        assertEquals(null, planner.users.findUser("99"))
        assertEquals(
            listOf("21", "22"),
            planner.userService
                .findFormerNominators()
                .map { it.osuId }
                .sorted()
                .filter { it != "20" },
        )
        // By name
        assertEquals(listOf("22", "21"), planner.userService.findFormerNominators(Gamemode.mania).map { it.osuId })
        assertEquals(listOf("20"), planner.userService.findFormerNominators(Gamemode.taiko).map { it.osuId })
        assertEquals(0, planner.userService.markFormerNominators(beatmaps))
    }

    @Test
    fun `Should keep former gamemodes during a user sync, until they get a role in it again`() {
        FakeOsu.token("own")
        planner.user("20", "BN", former(Gamemode.taiko), former(Gamemode.osu))
        FakeOsu.user("20", "Back", """[{"id":${MeGroup.BN},"playmodes":["osu"]}]""")

        planner.syncService.syncUsers(setOf("20"), force = true)
        planner.osuJobs.finish()

        assertEquals(User("20", "Back", listOf(nominator(Gamemode.osu), former(Gamemode.taiko))), planner.users.findUser("20"))
    }

    @Test
    fun `Should replace former once someone joins the gamemode again`() {
        planner.user("20", "BN", former(Gamemode.osu))
        val event =
            GroupEvent(1, "user_add", MeGroup.BN, "20", "BN", "Beatmap Nominators", listOf("osu"), Instant.parse("2026-11-26T10:00:00Z"))

        planner.userService.applyGroupEvent(event)

        assertEquals(listOf(nominator(Gamemode.osu)), planner.users.findUser("20")!!.gamemodes)
    }

    @Test
    fun `Should only let a former nominator view, like a mapper`() {
        val permission = getHighestRoleForUser(User("20", "Former", listOf(former(Gamemode.osu), UserGamemode(Gamemode.osu, Role.Mapper))))

        assertEquals(Role.Former, permission.osuRole)
        assertEquals(setOf("VIEWER"), permission.roles)
        assertEquals(
            Role.Probation,
            getHighestRoleForUser(User("21", "", listOf(former(Gamemode.osu), UserGamemode(Gamemode.taiko, Role.Probation)))).osuRole,
        )
        assertEquals(
            listOf(false, true, true, true),
            listOf(Role.Former, Role.Nominator, Role.Probation, Role.NominationAssessment).map {
                it.canNominate
            },
        )
    }
}
