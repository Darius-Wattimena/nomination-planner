package nl.greaper.bnplanner.service

import com.nhaarman.mockitokotlin2.mock
import nl.greaper.bnplanner.auth.OsuTokenVerifierTest.Companion.createToken
import nl.greaper.bnplanner.auth.RolePermission
import nl.greaper.bnplanner.model.Gamemode
import nl.greaper.bnplanner.model.Role
import nl.greaper.bnplanner.model.User
import nl.greaper.bnplanner.model.UserContext
import nl.greaper.bnplanner.model.UserGamemode
import nl.greaper.bnplanner.model.discord.EmbedColor
import nl.greaper.bnplanner.model.discord.EmbedFooter
import nl.greaper.bnplanner.model.discord.EmbedThumbnail
import nl.greaper.bnplanner.model.osu.AuthToken
import nl.greaper.bnplanner.support.FakeOsu
import nl.greaper.bnplanner.support.Planner
import nl.greaper.bnplanner.support.RecordingDiscord.Sent
import nl.greaper.bnplanner.task.UserUpdater
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.scheduling.annotation.Scheduled
import java.time.Instant

/** Logging in, refreshing the login and the token used to update users */
class LoginServiceTest {
    private val planner = Planner()
    private val auth = planner.authService
    private val osu = planner.osuService
    private val token = createToken(subject = "5")

    private fun login(
        description: String,
        color: EmbedColor,
        user: User? = null,
    ) = Sent(
        description,
        color,
        true,
        emptyList(),
        EmbedThumbnail(user?.let { "https://a.ppy.sh/${it.osuId}" } ?: ""),
        EmbedFooter(user?.username ?: "", "https://a.ppy.sh/${user?.osuId}"),
    )

    private val user = User("5", "BN", listOf(UserGamemode(Gamemode.osu, Role.Nominator)))

    private fun osuKnowsUser() {
        FakeOsu.token(token, "new-refresh", 86400)
        FakeOsu.user("5", "BN", """[{"id":28,"playmodes":["osu"]}]""")
    }

    private fun assertContext(context: UserContext?) {
        val expected = Instant.now().plusSeconds(86400 - 300).toEpochMilli()
        assertTrue(context!!.validUntilEpochMilli in expected - 60_000..expected)
        assertEquals(
            UserContext(
                user,
                token,
                "new-refresh",
                context.validUntilEpochMilli,
                RolePermission(Role.Nominator, setOf("VIEWER", "EDITOR")),
            ),
            context,
        )
    }

