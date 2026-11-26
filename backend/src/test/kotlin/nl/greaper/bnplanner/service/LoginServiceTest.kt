package nl.greaper.bnplanner.service

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
        EmbedFooter(user?.username ?: "", user?.osuId?.let { "https://a.ppy.sh/$it" }),
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

    // Stays until the login moves to Spring Security sessions, which removes this endpoint and the workaround in the frontend
    @Test
    fun `SUSPECTED BUG - Should refresh a login, dropping the last character of the token`() {
        osuKnowsUser()

        assertContext(auth.refresh("refresh="))
        assertTrue(FakeOsu.requests[0].body.contains(""""refresh_token":"refresh""""))
        assertEquals(login("📗 **LOGIN:** BN refreshed login", EmbedColor.GREEN, user), planner.discord.single())
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

        assertNull(auth.refresh("refresh="))
        assertEquals(
            listOf(login("📕 **ERROR:** Could not set up context while refreshing user login", EmbedColor.RED)),
            planner.discord.sent,
        )
    }

    @Test
    fun `Should log in a restricted user as viewer`() {
        FakeOsu.token(token)

        val context = auth.refresh("refresh=")!!
        assertEquals(User("5", "RESTRICTED", emptyList(), true), context.user)
        assertEquals(RolePermission(Role.Mapper, setOf("VIEWER")), context.permission)
        assertEquals(
            "📗 **LOGIN:** RESTRICTED refreshed login",
            planner.discord.sent
                .last()
                .description,
        )
    }

    @Test
    fun `Should report a login without a user or message`() {
        auth.logLogin(null)
        auth.logLogin(null, exception = RuntimeException())
        auth.logLogin(user)

        assertEquals(
            listOf(
                login("📕 **ERROR:** Could not find user while logging in", EmbedColor.RED),
                login("📕 **ERROR:** Could not find user while logging in", EmbedColor.RED),
                login("📗 **LOGIN:** BN logged in", EmbedColor.GREEN, user),
            ),
            planner.discord.sent,
        )
    }

    @Test
    fun `Should log in a known user with their roles when osu fails to find them`() {
        planner.users.saveUser(user)
        FakeOsu.token(token, "new-refresh", 86400)
        FakeOsu.respond("GET /api/v2/users/5?key=id", "{}", status = 503)

        assertContext(auth.refresh("refresh="))
        assertEquals(listOf(user), planner.users.list())
    }

    @Test
    fun `Should not log in an unknown user when osu fails to find them`() {
        FakeOsu.token(token, "new-refresh", 86400)
        FakeOsu.respond("GET /api/v2/users/5?key=id", "{}", status = 503)

        assertNull(auth.refresh("refresh="))
        assertEquals(emptyList<User>(), planner.users.list())
    }

    @Test
    fun `Should use the permission of the developer`() {
        FakeOsu.token(createToken(subject = "2369776"))
        FakeOsu.user("2369776", "Greaper")

        assertEquals(
            RolePermission(Role.NominationAssessment, setOf("DEVELOPER", "ADMIN", "VIEWER", "EDITOR")),
            auth.refresh("refresh=")!!.permission,
        )
    }

    @Test
    fun `Should not set up a login for a token without a subject`() {
        // {"exp":4102444800}
        FakeOsu.token("${token.substringBefore('.')}.eyJleHAiOjQxMDI0NDQ4MDB9.signature")

        assertNull(auth.refresh("refresh="))
        assertEquals(listOf("POST /oauth/token"), FakeOsu.calls())
    }

    @Test
    fun `Should refresh a login without Discord`() {
        val auth = Planner(withDiscord = false).authService
        osuKnowsUser()

        assertContext(auth.refresh("refresh="))
    }
}
