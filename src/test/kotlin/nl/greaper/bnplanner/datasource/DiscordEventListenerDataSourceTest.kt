package nl.greaper.bnplanner.datasource

import nl.greaper.bnplanner.model.Gamemode
import nl.greaper.bnplanner.model.discord.DiscordEventListener
import nl.greaper.bnplanner.support.TestMongo
import org.bson.Document
import org.bson.types.ObjectId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class DiscordEventListenerDataSourceTest {
    @BeforeEach
    fun setup() = TestMongo.clear()

    private fun source() = DiscordEventListenerDataSource(TestMongo.database)

    private val osu = DiscordEventListener("guild", "1", Gamemode.osu)
    private val all = DiscordEventListener("guild", "2", null, confidential = true)
    private val other = DiscordEventListener("other", "3", null)

    @Test
    fun `Should store a listener in the expected document shape`() {
        assertTrue(source().create(osu).wasAcknowledged())
        source().create(all)

        val stored = TestMongo.documents("discord")
        assertTrue(stored.all { it["_id"] is ObjectId })
        stored.forEach { it.remove("_id") }
        assertEquals(
            listOf(
                Document.parse("""{"guildId": "guild", "channelId": "1", "gamemode": "osu", "confidential": false}"""),
                Document.parse("""{"guildId": "guild", "channelId": "2", "gamemode": null, "confidential": true}"""),
            ),
            stored,
        )
    }

    @Test
    fun `Should load the stored listeners when starting`() {
        TestMongo.insert("discord", """{"guildId": "guild", "channelId": "1", "gamemode": "osu"}""")
        TestMongo.insert("discord", """{"guildId": "guild", "channelId": "2", "confidential": true}""")

        assertEquals(listOf(osu, all), source().getListeners())
    }

    @Test
    fun `Should find listeners`() {
        val source = source()
        listOf(osu, all, other).forEach { source.create(it) }

        assertEquals(listOf(osu, all, other), source.getListeners())
        assertEquals(listOf(osu, all), source.findByGuildId("guild"))
        assertEquals(emptyList<DiscordEventListener>(), source.findByGuildId("unknown"))
        assertEquals(all, source.findByGuildIdAndChannelId("guild", "2"))
        assertNull(source.findByGuildIdAndChannelId("guild", "3"))
        assertNull(source.findByGuildIdAndChannelId("other", "2"))
    }

    @Test
    fun `Should replace a listener`() {
        val source = source()
        listOf(osu, all, other).forEach { source.create(it) }
        val updated = osu.copy(gamemode = Gamemode.mania)

        assertEquals(1, source.replace(osu, updated).modifiedCount)
        assertEquals(listOf(all, other, updated), source.getListeners())
        assertEquals(listOf(updated, all, other), source().getListeners())
    }

    @Test
    fun `Should count replacing a listener with the same configuration as modified`() {
        val source = source()
        source.create(osu)

        // The /planner register command would answer with an error otherwise
        val result = source.replace(osu, osu)
        assertEquals(1, result.matchedCount)
        assertEquals(1, result.modifiedCount)
    }

    @Test
    fun `Should remove a listener`() {
        val source = source()
        listOf(osu, all, other).forEach { source.create(it) }

        assertEquals(1, source.remove(all).deletedCount)
        assertEquals(0, source.remove(all).deletedCount)
        assertEquals(listOf(osu, other), source.getListeners())
        assertEquals(listOf(osu, other), source().getListeners())
    }
}
