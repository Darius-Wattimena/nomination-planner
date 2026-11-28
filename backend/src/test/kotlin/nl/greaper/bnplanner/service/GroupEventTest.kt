package nl.greaper.bnplanner.service

import nl.greaper.bnplanner.model.Gamemode
import nl.greaper.bnplanner.model.Role
import nl.greaper.bnplanner.model.User
import nl.greaper.bnplanner.model.UserGamemode
import nl.greaper.bnplanner.model.discord.EmbedColor
import nl.greaper.bnplanner.model.discord.EmbedFooter
import nl.greaper.bnplanner.model.discord.EmbedThumbnail
import nl.greaper.bnplanner.model.osu.GroupEvent
import nl.greaper.bnplanner.model.osu.MeGroup
import nl.greaper.bnplanner.support.FakeOsu
import nl.greaper.bnplanner.support.Planner
import nl.greaper.bnplanner.support.Planner.Companion.nominator
import nl.greaper.bnplanner.support.RecordingDiscord
import nl.greaper.bnplanner.support.TestMongo
import org.bson.Document
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant

/** How someone joining or leaving a group on osu! changes them on the planner, the same way a user sync does */
class GroupEventTest {
    private val planner = Planner()

    private fun apply(event: GroupEvent) = planner.userService.applyGroupEvent(event)

    private fun gamemodes(
        role: Role,
        vararg gamemodes: Gamemode,
    ) = gamemodes.map { UserGamemode(it, role) }

    private fun message(
        text: String,
        color: EmbedColor = EmbedColor.GREEN,
        user: String = "40",
        username: String = "tilda",
    ) = RecordingDiscord.Sent(
        "**[$username](https://osu.ppy.sh/users/$user)**\n$text",
        color,
        true,
        emptyList(),
        EmbedThumbnail("https://a.ppy.sh/$user"),
        EmbedFooter("osu!"),
    )

    @Test
    fun `Should add someone the planner does not know yet`() {
        val added = apply(event("user_add", modes = listOf("osu")))

        val expected = User("40", "tilda", gamemodes(Role.Nominator, Gamemode.osu))
        assertEquals(expected, added)
        assertEquals(expected, planner.users.findUser("40"))
        assertEquals(
            Document("_id", "40")
                .append("username", "tilda")
                .append("gamemodes", listOf(Document("gamemode", "osu").append("role", "Nominator")))
                .append("restricted", null),
            TestMongo.database
                .getCollection("users")
                .find()
                .single(),
        )
        assertEquals(message("Added to Beatmap Nominators [osu]"), planner.discord.single())
        assertEquals(emptyList<String>(), FakeOsu.calls())
    }

    @Test
    fun `Should replace the gamemodes of the group only, keeping the other groups like a user sync`() {
        planner.user(
            "40",
            "old name",
            nominator(Gamemode.osu),
            nominator(Gamemode.taiko),
            UserGamemode(Gamemode.mania, Role.NominationAssessment),
            // Not a group, which a user sync leaves out as well
            UserGamemode(Gamemode.osu, Role.Mapper),
        )

        apply(event("user_add", modes = listOf("taiko", "fruits")))

        assertEquals(
            User(
                "40",
                "tilda",
                gamemodes(Role.NominationAssessment, Gamemode.mania) + gamemodes(Role.Nominator, Gamemode.taiko, Gamemode.fruits) +
                    // No longer a nominator of osu!
                    gamemodes(Role.Former, Gamemode.osu),
            ),
            planner.users.findUser("40"),
        )
        assertEquals(
            message("Added to Beatmap Nominators [catch]\nRemoved from Beatmap Nominators [osu]", EmbedColor.RED),
            planner.discord.single(),
        )
    }

    @Test
    fun `Should give a NAT member without a gamemode all of them`() {
        apply(event("user_add", group = MeGroup.NAT, groupName = "Nomination Assessment Team"))

        assertEquals(gamemodes(Role.NominationAssessment, *Gamemode.entries.toTypedArray()), planner.users.findUser("40")!!.gamemodes)
        assertEquals(message("Added to Nomination Assessment Team [osu, taiko, catch, mania]"), planner.discord.single())
    }

    @Test
    fun `Should give a NAT member their own gamemodes`() {
        apply(event("user_add", group = MeGroup.NAT, modes = listOf("mania")))

        assertEquals(gamemodes(Role.NominationAssessment, Gamemode.mania), planner.users.findUser("40")!!.gamemodes)
    }

