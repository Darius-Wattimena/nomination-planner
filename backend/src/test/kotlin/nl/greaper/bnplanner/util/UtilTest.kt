package nl.greaper.bnplanner.util

import nl.greaper.bnplanner.ServletInitializer
import nl.greaper.bnplanner.auth.RolePermission
import nl.greaper.bnplanner.config.DatabaseConfig
import nl.greaper.bnplanner.config.MongoProperties
import nl.greaper.bnplanner.model.Gamemode
import nl.greaper.bnplanner.model.PlannerEvent
import nl.greaper.bnplanner.model.Role
import nl.greaper.bnplanner.model.User
import nl.greaper.bnplanner.model.UserGamemode
import nl.greaper.bnplanner.model.beatmap.BeatmapStatus
import nl.greaper.bnplanner.model.beatmap.BeatmapStatus.Companion.toPriorityStatus
import nl.greaper.bnplanner.model.discord.EmbedColor
import nl.greaper.bnplanner.model.discord.EmbedFooter
import nl.greaper.bnplanner.model.discord.EmbedMessage
import nl.greaper.bnplanner.model.discord.EmbedThumbnail
import nl.greaper.bnplanner.model.discord.getValue
import nl.greaper.bnplanner.model.osu.BeatmapSet
import nl.greaper.bnplanner.support.Planner.Companion.beatmap
import nl.greaper.bnplanner.support.Planner.Companion.mode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.boot.builder.SpringApplicationBuilder
import java.time.Instant

class UtilTest {
    @Test
    fun `Should know the priority of every status`() {
        BeatmapStatus.entries.forEachIndexed { index, status ->
            assertEquals(index + 1, status.toPriorityStatus())
            assertEquals(status, BeatmapStatus.fromPriorityStatus(index + 1))
        }
        assertNull(BeatmapStatus.fromPriorityStatus(0))
        assertNull(BeatmapStatus.fromPriorityStatus(9))
    }

    @Test
    fun `Should find nominations`() {
        val none = mode(Gamemode.osu)
        val first = mode(Gamemode.osu, firstNominated = true)
        val second = mode(Gamemode.osu, secondNominated = true)
        val both = mode(Gamemode.osu, firstNominated = true, secondNominated = true)

        assertEquals(listOf(false, true, true, true), listOf(none, first, second, both).map { it.hasAnyNomination() })
        assertEquals(listOf(false, false, false, true), listOf(none, first, second, both).map { it.hasAllNominations() })
    }

    @Test
    fun `Should need two nominations for the main gamemode and one for the others`() {
        val osuTwice = mode(Gamemode.osu, firstNominated = true, secondNominated = true)
        val osuOnce = mode(Gamemode.osu, firstNominated = true)
        val taikoOnce = mode(Gamemode.taiko, secondNominated = true)
        val taikoNever = mode(Gamemode.taiko)
        val osu = listOf(Gamemode.osu)
        val taiko = listOf(Gamemode.taiko)

        assertTrue(beatmap("1", osuTwice).hasEnoughNominations())
        assertFalse(beatmap("1", osuOnce).hasEnoughNominations())
        assertTrue(beatmap("1", osuTwice, taikoOnce, main = osu).hasEnoughNominations())
        assertFalse(beatmap("1", osuTwice, taikoOnce, main = taiko).hasEnoughNominations())
        assertFalse(beatmap("1", osuTwice, taikoNever, main = osu).hasEnoughNominations())
        assertTrue(beatmap("1", osuTwice, taikoOnce, main = osu + taiko).hasEnoughNominations())
        assertFalse(beatmap("1", osuOnce, taikoOnce, main = osu + taiko).hasEnoughNominations())
        assertTrue(beatmap("1", osuTwice, taikoOnce).hasEnoughNominations())
        // The main gamemode is not on the planner
        assertTrue(beatmap("1", osuOnce, main = taiko).hasEnoughNominations())
    }

    private fun set(
        vararg difficulties: Pair<Gamemode, Int>,
        summary: BeatmapSet.NominationsSummary? = null,
    ) = BeatmapSet(
        id = "1",
        title = "Title",
        artist = "Artist",
        creator = "Host",
        user_id = "10",
        ranked = 0,
        last_updated = Instant.EPOCH,
        beatmaps = difficulties.map { (mode, mapper) -> BeatmapSet.Beatmap(100, mapper, mode, "Hard") },
        nominationsSummary = summary,
    ).findMainGamemodes()

    @Test
    fun `Should find the main gamemode by its difficulties`() {
        val osu = Gamemode.osu to 10
        val taiko = Gamemode.taiko to 10
        val guestTaiko = Gamemode.taiko to 11

        assertNull(set())
        assertNull(set(osu, osu))
        // The most difficulties
        assertEquals(listOf(Gamemode.osu), set(osu, osu, taiko))
        assertEquals(listOf(Gamemode.taiko), set(osu, guestTaiko, guestTaiko))
        // Otherwise what the host mapped most of
        assertEquals(listOf(Gamemode.osu), set(osu, guestTaiko))
        // Otherwise what is nominated first
        assertEquals(listOf(Gamemode.osu, Gamemode.taiko), set(osu, taiko))
        // What osu! tells is always right
        assertEquals(listOf(Gamemode.mania), set(osu, osu, taiko, summary = BeatmapSet.NominationsSummary(listOf("mania"))))
        assertEquals(listOf(Gamemode.osu), set(osu, osu, taiko, summary = BeatmapSet.NominationsSummary()))
    }