    @Test
    fun `SUSPECTED BUG - Should log in, dropping the last character of the code`() {
        osuKnowsUser()

        assertContext(auth.login("code="))
        assertEquals(listOf("POST /oauth/token", "GET /api/v2/users/5?key=id"), FakeOsu.calls())
        assertTrue(FakeOsu.requests[0].body.contains(""""code":"code""""))
        assertEquals("Bearer $token", FakeOsu.requests[1].header("Authorization"))
        assertEquals(login("📗 **LOGIN:** BN logged in", EmbedColor.GREEN, user), planner.discord.single())
        assertEquals(user, planner.users.findUser("5"))
    }

    @Test
    fun `SUSPECTED BUG - Should refresh a login, dropping the last character of the token`() {
        osuKnowsUser()

        assertContext(auth.refresh("refresh="))
        assertTrue(FakeOsu.requests[0].body.contains(""""refresh_token":"refresh""""))
        assertEquals(login("📗 **LOGIN:** BN refreshed login", EmbedColor.GREEN, user), planner.discord.single())
    }

    @Test
    fun `Should not log in using a blank code`() {
        assertNull(auth.login(" ="))
        assertNull(auth.login(""))
        assertEquals(emptyList<String>(), FakeOsu.calls())
        assertEquals(
            login("📕 **ERROR:** Unexpected error occurred while logging in\nProvided code is blank", EmbedColor.RED),
            planner.discord.sent[0],
        )
    }

    @Test
    fun `Should not log in when osu refuses the code`() {
        FakeOsu.respond("POST /oauth/token", """{"error":"invalid_grant"}""", status = 400)

        assertNull(auth.login("code="))
        assertEquals(emptyList<Sent>(), planner.discord.sent)
    }

    @Test
    fun `Should not log in without a body`() {
        FakeOsu.respond("POST /oauth/token", "", status = 204)

        assertNull(auth.login("code="))
    }

    @Test
    fun `Should not refresh without a body`() {
        FakeOsu.respond("POST /oauth/token", "", status = 204)

        assertNull(auth.refresh("refresh="))
        assertEquals(
            login("📕 **ERROR:** Unexpected error occurred while refreshing user login\nCouldn't get token from osu", EmbedColor.RED),
            planner.discord.single(),
        )
    }

    @Test
    fun `Should not refresh when osu answers with anything but success`() {
        FakeOsu.respond(
            "POST /oauth/token",
            """{"token_type":"Bearer","expires_in":1,"access_token":"a","refresh_token":"r"}""",
            status = 302,
        )

        assertNull(auth.refresh("refresh="))
        assertTrue(
            planner.discord
                .single()
                .description
                .endsWith("\nCouldn't get token from osu"),
        )
    }

    @Test
    fun `Should not refresh when osu fails`() {
        FakeOsu.respond("POST /oauth/token", "{}", status = 500)

        assertNull(auth.refresh("refresh="))
        assertTrue(
            planner.discord
                .single()
                .description
                .startsWith("📕 **ERROR:** Unexpected error occurred while refreshing user login\n500"),
        )
    }

    @Test
    fun `Should not set up a login without a readable token`() {
        FakeOsu.token("unreadable")

        assertNull(auth.login("code="))
        assertNull(auth.refresh("refresh="))
        // SUSPECTED BUG - Reported in green, as this isn't an exception
        assertEquals(
            listOf(
                login("📕 **ERROR:** Could not set up context while logging in", EmbedColor.GREEN),
                login("📕 **ERROR:** Could not set up context while refreshing user login", EmbedColor.GREEN),
            ),
            planner.discord.sent,
        )
    }

    @Test
    fun `Should log in a restricted user as viewer`() {
        FakeOsu.token(token)

        val context = auth.login("code=")!!
        assertEquals(User("5", "RESTRICTED", emptyList(), true), context.user)
        assertEquals(RolePermission(Role.Mapper, setOf("VIEWER")), context.permission)
        assertEquals(
            "📗 **LOGIN:** RESTRICTED logged in",
            planner.discord.sent
                .last()
                .description,
        )
    }

    @Test
    fun `Should report a login without a user or message`() {
        auth.logLogin(UserContext(null, "", "", 0, RolePermission(Role.Mapper, emptySet())))
        auth.logLogin(null, exception = RuntimeException())

        assertEquals(
            listOf(
                login("📕 **ERROR:** Could not find user while logging in", EmbedColor.GREEN),
                login("📕 **ERROR:** Could not set up context while logging in", EmbedColor.RED),
            ),
            planner.discord.sent,
        )
    }

    @Test
    fun `Should log in without Discord`() {
        val auth = Planner(withDiscord = false).authService
        osuKnowsUser()

        assertContext(auth.login("code="))
    }

    @Test
    fun `Should use the permission of the developer`() {
        FakeOsu.token(createToken(subject = "2369776"))
        FakeOsu.user("2369776", "Greaper")

        assertEquals(
            RolePermission(Role.NominationAssessment, setOf("DEVELOPER", "ADMIN", "VIEWER", "EDITOR")),
            auth.login("code=")!!.permission,
        )
    }

    // Token of the updater

    private val later = Instant.now().plusSeconds(600).epochSecond
    private val soon = Instant.now().plusSeconds(200).epochSecond

    private fun decimalToken(expires: Long) = updaterToken("$expires.5")

    private fun updaterToken(expires: String): String {
        fun encode(value: String) =
            java.util.Base64
                .getUrlEncoder()
                .withoutPadding()
                .encodeToString(value.toByteArray())
        return "${encode("""{"alg":"RS256","typ":"JWT"}""")}.${encode("""{"sub":"5","exp":$expires}""")}.made-up"
    }

    private fun storedTokens() = planner.tokens.list().map { it.access_token }

    @Test
    fun `Should use a token that is still valid`() {
        val token = decimalToken(later)

        assertEquals(token, osu.getValidUpdaterToken(token, "refresh"))
        assertEquals(emptyList<String>(), FakeOsu.calls())
    }

    @Test
    fun `Should refresh a token within five minutes of expiring`() {
        planner.tokens.insertOne(AuthToken("Bearer", 1, "old", "refresh"))
        FakeOsu.token("new", "new-refresh")

        assertEquals("new", osu.getValidUpdaterToken(decimalToken(soon), "refresh"))
        assertEquals(listOf(AuthToken("Bearer", 86400, "new", "new-refresh")), planner.tokens.list())
        assertTrue(
            FakeOsu.requests
                .single()
                .body
                .contains(""""refresh_token":"refresh""""),
        )
    }

    @Test
    fun `Should refresh an expired or unreadable token`() {
        planner.tokens.insertOne(AuthToken("Bearer", 1, "old", "refresh"))
        FakeOsu.token("new")
        assertEquals("new", osu.getValidUpdaterToken(createToken(expires = 1), "refresh"))
        assertEquals(listOf("new"), storedTokens())

        FakeOsu.token("newer")
        assertEquals("newer", osu.getValidUpdaterToken("unreadable", "refresh"))
        assertEquals(listOf("newer"), storedTokens())
    }

    @Test
    fun `SUSPECTED BUG - A token expiring on a whole second is never valid`() {
        assertNull(osu.getValidUpdaterToken(updaterToken("$later"), "refresh"))
        assertNull(osu.getValidUpdaterToken(updaterToken("null"), "refresh"))
        assertEquals(emptyList<String>(), FakeOsu.calls())
    }

    @Test
    fun `Should remove the stored token when osu no longer accepts it`() {
        FakeOsu.respond("POST /oauth/token", "{}", status = 401)

        listOf("unreadable", decimalToken(soon)).forEach {
            planner.tokens.insertOne(AuthToken("Bearer", 1, "old", "refresh"))
            assertNull(osu.getValidUpdaterToken(it, "refresh"))
            assertEquals(emptyList<String>(), storedTokens())
        }
    }

    @Test
    fun `SUSPECTED BUG - The stored token is kept when osu fails to refresh, but throws`() {
        planner.tokens.insertOne(AuthToken("Bearer", 1, "old", "refresh"))
        FakeOsu.respond("POST /oauth/token", "{}", status = 500)

        assertThrows<Exception> { osu.getValidUpdaterToken("unreadable", "refresh") }
        assertThrows<Exception> { osu.getValidUpdaterToken(decimalToken(soon), "refresh") }
        assertEquals(listOf("old"), storedTokens())
    }

    // Updating users

    private val updater = UserUpdater(osu, planner.userService, planner.recalculations, planner.tokens)

    private fun recalculate(accessToken: String) {
        planner.userService.createTemporaryUser("7")
        planner.userService.createTemporaryUser("8")
        planner.tokens.insertOne(AuthToken("Bearer", 86400, accessToken, "refresh"))
        updater.taskUserUpdater()
    }

    @Test
    fun `Should update one user at a time`() {
        FakeOsu.user("7", "Seven")
        FakeOsu.user("8", "Eight")
        recalculate(decimalToken(later))

        assertEquals("Seven", planner.users.findUser("7")!!.username)
        assertEquals("8", planner.users.findUser("8")!!.username)
        assertEquals(listOf("8"), planner.recalculations.list().map { it.osuId })
        assertEquals("Bearer ${decimalToken(later)}", FakeOsu.requests.single().header("Authorization"))

        updater.taskUserUpdater()
        assertEquals("Eight", planner.users.findUser("8")!!.username)
        updater.taskUserUpdater()
        assertEquals(2, FakeOsu.calls().size)
    }

    @Test
    fun `Should not update users without a token`() {
        planner.userService.createTemporaryUser("7")
        updater.taskUserUpdater()

        assertEquals(1, planner.recalculations.list().size)
        assertEquals(emptyList<String>(), FakeOsu.calls())
    }

    @Test
    fun `Should not update users without a valid token`() {
        recalculate(updaterToken("$later"))

        assertEquals(2, planner.recalculations.list().size)
        assertEquals(emptyList<String>(), FakeOsu.calls())
    }

    @Test
    fun `Should keep the user to update when it can't be found`() {
        // Never the case at the moment, as an unknown user becomes restricted
        val updater = UserUpdater(osu, mock<UserService>(), planner.recalculations, planner.tokens)
        planner.userService.createTemporaryUser("7")
        planner.tokens.insertOne(AuthToken("Bearer", 86400, decimalToken(later), "refresh"))
        updater.taskUserUpdater()

        assertEquals(listOf("7"), planner.recalculations.list().map { it.osuId })
    }

    @Test
    fun `Should update users every 15 seconds, but only when active`() {
        val scheduled = UserUpdater::class.java.getMethod("taskUserUpdater").getAnnotation(Scheduled::class.java)
        assertEquals("*/15 * * * * *", scheduled.cron)

        val runner =
            ApplicationContextRunner()
                .withUserConfiguration(UserUpdater::class.java)
                .withBean(OsuService::class.java, { osu })
                .withBean(UserService::class.java, { planner.userService })
                .withBean(planner.recalculations.javaClass, { planner.recalculations })
                .withBean(planner.tokens.javaClass, { planner.tokens })

        fun isActive(property: String?): Boolean {
            var active = false
            val configured = if (property != null) runner.withPropertyValues(property) else runner
            configured.run { active = it.getBeansOfType(UserUpdater::class.java).isNotEmpty() }
            return active
        }

        assertEquals(
            listOf(false, false, true),
            listOf(null, "recalculation.active=false", "recalculation.active=true").map { isActive(it) },
        )
    }
}
