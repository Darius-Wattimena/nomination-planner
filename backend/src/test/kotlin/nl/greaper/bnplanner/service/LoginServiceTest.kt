package nl.greaper.bnplanner.service

import nl.greaper.bnplanner.model.Gamemode
import nl.greaper.bnplanner.model.Role
import nl.greaper.bnplanner.model.User
import nl.greaper.bnplanner.model.UserGamemode
import nl.greaper.bnplanner.model.discord.EmbedColor
import nl.greaper.bnplanner.model.discord.EmbedFooter
import nl.greaper.bnplanner.model.discord.EmbedThumbnail
import nl.greaper.bnplanner.support.Planner
import nl.greaper.bnplanner.support.RecordingDiscord.Sent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Telling the developer who logged in */
class LoginServiceTest {
    private val planner = Planner()
    private val auth = planner.authService
    private val user = User("5", "BN", listOf(UserGamemode(Gamemode.osu, Role.Nominator)))

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

    @Test
    fun `Should report a login`() {
        auth.logLogin(user)

        assertEquals(login("📗 **LOGIN:** BN logged in", EmbedColor.GREEN, user), planner.discord.single())
    }

    @Test
    fun `Should report a failed login`() {
        auth.logLogin(null)
        auth.logLogin(null, exception = RuntimeException("osu! refused"))
        auth.logLogin(null, exception = RuntimeException())

        assertEquals(
            listOf(
                login("📕 **ERROR:** Could not find user while logging in", EmbedColor.RED),
                login("📕 **ERROR:** Unexpected error occurred while logging in\nosu! refused", EmbedColor.RED),
                login("📕 **ERROR:** Unexpected error occurred while logging in\nnull", EmbedColor.RED),
            ),
            planner.discord.sent,
        )
    }

    @Test
    fun `Should report a login without Discord`() {
        Planner(withDiscord = false).authService.logLogin(user)
    }
}