    @Test
    fun `Should have readable names and icons`() {
        assertEquals(listOf("osu", "taiko", "catch", "mania"), Gamemode.entries.map { it.toReadableName() })
        assertEquals(
            listOf("❤", "💭", "💔", "🗯", "⏳", "💖", "🗑", "🔨"),
            BeatmapStatus.entries.map { it.getEmojiIcon() },
        )
    }

    @Test
    fun `Should find the role of an osu group`() {
        assertEquals(
            listOf(Role.NominationAssessment, Role.Nominator, Role.Probation, Role.Mapper),
            listOf("7", "28", "32", "16").map { Role.fromOsuId(it) },
        )
    }

    private fun permission(vararg roles: Role) =
        getHighestRoleForUser(User("1", "", roles.mapIndexed { index, role -> UserGamemode(Gamemode.entries[index], role) }))

    @Test
    fun `Should use the permission of the highest role`() {
        val viewer = RolePermission(Role.Mapper, setOf("VIEWER"))
        val nominator = RolePermission(Role.Nominator, setOf("VIEWER", "EDITOR"))
        val admin = RolePermission(Role.NominationAssessment, setOf("VIEWER", "EDITOR", "ADMIN"))

        assertEquals(viewer, getHighestRoleForUser(null))
        assertEquals(viewer, permission())
        assertEquals(viewer, permission(Role.Mapper))
        assertEquals(RolePermission(Role.Probation, setOf("VIEWER", "EDITOR")), permission(Role.Mapper, Role.Probation))
        assertEquals(nominator, permission(Role.Probation, Role.Nominator))
        assertEquals(admin, permission(Role.Nominator, Role.NominationAssessment, Role.Mapper))
        assertEquals(
            RolePermission(Role.NominationAssessment, setOf("DEVELOPER", "ADMIN", "VIEWER", "EDITOR")),
            getHighestRoleForUser(User("2369776", "", emptyList())),
        )
        assertEquals("Nominator", nominator.name)
    }

    @Test
    fun `Should skip restricted users`() {
        assertEquals(setOf("3071175", "2697284", "6694242"), RESTRICTED_IDS)
        assertTrue(RESTRICTED_IDS.all { shouldSkipUser(it) })
        assertFalse(shouldSkipUser("1"))
    }

    @Test
    fun `Should wait`() {
        val start = System.nanoTime()
        Sleeper.THREAD.sleep(20)

        assertTrue(System.nanoTime() - start >= 15_000_000)
    }

    @Test
    fun `Should have a value for every Discord color`() {
        assertEquals(
            listOf(
                1752220,
                3066993,
                3447003,
                10181046,
                15844367,
                15105570,
                15158332,
                9807270,
                8359053,
                3426654,
                1146986,
                2067276,
                2123412,
                7419530,
                12745742,
                11027200,
                10038562,
                9936031,
                12370112,
                2899536,
                16580705,
                12320855,
            ),
            EmbedColor.entries.map { it.getValue() },
        )
    }

    @Test
    fun `Should create a Discord message`() {
        val embed = EmbedMessage("text", 1, EmbedThumbnail("url"), EmbedFooter("footer"))

        assertNull(embed.footer.icon_url)
    }

    @Test
    fun `Should know the planner events`() {
        assertEquals(
            listOf(
                "BEATMAP_CREATE",
                "BEATMAP_ADD_NOMINATOR",
                "BEATMAP_REMOVE_NOMINATOR",
                "BEATMAP_UPDATE_OSU_STATUS",
                "BEATMAP_UPDATE_PLANNER_STATUS",
                "BEATMAP_DELETE",
                "USER_CREATE",
                "USER_LOGIN",
                "USER_UPDATE_GROUP",
            ),
            PlannerEvent.entries.map { it.name },
        )
    }

    @Test
    fun `Should start the application when deployed as a war`() {
        val configure = ServletInitializer::class.java.getDeclaredMethod("configure", SpringApplicationBuilder::class.java)
        configure.isAccessible = true
        val builder = configure.invoke(ServletInitializer(), SpringApplicationBuilder()) as SpringApplicationBuilder

        assertEquals(
            setOf("nl.greaper.bnplanner.Application"),
            builder
                .build()
                .allSources
                .map { (it as Class<*>).name }
                .toSet(),
        )
    }

    @Test
    fun `Should connect without credentials unless both are configured`() {
        listOf("" to "", "user" to "", "" to "password", " " to "password").forEach { (username, password) ->
            DatabaseConfig().mongoClient(MongoProperties("db", username, password, "admin", "127.0.0.1", 1)).use {
                assertEquals(
                    listOf("127.0.0.1:1"),
                    it.clusterDescription.clusterSettings.hosts
                        .map { host -> "$host" },
                )
            }
        }
    }

    @Test
    fun `Should look up the cluster using the encoded credentials`() {
        // A cluster can't have a port, so this is refused before anything is looked up. Other characters would be refused earlier.
        val ex =
            assertThrows<IllegalArgumentException> {
                DatabaseConfig().mongoClient(MongoProperties("db", "us/er", "p@ss:word%", "admin", "localhost:1", 1))
            }

        assertTrue("SRV" in ex.message!! || "srv" in ex.message!!, ex.message)
    }
}
