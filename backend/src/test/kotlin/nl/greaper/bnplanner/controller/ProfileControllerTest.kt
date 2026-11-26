package nl.greaper.bnplanner.controller

import nl.greaper.bnplanner.model.Gamemode
import nl.greaper.bnplanner.support.Planner
import nl.greaper.bnplanner.support.Planner.Companion.LOGIN
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException

class ProfileControllerTest {
    private val planner = Planner()
    private val controller = ProfileController(planner.profileService, planner.userService)

    // Security only lets known users in, but they can disappear in the meantime
    @Test
    fun `Should answer not found for a user that is not known`() {
        val exception = assertThrows<ResponseStatusException> { controller.saveInterests(LOGIN, setOf(Gamemode.osu)) }

        assertEquals(HttpStatus.NOT_FOUND, exception.statusCode)
    }
}
