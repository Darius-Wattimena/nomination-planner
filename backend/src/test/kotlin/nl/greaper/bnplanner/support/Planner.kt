package nl.greaper.bnplanner.support

import nl.greaper.bnplanner.auth.OsuLogin
import nl.greaper.bnplanner.client.DiscordClient
import nl.greaper.bnplanner.client.OsuHttpClient
import nl.greaper.bnplanner.config.OsuProperties
import nl.greaper.bnplanner.datasource.BeatmapDataSource
import nl.greaper.bnplanner.datasource.BeatmapEventDataSource
import nl.greaper.bnplanner.datasource.HistoryImportDataSource
import nl.greaper.bnplanner.datasource.RoleHistoryDataSource
import nl.greaper.bnplanner.datasource.UserDataSource
import nl.greaper.bnplanner.model.Gamemode
import nl.greaper.bnplanner.model.Role
import nl.greaper.bnplanner.model.User
import nl.greaper.bnplanner.model.UserGamemode
import nl.greaper.bnplanner.model.beatmap.Beatmap
import nl.greaper.bnplanner.model.beatmap.BeatmapGamemode
import nl.greaper.bnplanner.model.beatmap.BeatmapNominator
import nl.greaper.bnplanner.model.beatmap.BeatmapStatus
import nl.greaper.bnplanner.service.AuthService
import nl.greaper.bnplanner.service.BeatmapService
import nl.greaper.bnplanner.service.HistoryImportService
import nl.greaper.bnplanner.service.OsuJobs
import nl.greaper.bnplanner.service.OsuService
import nl.greaper.bnplanner.service.ProfileService
import nl.greaper.bnplanner.service.RoleHistoryService
import nl.greaper.bnplanner.service.SyncService
import nl.greaper.bnplanner.service.TransferService
import nl.greaper.bnplanner.service.UserService
import nl.greaper.bnplanner.util.Sleeper
import org.junit.jupiter.api.Assertions.assertTrue
import tools.jackson.module.kotlin.jacksonMapperBuilder
import java.time.Instant

/** The whole application without Spring, using a real (emptied) MongoDB and a fake osu! */
class Planner(
    withDiscord: Boolean = true,
) {
    init {
        TestMongo.clear()
        FakeOsu.reset()
    }

    val discord = RecordingDiscord()
    private val discordClient: DiscordClient? = discord.takeIf { withDiscord }
    val beatmaps = BeatmapDataSource(TestMongo.database, true)
    val users = UserDataSource(TestMongo.database)

    val osuClient = osuClient()
    val userService = UserService(users, osuClient, discordClient)
    val events = BeatmapEventDataSource(TestMongo.database)
    val beatmapService = BeatmapService(beatmaps, userService, osuClient, discordClient, events)
    val osuService = OsuService(osuClient)
    val authService = AuthService(discordClient)
    val osuJobs = OsuJobs(beatmapService, userService, osuService)
    val syncService = SyncService(userService, beatmapService, osuJobs, osuClient)
    val profileService = ProfileService(beatmapService, userService)
    val roleHistories = RoleHistoryDataSource(TestMongo.database)
    val roleHistory = RoleHistoryService(roleHistories, osuClient, jacksonMapperBuilder().build())
    val transferService = TransferService(beatmaps, events, users, userService, roleHistory, jacksonMapperBuilder().build())
    val historyProgress = HistoryImportDataSource(TestMongo.database)

    /** How long the import of the ranked history waited between calls to osu! */
    val historySleeps = mutableListOf<Long>()
    val historyImport =
        HistoryImportService(
            osuClient,
            osuService,
            userService,
            users,
            beatmaps,
            events,
            historyProgress,
            roleHistory,
            perMinute = 60,
            sleeper = { historySleeps += it },
            // No time passes, so every call to osu! waits the whole second
            clock = { 0L },
            today = { java.time.LocalDate.of(2014, 2, 10) },
        )

    fun user(
        id: String,
        username: String = "User $id",
        vararg gamemodes: UserGamemode,
    ): User = User(id, username, gamemodes.toList()).also { users.saveUser(it) }

    fun save(beatmap: Beatmap): Beatmap = beatmap.also { beatmaps.insertOne(it) }

    fun stored(id: String = "1"): Beatmap = beatmaps.findById(id)!!

    companion object {
        val ADDED: Instant = Instant.parse("2024-01-01T10:00:00Z")
        val UPDATED: Instant = Instant.parse("2024-02-02T11:12:13.456Z")

        /** Token of the user performing an action */
        const val TOKEN = "made-up-token"

        /** The user performing an action */
        val LOGIN = OsuLogin("99") { TOKEN }

        // Configured the same way as Spring does
        fun osuClient() =
            OsuHttpClient(
                OsuProperties("14229", "made-up-secret", "http://localhost:3000/login", FakeOsu.url, minRequestIntervalMillis = 0),
                jacksonMapperBuilder().build(),
                // Only waits when osu! asks for it, which tests don't have to do for real
                Sleeper {},
            )

        fun mode(
            gamemode: Gamemode,
            first: String = "0",
            second: String = "0",
            firstNominated: Boolean = false,
            secondNominated: Boolean = false,
        ) = BeatmapGamemode(gamemode, listOf(BeatmapNominator(first, firstNominated), BeatmapNominator(second, secondNominated)))

        fun beatmap(
            id: String = "1",
            vararg gamemodes: BeatmapGamemode = arrayOf(mode(Gamemode.osu)),
            status: BeatmapStatus = BeatmapStatus.Pending,
            artist: String = "Artist $id",
            title: String = "Title $id",
            mapper: String = "Mapper",
            mapperId: String = "10",
            note: String = "",
            updated: Instant = UPDATED,
            ranked: Instant? = null,
            main: List<Gamemode>? = null,
        ) = Beatmap(id, artist, title, note, mapper, mapperId, status, gamemodes.toList(), ADDED, updated, ranked, main)

        fun nominator(gamemode: Gamemode) = UserGamemode(gamemode, Role.Nominator)

        fun assertRecent(instant: Instant?) {
            val now = Instant.now()
            assertTrue(instant!! > now.minusSeconds(60) && instant <= now, "Expected $instant to be just before $now")
        }
    }
}