    @Test
    fun `Should remove someone from a group, keeping the other groups, as a former nominator`() {
        planner.user("40", "tilda", nominator(Gamemode.osu), UserGamemode(Gamemode.osu, Role.NominationAssessment))

        apply(event("user_remove"))
        apply(event("user_remove", group = MeGroup.NAT))

        assertEquals(gamemodes(Role.Former, Gamemode.osu), planner.users.findUser("40")!!.gamemodes)
        assertEquals(
            listOf(
                message("Removed from Beatmap Nominators [osu]", EmbedColor.RED),
                message("Removed from ${MeGroup.NAT} [osu]", EmbedColor.RED),
            ),
            planner.discord.sent,
        )
    }

    @Test
    fun `Should follow a change of gamemodes`() {
        planner.user("40", "tilda", nominator(Gamemode.osu))

        apply(event("user_add_playmodes", modes = listOf("mania")))
        apply(event("user_remove_playmodes", modes = listOf("osu")))

        assertEquals(
            gamemodes(Role.Nominator, Gamemode.mania) + gamemodes(Role.Former, Gamemode.osu),
            planner.users.findUser("40")!!.gamemodes,
        )
        assertEquals(
            listOf(message("Added to Beatmap Nominators [mania]"), message("Removed from Beatmap Nominators [osu]", EmbedColor.RED)),
            planner.discord.sent,
        )
    }

    @Test
    fun `Should no longer see someone as restricted once they join, but keep it when they leave`() {
        planner.users.saveUser(User("40", "RESTRICTED", emptyList(), restricted = true))
        planner.users.saveUser(User("41", "RESTRICTED", emptyList(), restricted = true))

        apply(event("user_add", modes = listOf("osu")))
        assertNull(apply(event("user_remove", user = "41", name = null)))

        assertEquals(User("40", "tilda", gamemodes(Role.Nominator, Gamemode.osu)), planner.users.findUser("40"))
        assertEquals(User("41", "RESTRICTED", emptyList(), restricted = true), planner.users.findUser("41"))
        assertEquals(message("Added to Beatmap Nominators [osu]"), planner.discord.single())
    }

    @Test
    fun `Should only change the name when that is all that changed`() {
        planner.user("40", "old name", nominator(Gamemode.osu))

        apply(event("user_add", modes = listOf("osu")))

        assertEquals("tilda", planner.users.findUser("40")!!.username)
        assertEquals(message("Updated"), planner.discord.single())
    }

    @Test
    fun `Should change nothing when osu tells what is known already, in any order`() {
        val known = planner.user("40", "tilda", UserGamemode(Gamemode.taiko, Role.Nominator), UserGamemode(Gamemode.osu, Role.Nominator))

        assertNull(apply(event("user_add", modes = listOf("osu", "taiko"))))
        assertNull(apply(event("user_add_playmodes", modes = listOf("osu", "quaver"))))
        assertEquals(known, planner.users.findUser("40"))
        assertEquals(emptyList<RecordingDiscord.Sent>(), planner.discord.sent)
    }

    @Test
    fun `Should call someone unknown without a name Unknown`() {
        apply(event("user_add", name = null, modes = listOf("osu")))

        assertEquals("Unknown", planner.users.findUser("40")!!.username)
    }

    @Test
    fun `Should ignore other groups, other events and events without anybody`() {
        assertNull(apply(event("user_add", group = "4", modes = listOf("osu"))))
        assertNull(apply(event("user_set_default")))
        assertNull(apply(event("user_add", user = null, modes = listOf("osu"))))
        // Someone unknown who isn't in the group has nothing to remember
        assertNull(apply(event("user_remove")))
        assertNull(apply(event("user_remove_playmodes", modes = listOf("osu"))))
        assertEquals(emptyList<User>(), planner.users.allUsers())
    }

    @Test
    fun `Should work without Discord`() {
        val planner = Planner(withDiscord = false)

        assertEquals("tilda", planner.userService.applyGroupEvent(event("user_add", modes = listOf("osu")))!!.username)
    }

    companion object {
        fun event(
            type: String,
            group: String = MeGroup.BN,
            user: String? = "40",
            name: String? = "tilda",
            modes: List<String>? = null,
            groupName: String? = "Beatmap Nominators".takeIf { group == MeGroup.BN },
            id: Long = 3718,
        ) = GroupEvent(id, type, group, user, name, groupName, modes, Instant.parse("2026-11-24T19:51:17Z"))
    }
}
