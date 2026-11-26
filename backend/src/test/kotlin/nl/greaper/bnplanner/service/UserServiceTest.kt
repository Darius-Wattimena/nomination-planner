package nl.greaper.bnplanner.service

import nl.greaper.bnplanner.auth.OsuTokenVerifierTest.Companion.createToken
import nl.greaper.bnplanner.model.Gamemode
import nl.greaper.bnplanner.model.Role
import nl.greaper.bnplanner.model.User
import nl.greaper.bnplanner.model.UserGamemode
import nl.greaper.bnplanner.model.discord.EmbedColor
import nl.greaper.bnplanner.model.discord.EmbedFooter
import nl.greaper.bnplanner.model.discord.EmbedThumbnail
import nl.greaper.bnplanner.support.FakeOsu
import nl.greaper.bnplanner.support.Planner
import nl.greaper.bnplanner.support.Planner.Companion.TOKEN
import nl.greaper.bnplanner.support.Planner.Companion.nominator
import nl.greaper.bnplanner.support.RecordingDiscord.Sent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class UserServiceTest {
    private val planner = Planner()
    private val service = planner.userService

    private fun force(
        id: String = "5",
        log: Boolean = true,
    ) = service.forceFindUserById(TOKEN, id, log)

    private fun sent(
        description: String,
        color: EmbedColor,
    ) = Sent(description, color, true, emptyList(), EmbedThumbnail("https://a.ppy.sh/5"), EmbedFooter("Nomination Planner"))

    @Test
    fun `Should find a user at osu and save it`() {
        FakeOsu.user(
            "5",
            "BN",
            """
            [{"id":28,"identifier":"bng","name":"Beatmap Nominators","playmodes":["osu","Mania"]},
            {"id":32,"playmodes":["taiko"]},{"id":7,"playmodes":["fruits"]},
            {"id":11,"playmodes":["osu"]},{"id":16,"playmodes":null},{"id":28,"playmodes":null}]
            """,
        )
        val expected =
            User(
                "5",
                "BN",
                listOf(
                    nominator(Gamemode.osu),
                    nominator(Gamemode.mania),
                    UserGamemode(Gamemode.taiko, Role.Probation),
                    UserGamemode(Gamemode.fruits, Role.NominationAssessment),
                ),
            )

        assertEquals(expected, force())
        assertEquals(expected, service.findUserById("5"))
        assertEquals(listOf(expected), planner.users.list())
        assertEquals(sent("Created user 5, with username: BN", EmbedColor.BLUE), planner.discord.single())
    }

    @Test
    fun `Should give NAT without a gamemode all gamemodes`() {
        FakeOsu.user("5", "NAT", """[{"id":7,"playmodes":[]}]""")
        FakeOsu.user("6", "NAT", """[{"id":7,"playmodes":null}]""")
        FakeOsu.user("7", "BN", """[{"id":28,"playmodes":[]}]""")

        val expected = Gamemode.values().map { UserGamemode(it, Role.NominationAssessment) }

        assertEquals(expected, service.forceFindUserById(TOKEN, "5")!!.gamemodes)
        assertEquals(expected, service.forceFindUserById(TOKEN, "6")!!.gamemodes)
        assertEquals(emptyList<UserGamemode>(), service.forceFindUserById(TOKEN, "7")!!.gamemodes)
    }

    @Test
    fun `Should find a user without groups`() {
        FakeOsu.respond("GET /api/v2/users/5?key=id", """{"id":5,"username":"Mapper"}""")
        FakeOsu.respond("GET /api/v2/users/6?key=id", """{"id":6,"username":"Mapper","groups":null}""")

        assertEquals(User("5", "Mapper", emptyList()), force(log = false))
        assertEquals(User("6", "Mapper", emptyList()), force("6", log = false))
        assertEquals(emptyList<Sent>(), planner.discord.sent)
    }

    @Test
    fun `Should save a user osu does not know as restricted`() {
        val expected = User("5", "RESTRICTED", emptyList(), restricted = true)

        assertEquals(expected, force(log = false))
        assertEquals(listOf(expected), planner.users.list())
        assertEquals(sent("Could not find user with id 5, created restricted user", EmbedColor.ORANGE), planner.discord.single())
    }

    @Test
    fun `Should keep what is known about a user when osu is unavailable`() {
        val user = planner.user("5", "BN", nominator(Gamemode.osu))
        FakeOsu.respond("GET /api/v2/users/5?key=id", "", status = 503)
        FakeOsu.respond("GET /api/v2/users/6?key=id", "", status = 503)

        assertNull(force())
        assertNull(force("6"))
        assertEquals(listOf(user), planner.users.list())
        assertEquals(user, service.findUserById("5"))
        assertEquals(emptyList<Sent>(), planner.discord.sent)
    }

    @Test
    fun `Should leave out a gamemode the planner does not know`() {
        FakeOsu.user("5", "BN", """[{"id":28,"playmodes":["new-mode","osu"]}]""")

        assertEquals(listOf(Gamemode.osu), force()!!.gamemodes.map { it.gamemode })
    }

    @Test
    fun `Should find users with and without a known editor`() {
        planner.user("99", "Editor")
        FakeOsu.user("5", "BN")

        assertEquals("BN", service.forceFindUserById(TOKEN, "5")!!.username)
        assertEquals("BN", service.forceFindUserById("unreadable", "5")!!.username)
        assertEquals("RESTRICTED", service.forceFindUserById(TOKEN, "6")!!.username)
        assertEquals("RESTRICTED", service.forceFindUserById("unreadable", "6")!!.username)
    }

    @Test
    fun `Should work without Discord`() {
        val service = Planner(withDiscord = false).userService
        FakeOsu.user("5", "BN")

        assertEquals("BN", service.forceFindUserById(TOKEN, "5")!!.username)
        assertEquals("RESTRICTED", service.forceFindUserById(TOKEN, "6")!!.username)
    }

    @Test
    fun `Should save the modes a user is interested in`() {
        planner.user("99", "Mapper")

        val expected = User("99", "Mapper", emptyList(), interests = listOf(Gamemode.osu, Gamemode.mania))
        assertEquals(expected, service.saveInterests(TOKEN, setOf(Gamemode.mania, Gamemode.osu)))
        assertEquals(listOf(expected), planner.users.list())
        // Nothing chosen is the same as never having chosen
        assertEquals(User("99", "Mapper", emptyList()), service.saveInterests(TOKEN, emptySet()))
        assertEquals(listOf(User("99", "Mapper", emptyList())), planner.users.list())
    }

    @Test
    fun `Should not save interests of an unknown user`() {
        assertNull(service.saveInterests(TOKEN, setOf(Gamemode.osu)))
        assertNull(service.saveInterests("unreadable", setOf(Gamemode.osu)))
        assertEquals(emptyList<User>(), planner.users.list())
    }

    @Test
    fun `Should keep the interests of a user when finding them at osu again`() {
        planner.users.saveUser(User("5", "Old", emptyList(), interests = listOf(Gamemode.taiko)))
        planner.users.saveUser(User("6", "Old", emptyList(), interests = listOf(Gamemode.fruits)))
        FakeOsu.user("5", "BN", """[{"id":28,"playmodes":["osu"]}]""")

        assertEquals(User("5", "BN", listOf(nominator(Gamemode.osu)), interests = listOf(Gamemode.taiko)), force(log = false))
        assertEquals(listOf(Gamemode.fruits), force("6", log = false)!!.interests)
        assertEquals(listOf(Gamemode.taiko), service.findUserById("5")!!.interests)
    }

    @Test
    fun `Should find a user by id`() {
        val user = planner.user("5")

        assertEquals(user, service.findUserById("5"))
        assertNull(service.findUserById("6"))
        assertNull(service.findUserById("-1"))
        assertNull(service.findUserById("-2"))
        assertEquals(User("0", "None", emptyList()), service.findUserById("0"))
    }

    @Test
    fun `Should find the editor of a token`() {
        val editor = planner.user("99")

        assertEquals("99", service.getEditorId(TOKEN))
        assertEquals("99", service.getEditorId("Bearer $TOKEN"))
        assertEquals(editor, service.getEditor(TOKEN))
        assertNull(service.getEditorId("unreadable"))
        assertNull(service.getEditor("unreadable"))
        assertNull(service.getEditor(createToken(subject = "98")))
        // Expired tokens can't be read
        assertNull(service.getEditorId(createToken(expires = 1)))
    }
}
