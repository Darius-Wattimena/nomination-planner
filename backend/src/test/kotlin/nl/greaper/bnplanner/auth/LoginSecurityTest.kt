package nl.greaper.bnplanner.auth

import nl.greaper.bnplanner.BaseTest
import nl.greaper.bnplanner.client.OsuHttpClient
import nl.greaper.bnplanner.datasource.UserDataSource
import nl.greaper.bnplanner.model.Gamemode
import nl.greaper.bnplanner.model.Role
import nl.greaper.bnplanner.model.User
import nl.greaper.bnplanner.model.UserGamemode
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpHeaders.AUTHORIZATION
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

/** Performs requests through the actual security setup */
@AutoConfigureMockMvc
@TestPropertySource(properties = ["osu.clientId=14229"])
class LoginSecurityTest : BaseTest() {
    @Autowired
    lateinit var mvc: MockMvc

    @Autowired
    lateinit var users: UserDataSource

    @MockitoBean
    lateinit var client: OsuHttpClient

    @BeforeEach
    fun setup() {
        users.addInMemoryUser(User("123", "NAT", listOf(UserGamemode(Gamemode.osu, Role.NominationAssessment))))
    }

    private fun search(authorization: String) = mvc.get("/v2/user/search") { header(AUTHORIZATION, authorization) }

    @Test
    fun `Should refuse a forged token of an existing user`() {
        whenever(client.findTokenOwnerId(any())) doReturn null

        search("Bearer ${OsuTokenVerifierTest.createToken()}").andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `Should allow a token accepted by osu`() {
        whenever(client.findTokenOwnerId(any())) doReturn "123"

        val token = OsuTokenVerifierTest.createToken(expires = 4102444801)
        search("Bearer $token").andExpect { status { isOk() } }
    }

    @Test
    fun `Should be unavailable when osu can not verify the token`() {
        whenever(client.findTokenOwnerId(any())) doThrow OsuUnavailableException(RuntimeException())

        val token = OsuTokenVerifierTest.createToken(expires = 4102444802)
        search("Bearer $token").andExpect { status { isServiceUnavailable() } }
    }

    @Test
    fun `Should refuse an empty Aiess token when none is configured`() {
        listOf("Aiess  x", "Aiess ", "Aiess x").forEach {
            mvc
                .post("/v2/aiess/event/user") { header(AUTHORIZATION, it) }
                .andExpect { status { isUnauthorized() } }
        }
    }
}
