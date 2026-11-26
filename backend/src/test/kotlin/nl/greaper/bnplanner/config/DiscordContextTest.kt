package nl.greaper.bnplanner.config

import net.dv8tion.jda.api.JDA
import nl.greaper.bnplanner.Application
import nl.greaper.bnplanner.DefaultExceptionHandler
import nl.greaper.bnplanner.client.DiscordBotClient
import nl.greaper.bnplanner.client.DiscordClient
import nl.greaper.bnplanner.datasource.DiscordEventListenerDataSource
import nl.greaper.bnplanner.service.BeatmapService
import nl.greaper.bnplanner.support.FakeJda
import nl.greaper.bnplanner.support.Planner.Companion.LOGIN
import nl.greaper.bnplanner.support.Planner.Companion.beatmap
import nl.greaper.bnplanner.support.TestMongo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.ApplicationContext
import org.springframework.http.HttpHeaders.AUTHORIZATION
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post

/** Starts the application using the beans of production, only not logging in to Discord */
@SpringBootTest(classes = [Application::class])
@TestPropertySource(properties = ["discord.enabled=true"])
class DiscordEnabledTest {
    // Replaces the one that logs in
    @MockitoBean
    lateinit var jda: JDA

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            TestMongo.clear()
            TestMongo.insert("discord", """{"guildId": "guild", "channelId": "secret", "confidential": true}""")
            registry.add("mongodb.host") { TestMongo.host }
            registry.add("mongodb.port") { TestMongo.port }
            registry.add("mongodb.db") { TestMongo.DATABASE }
        }
    }

    @Autowired
    lateinit var context: ApplicationContext

    @Test
    fun `Should send messages to Discord`() {
        val discord = FakeJda()
        discord.addChannel("secret")
        whenever(jda.getTextChannelById("secret")).thenAnswer { discord.jda.getTextChannelById("secret") }

        assertTrue(context.getBean(DiscordClient::class.java) is DiscordBotClient)
        assertEquals(1, context.getBean(DiscordEventListenerDataSource::class.java).getListeners().size)

        context.getBean(BeatmapService::class.java).logBeatmapDelete(LOGIN, beatmap())
        assertEquals("secret", discord.messages.single().channel)
    }
}

@SpringBootTest(classes = [Application::class])
@AutoConfigureMockMvc
class DiscordDisabledTest {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("mongodb.host") { TestMongo.host }
            registry.add("mongodb.port") { TestMongo.port }
            registry.add("mongodb.db") { TestMongo.DATABASE }
        }
    }

    @Autowired
    lateinit var context: ApplicationContext

    @Autowired
    lateinit var mvc: MockMvc

    @Test
    fun `Should handle requests and errors without Discord`() {
        assertTrue(context.getBeansOfType(DiscordClient::class.java).isEmpty())
        assertTrue(context.getBeansOfType(JDA::class.java).isEmpty())

        val response = context.getBean(DefaultExceptionHandler::class.java).exception(RuntimeException("made up"))
        assertEquals("Unable to handle request", response.body)
    }
}
